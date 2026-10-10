package com.puretv.twitch.tv.data

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import android.util.Log
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import java.util.LinkedHashMap
import java.util.UUID

/**
 * TV-side port of Chatslator's incoming-message behavior: detect source language,
 * skip short/common Twitch chat tokens, replace only messages that need translation,
 * and share cached/in-flight translations across duplicate chat lines.
 *
 * Uses Google's translateHtml endpoint with the API key and protobuf JSON payload
 * used by Chatslator's faster translation path.
 */
class ChatslatorTranslationClient(private val httpClient: HttpClient) {
    data class Result(val text: String, val sourceLanguage: String)

    private val mutex = Mutex()
    private val requests = Semaphore(6)
    private val cache = object : LinkedHashMap<String, Result?>(CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result?>?): Boolean =
            size > CACHE_SIZE
    }
    private val inFlight = mutableMapOf<String, CompletableDeferred<Result?>>()

    suspend fun translate(
        message: ChatMessage,
        targetLanguage: String,
        knownEmoteNames: Collection<String> = emptyList(),
    ): Result? {
        val text = message.message.trim()
        val target = targetLanguage.trim().ifBlank { "en" }
        if (!LANGUAGE_TAG.matches(target)) return null

        // Tagged Twitch emotes and already-tokenized third-party emotes are
        // trusted metadata. The additional names come from the active emote
        // index, so arbitrary chat words that merely look like emotes stay text.
        val emoteNames = buildSet {
            addAll(knownEmoteNames)
            message.parsedParts.forEach { part ->
                when (part) {
                    is MessagePart.TwitchEmote -> add(part.name)
                    is MessagePart.ThirdPartyEmote -> add(part.name)
                    is MessagePart.Text -> Unit
                }
            }
        }
        val protected = ChatTranslationText.maskEmotes(text, emoteNames) ?: return null
        if (!shouldTranslate(protected.translatableText)) return null
        val cacheKey = buildString {
            append(target.lowercase()).append('\u0000').append(text.lowercase())
            protected.emotes.forEach { append('\u0000').append(it.original) }
        }

        var cached = false
        var cachedResult: Result? = null
        var shared: CompletableDeferred<Result?>? = null
        var isOwner = false
        mutex.withLock {
            if (cache.containsKey(cacheKey)) {
                cached = true
                cachedResult = cache[cacheKey]
            } else {
                shared = inFlight[cacheKey]
                if (shared == null) {
                    shared = CompletableDeferred()
                    inFlight[cacheKey] = shared!!
                    isOwner = true
                }
            }
        }
        if (cached) return cachedResult
        val deferred = checkNotNull(shared)
        if (!isOwner) return deferred.await()

        return try {
            val result = requests.withPermit { requestTranslation(protected, target) }
            mutex.withLock {
                cache[cacheKey] = result
                inFlight.remove(cacheKey)
            }
            deferred.complete(result)
            result
        } catch (cancelled: CancellationException) {
            mutex.withLock { inFlight.remove(cacheKey) }
            deferred.complete(null)
            throw cancelled
        } catch (error: Exception) {
            mutex.withLock {
                inFlight.remove(cacheKey)
            }
            deferred.complete(null)
            // Do not cache a transport/API failure as "no translation". A
            // temporary DNS, TLS, rate-limit, or response error should be
            // retried when the same message is encountered again.
            Log.w(TAG, "Chatslator-compatible translation request failed (${error.javaClass.simpleName}): ${error.message}")
            null
        }
    }

    private suspend fun requestTranslation(text: ChatTranslationText.MaskedText, targetLanguage: String): Result? {
        val payload = buildJsonArray {
            add(
                buildJsonArray {
                    add(buildJsonArray { add(JsonPrimitive(text.maskedText)) })
                    add(JsonPrimitive("auto"))
                    add(JsonPrimitive(targetLanguage))
                },
            )
            add(JsonPrimitive("te_lib"))
        }
        val body = httpClient.post(TRANSLATE_URL) {
            API_HEADERS.forEach { (name, value) -> header(name, value) }
            setBody(payload.toString())
        }.bodyAsText()

        val root = Json.parseToJsonElement(body) as? JsonArray ?: return null
        val firstRow = root.getOrNull(0) as? JsonArray ?: return null
        // Response segments are nested arrays; recursively join their text so
        // Android does not expose a JSONArray's JSON representation as chat text.
        val translated = translationText(firstRow.getOrNull(0)).trim()
        if (translated.isBlank() || translated.equals(text.maskedText, ignoreCase = true)) return null
        val sourceLanguage = root.getOrNull(1).stringAt(0)
            .ifBlank { firstRow.getOrNull(1).stringValue().orEmpty() }
            .ifBlank { firstRow.getOrNull(2).stringValue().orEmpty() }
            .trim()
        // A missing/invalid detected language cannot safely distinguish a real
        // translation from same-language noise. `auto` above is the configured
        // source; only this response value is used for filtering.
        if (!LANGUAGE_TAG.matches(sourceLanguage) || sameLanguage(sourceLanguage, targetLanguage)) return null
        val restored = ChatTranslationText.restoreEmotes(translated, text) ?: return null
        if (restored.isBlank() || restored.equals(text.originalText, ignoreCase = true)) return null
        return Result(restored, sourceLanguage)
    }

    private fun translationText(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.joinToString(separator = "") { translationText(it) }
        else -> ""
    }

    private fun JsonElement?.stringAt(index: Int): String =
        (this as? JsonArray)?.getOrNull(index).stringValue().orEmpty()

    private fun JsonElement?.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun shouldTranslate(text: String): Boolean {
        if (text.length < 2 || text.length > MAX_MESSAGE_LENGTH) return false
        val normalized = text.lowercase().trim()
        if (normalized.startsWith("!") || normalized.startsWith("/")) return false
        if (normalized in COMMON_CHAT_TOKENS || normalized.all(Char::isDigit)) return false
        return text.any(Char::isLetter)
    }

    private fun sameLanguage(source: String, target: String): Boolean =
        ChatTranslationText.sameLanguage(source, target)

    private companion object {
        const val TAG = "ChatslatorTranslation"
        const val CACHE_SIZE = 500
        const val MAX_MESSAGE_LENGTH = 1_000
        const val TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml"
        val API_HEADERS = mapOf(
            "X-Goog-API-Key" to "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520",
            "Content-Type" to "application/json+protobuf",
        )
        val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")
        val COMMON_CHAT_TOKENS = setOf(
            "gg", "glhf", "gj", "nt", "gr", "ty", "lol", "lmao", "rofl", "kekw",
            "pog", "pogchamp", "kappa", "kapp", "monkaw", "lul", "omegalul", "wtf",
            "sadge", "peepo", "peepohappy", "peeposad", "f", "w", "ww", "l", "ll",
            "yo", "cya", "o/", "zz", "zzz", "ez", "xd", "xdd", "om", "??", "???",
            "!", "!!", "...",
        )
    }
}

/** Pure masking/restoration helpers, kept separate so placeholder failures can be tested without networking. */
internal object ChatTranslationText {
    internal data class EmotePlaceholder(val placeholder: String, val original: String)

    internal data class MaskedText(
        val originalText: String,
        val maskedText: String,
        val translatableText: String,
        val emotes: List<EmotePlaceholder>,
    )

    fun maskEmotes(text: String, names: Collection<String>): MaskedText? {
        val candidates = names.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
            .toList()
        if (candidates.isEmpty()) return MaskedText(text, text, text, emptyList())

        val alternatives = candidates.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?<![\\p{L}\\p{N}_])($alternatives)(?![\\p{L}\\p{N}_])")
        val nonce = UUID.randomUUID().toString().replace("-", "")
        if (text.contains("ZXQ${nonce}E", ignoreCase = true)) return null

        val placeholders = mutableListOf<EmotePlaceholder>()
        val masked = pattern.replace(text) { match ->
            val placeholder = "ZXQ${nonce}E${placeholders.size}QXZ"
            placeholders += EmotePlaceholder(placeholder, match.value)
            placeholder
        }
        val translatable = pattern.replace(text, " ").trim()
        return MaskedText(text, masked, translatable, placeholders)
    }

    fun restoreEmotes(translated: String, masked: MaskedText): String? {
        if (masked.emotes.isEmpty()) return translated
        val positions = masked.emotes.map { emote ->
            val first = translated.indexOf(emote.placeholder)
            if (first < 0 || translated.indexOf(emote.placeholder, first + emote.placeholder.length) >= 0) return null
            first
        }
        if (positions.zipWithNext().any { (left, right) -> left >= right }) return null

        // A provider must not alter a token or invent a malformed sibling.
        val marker = Regex("ZXQ[0-9a-fA-F]{32}E\\d+QXZ")
        val foundMarkers = marker.findAll(translated).map { it.value }.toList()
        if (foundMarkers != masked.emotes.map { it.placeholder }) return null

        var restored = translated
        masked.emotes.forEach { restored = restored.replace(it.placeholder, it.original) }
        return restored
    }

    fun sameLanguage(source: String, target: String): Boolean {
        fun base(code: String): String = code.trim().replace('_', '-').substringBefore('-').lowercase()
        val sourceBase = base(source)
        val targetBase = base(target)
        return sourceBase.isNotBlank() && targetBase.isNotBlank() && sourceBase == targetBase
    }
}
