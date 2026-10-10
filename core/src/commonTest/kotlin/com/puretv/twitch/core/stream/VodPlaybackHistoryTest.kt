package com.puretv.twitch.core.stream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VodPlaybackHistoryTest {
    @Test
    fun historyIncludesViewerAndKeepsTwitchOrder() = runTest {
        val response = """{"data":{"currentUser":{"id":"viewer-123","viewedVideos":{"edges":[
            {"history":{"position":30},"node":{"id":"456"}},
            {"history":{"position":7},"node":{"id":"789"}}
        ]}}}}"""
        val client = HttpClient(MockEngine {
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val gql = TwitchGqlClient(client)

        val history = gql.fetchVodPlaybackHistory("oauth-token").getOrThrow()

        assertEquals("viewer-123", history.userId)
        assertEquals(listOf("456", "789"), history.entries.map { it.vodId })
        assertEquals(listOf(30_000L, 7_000L), history.entries.map { it.positionMs })
    }
}
