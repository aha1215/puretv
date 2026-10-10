package com.puretv.twitch.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GetVideosByIdsTest {
    @Test
    fun idsAreDeduplicatedAndBatchedAtHelixLimit() = runTest {
        val requestSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            requestSizes += request.url.parameters.getAll("id")?.size ?: 0
            respond("""{"data":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val api = TwitchApiClient(client) { "token" }

        api.getVideosByIds((1..101).map { it.toString() } + listOf("1", "not-a-video"))

        assertEquals(listOf(100, 1), requestSizes)
    }
}
