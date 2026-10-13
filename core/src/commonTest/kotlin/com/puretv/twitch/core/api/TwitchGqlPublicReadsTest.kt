package com.puretv.twitch.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GQL public-read mapping is pure and schema-driven, so it is verified here
 * against payloads captured from the live endpoint (the same shapes the queries
 * in [TwitchGqlPublicReads] request). A missing/blank field must degrade the row,
 * not throw — these tests pin that down for the fields the UI actually uses.
 */
class TwitchGqlPublicReadsTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun topStreamsMapCoreFields() {
        val data = obj(
            """{"streams":{"edges":[{"node":{"id":"1","title":"T","viewersCount":123,
               "game":{"id":"g1","name":"Just Chatting"},
               "broadcaster":{"id":"b1","login":"chan","displayName":"Chan"},
               "previewImageURL":"https://img"}}]}}""",
        )
        val streams = parseGqlStreams(data)
        assertEquals(1, streams.size)
        val s = streams.single()
        assertEquals("1", s.id)
        assertEquals("b1", s.userId)
        assertEquals("chan", s.userLogin)
        assertEquals("Chan", s.userName)
        assertEquals("g1", s.gameId)
        assertEquals("Just Chatting", s.gameName)
        assertEquals("T", s.title)
        assertEquals(123, s.viewerCount)
        assertEquals("https://img", s.thumbnailUrl)
    }

    @Test fun userStreamsDropOfflineChannels() {
        val data = obj(
            """{"users":[
                {"id":"b1","login":"on","displayName":"On","stream":{"id":"1","title":"T","viewersCount":5,
                 "game":{"id":"g1","name":"G"},"previewImageURL":"u"}},
                {"id":"b2","login":"off","displayName":"Off","stream":null}]}""",
        )
        val streams = parseGqlUserStreams(data)
        assertEquals(1, streams.size)
        assertEquals("on", streams.single().userLogin)
    }

    @Test fun gameStreamsUseKnownGameIdWhenNodeOmitsIt() {
        val data = obj(
            """{"game":{"streams":{"edges":[{"node":{"id":"1","title":"T","viewersCount":5,"game":null,
               "broadcaster":{"id":"b","login":"l","displayName":"D"},"previewImageURL":"u"}}]}}}""",
        )
        assertEquals("gX", parseGqlGameStreams(data, "gX").single().gameId)
    }

    @Test fun topGamesMapBoxArt() {
        val data = obj("""{"games":{"edges":[{"node":{"id":"g1","name":"N","boxArtURL":"ba"}}]}}""")
        val games = parseGqlGames(data)
        assertEquals(1, games.size)
        assertEquals("g1", games.single().id)
        assertEquals("N", games.single().name)
        assertEquals("ba", games.single().boxArtUrl)
    }

    @Test fun searchMapsLiveAndOffline() {
        val data = obj(
            """{"searchFor":{"channels":{"edges":[
                {"item":{"id":"1","login":"xqc","displayName":"xQc","profileImageURL":"p",
                 "stream":{"id":"s","title":"t","viewersCount":9,"game":{"name":"GTA"}}}},
                {"item":{"id":"2","login":"off","displayName":"Off","profileImageURL":"p2","stream":null}}]}}}""",
        )
        val results = parseGqlSearchChannels(data)
        assertEquals(2, results.size)
        val live = results.first { it.broadcaster_login == "xqc" }
        assertTrue(live.is_live)
        assertEquals("GTA", live.game_name)
        assertEquals("p", live.thumbnail_url)
        assertFalse(results.first { it.broadcaster_login == "off" }.is_live)
    }

    @Test fun missingDataDegradesToEmpty() {
        assertEquals(emptyList(), parseGqlStreams(null))
        assertEquals(emptyList(), parseGqlUserStreams(null))
        assertEquals(emptyList(), parseGqlGameStreams(null, "g"))
        assertEquals(emptyList(), parseGqlGames(null))
        assertEquals(emptyList(), parseGqlSearchChannels(null))
    }

    @Test fun blankFieldsDoNotThrow() {
        val data = obj("""{"streams":{"edges":[{"node":{}}]}}""")
        val s = parseGqlStreams(data).single()
        assertEquals("", s.id)
        assertEquals(0, s.viewerCount)
    }
}
