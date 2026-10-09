package com.puretv.twitch.tv.data

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.util.LinkedHashMap

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

    suspend fun translate(message: String, targetLanguage: String): Result? {
        val text = message.trim()
        val target = targetLanguage.trim().ifBlank { "en" }
        if (!shouldTranslate(text) || !LANGUAGE_TAG.matches(target)) return null
        val cacheKey = "${target.lowercase()}\u0000${text.lowercase()}"

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
            val result = requests.withPermit { requestTranslation(text, target) }
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

    private suspend fun requestTranslation(text: String, targetLanguage: String): Result? {
        val payload = JSONArray()
            .put(
                JSONArray()
                    .put(JSONArray().put(text))
                    .put("auto")
                    .put(targetLanguage),
            )
            .put("te_lib")
        val body = httpClient.post(TRANSLATE_URL) {
            API_HEADERS.forEach { (name, value) -> header(name, value) }
            setBody(payload.toString())
        }.bodyAsText()

        val root = JSONArray(body)
        val firstRow = root.optJSONArray(0) ?: return null
        // Response segments are nested arrays; recursively join their text so
        // Android does not expose a JSONArray's JSON representation as chat text.
        val translated = translationText(firstRow.opt(0)).trim()
        if (translated.isBlank() || translated.equals(text, ignoreCase = true)) return null
        val sourceLanguage = root.optJSONArray(1)?.optString(0).orEmpty().ifBlank {
            (firstRow.opt(1) as? String).orEmpty().ifBlank {
                (firstRow.opt(2) as? String).orEmpty()
            }
        }
        if (sameLanguage(sourceLanguage, targetLanguage)) return null
        return Result(translated, sourceLanguage)
    }

    private fun translationText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                append(translationText(value.opt(index)))
            }
        }
        else -> ""
    }

    private fun shouldTranslate(text: String): Boolean {
        if (text.length < 2 || text.length > MAX_MESSAGE_LENGTH) return false
        val normalized = text.lowercase().trim()
        if (normalized.startsWith("!") || normalized.startsWith("/")) return false
        if (normalized in COMMON_CHAT_TOKENS || normalized.all(Char::isDigit)) return false
        return text.any(Char::isLetter)
    }

    private fun sameLanguage(source: String, target: String): Boolean {
        if (source.isBlank()) return false
        val sourceBase = source.substringBefore('-').substringBefore('_')
        val targetBase = target.substringBefore('-').substringBefore('_')
        return sourceBase.equals(targetBase, ignoreCase = true)
    }

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
