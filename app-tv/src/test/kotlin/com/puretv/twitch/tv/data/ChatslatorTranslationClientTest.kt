package com.puretv.twitch.tv.data

import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatslatorTranslationClientTest {
    @Test
    fun repeatedEmotesAreMaskedAndRestoredExactly() {
        val original = "Hola kappa cómo estás Kappa"
        val masked = ChatTranslationText.maskEmotes(original, setOf("kappa", "Kappa"))!!

        assertEquals("Hola   cómo estás", masked.translatableText)
        assertEquals(2, masked.emotes.size)
        assertTrue(masked.maskedText.contains(masked.emotes[0].placeholder))
        assertEquals(original, ChatTranslationText.restoreEmotes(masked.maskedText, masked))
    }

    @Test
    fun unknownEmoteLikeWordsAreNotMaskedAndAllEmoteMessagesHaveNoTranslatableText() {
        val unknown = ChatTranslationText.maskEmotes("Hola Kappaish", setOf("Kappa"))!!
        assertTrue(unknown.emotes.isEmpty())
        assertEquals("Hola Kappaish", unknown.translatableText)

        val allEmotes = ChatTranslationText.maskEmotes("Kappa PogChamp Kappa", setOf("Kappa", "PogChamp"))!!
        assertTrue(allEmotes.translatableText.isBlank())
    }

    @Test
    fun rejectsMissingDuplicatedMalformedAndReorderedPlaceholders() {
        val masked = ChatTranslationText.maskEmotes("Hola Kappa PogChamp", setOf("Kappa", "PogChamp"))!!
        val first = masked.emotes[0].placeholder
        val second = masked.emotes[1].placeholder

        assertNull(ChatTranslationText.restoreEmotes("Hello $first", masked))
        assertNull(ChatTranslationText.restoreEmotes("Hello $first $second $second", masked))
        assertNull(ChatTranslationText.restoreEmotes("Hello ZXQbrokenQXZ $second", masked))
        assertNull(ChatTranslationText.restoreEmotes("Hello $second $first", masked))
    }

    @Test
    fun comparesRegionalLanguageCodesByBaseLanguage() {
        assertTrue(ChatTranslationText.sameLanguage("en_GB", "en"))
        assertTrue(ChatTranslationText.sameLanguage("en", "en-US"))
        assertTrue(ChatTranslationText.sameLanguage("pt-BR", "pt-PT"))
        assertTrue(!ChatTranslationText.sameLanguage("es", "en"))
    }

    @Test
    fun translatesSpanishTextAndRestoresEmoteUsingUniquePlaceholder() = runBlocking {
        val client = client { maskedText ->
            val translated = maskedText.replace("Hola", "Hello")
            response(translated, "es")
        }

        val result = client.translator.translate(message("Hola PogChamp"), "en", setOf("PogChamp"))

        assertEquals("Hello PogChamp", result?.text)
        assertEquals("es", result?.sourceLanguage)
        client.http.close()
    }

    @Test
    fun skipsTargetLanguageAndMissingDetection() = runBlocking {
        val english = client { maskedText -> response(maskedText.replace("Hello", "Hi"), "en-GB") }
        assertNull(english.translator.translate(message("Hello PogChamp"), "en", setOf("PogChamp")))
        english.http.close()

        val missing = client { maskedText -> response(maskedText.replace("Hola", "Hello"), "") }
        assertNull(missing.translator.translate(message("Hola"), "en"))
        missing.http.close()
    }

    @Test
    fun doesNotCallTranslatorForEmoteOnlyMessages() = runBlocking {
        var requestCount = 0
        val http = HttpClient(MockEngine {
            requestCount++
            respond(response("unused", "es"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val translator = ChatslatorTranslationClient(http)
        val emoteMessage = message(
            "Kappa Kappa",
            listOf(MessagePart.TwitchEmote("25", "Kappa"), MessagePart.Text(" "), MessagePart.TwitchEmote("25", "Kappa")),
        )

        assertNull(translator.translate(emoteMessage, "en"))
        assertEquals(0, requestCount)
        http.close()
    }

    @Test
    fun preservesTranslationCacheForDuplicateMessages() = runBlocking {
        var requestCount = 0
        val http = HttpClient(MockEngine { request ->
            requestCount++
            val content = request.body as OutgoingContent.ByteArrayContent
            val text = requestText(content)
            respond(response(text.replace("Hola", "Hello"), "es"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val translator = ChatslatorTranslationClient(http)
        val original = message("Hola")

        assertEquals("Hello", translator.translate(original, "en")?.text)
        assertEquals("Hello", translator.translate(original, "en")?.text)
        assertEquals(1, requestCount)
        http.close()
    }

    private data class TestClient(val http: HttpClient, val translator: ChatslatorTranslationClient)

    private fun client(responseFor: (String) -> String): TestClient {
        val http = HttpClient(MockEngine { request ->
            val content = request.body as OutgoingContent.ByteArrayContent
            val maskedText = requestText(content)
            respond(responseFor(maskedText), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return TestClient(http, ChatslatorTranslationClient(http))
    }

    private fun requestText(content: OutgoingContent.ByteArrayContent): String =
        Json.parseToJsonElement(content.bytes().decodeToString())
            .jsonArray[0].jsonArray[0].jsonArray[0].jsonPrimitive.content

    private fun response(translated: String, sourceLanguage: String): String =
        buildJsonArray {
            add(buildJsonArray { add(buildJsonArray { add(JsonPrimitive(translated)) }) })
            add(buildJsonArray { add(JsonPrimitive(sourceLanguage)) })
        }.toString()

    private fun message(text: String, parts: List<MessagePart> = listOf(MessagePart.Text(text))) = ChatMessage(
        id = "message-id",
        channel = "channel",
        username = "viewer",
        displayName = "Viewer",
        color = "",
        message = text,
        parsedParts = parts,
        badges = emptyList(),
        timestamp = 0L,
        isSubscriber = false,
        isModerator = false,
        isBroadcaster = false,
    )
}
