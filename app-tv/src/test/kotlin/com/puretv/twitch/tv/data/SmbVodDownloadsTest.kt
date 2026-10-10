package com.puretv.twitch.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmbVodDownloadsTest {
    @Test
    fun rewritesSegmentAndKeyUrisToBundleFiles() {
        val playlistUrl = "https://cdn.example/vod/index.m3u8"
        val content = """#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin"
#EXTINF:4.0,
segments/part0.ts
#EXT-X-ENDLIST"""
        val resources = mapOf(
            "https://cdn.example/keys/key.bin" to "media_000000.bin",
            "https://cdn.example/vod/segments/part0.ts" to "media_000001.ts",
        )

        assertEquals(
            """#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="media_000000.bin"
#EXTINF:4.0,
media_000001.ts
#EXT-X-ENDLIST""",
            rewriteHlsPlaylist(content, playlistUrl, resources),
        )
    }

    @Test
    fun smbFolderRejectsParentTraversal() {
        assertFailsWith<IllegalArgumentException> { smbFolderPath("shared/../private") }
    }

    @Test
    fun smbFolderKeepsNestedRelativePath() {
        assertEquals("media/shared", smbFolderPath("media/shared").toString().replace('\\', '/'))
    }
}
