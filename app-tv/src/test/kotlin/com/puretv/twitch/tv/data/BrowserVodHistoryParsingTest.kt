package com.puretv.twitch.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserVodHistoryParsingTest {
    @Test
    fun parsesTwitchVodLinksAndTimestampPositions() {
        assertEquals("123456789", BrowserVodSyncServer.twitchVodIdFromUrl("https://www.twitch.tv/videos/123456789?t=1h2m3s"))
        assertEquals("987654321", BrowserVodSyncServer.twitchVodIdFromUrl("https://twitch.tv/channel/v/987654321"))
        assertEquals(3_723_000L, BrowserVodSyncServer.twitchVodPositionMs("https://www.twitch.tv/videos/123456789?t=1h2m3s"))
    }

    @Test
    fun rejectsUntrustedHostsMalformedTimestampsAndTimestampLessLinks() {
        assertNull(BrowserVodSyncServer.twitchVodIdFromUrl("https://twitch.tv.evil.example/videos/123456789?t=1s"))
        assertNull(BrowserVodSyncServer.twitchVodPositionMs("https://www.twitch.tv/videos/123456789"))
        assertNull(BrowserVodSyncServer.twitchVodPositionMs("https://www.twitch.tv/videos/123456789?t=999999999999999999999h"))
    }
}
