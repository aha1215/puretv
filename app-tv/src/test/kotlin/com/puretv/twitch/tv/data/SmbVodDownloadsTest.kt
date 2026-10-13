package com.puretv.twitch.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun savedVodUriConstructionDoesNotNeedAConnectionOrCredentials() {
        assertEquals(
            "smb://nas.local/media/shared/puretv-vods/123/index.m3u8",
            buildSmbVodUri(SmbShareSettings("nas.local", "media", "shared"), "123"),
        )
    }

    @Test
    fun savedVodUriEncodesReservedCharactersInSharePaths() {
        assertEquals(
            "smb://nas.local/My%20Share/Media%20%23%20TV/puretv-vods/123/index.m3u8",
            buildSmbVodUri(SmbShareSettings("nas.local", "My Share", "Media # TV"), "123"),
        )
    }

    @Test
    fun savedVodUriUsesTheManifestNameStoredWithTheBundle() {
        assertEquals(
            "smb://nas.local/media/shared/puretv-vods/123/master.m3u8",
            buildSmbVodUri(SmbShareSettings("nas.local", "media", "shared"), "123", "master.m3u8"),
        )
    }

    @Test
    fun savedVodUriCanReadTheAtomicReplacementBackupDirectory() {
        assertEquals(
            "smb://nas.local/media/shared/puretv-vods/.123.backup/index.m3u8",
            buildSmbVodUri(
                SmbShareSettings("nas.local", "media", "shared"),
                "123",
                bundleDirectoryName = ".123.backup",
            ),
        )
    }

    @Test
    fun savedVodUriCanReadTheConvertedHlsCopyBesideTheDownloaderArchive() {
        assertEquals(
            "smb://nas.local/media/shared/puretv-vods/gilticus-garage-vod/puretv-hls/index.m3u8",
            buildSmbVodUri(
                SmbShareSettings("nas.local", "media", "shared"),
                "2842341648",
                manifestName = "puretv-hls/index.m3u8",
                bundleDirectoryName = "gilticus-garage-vod",
            ),
        )
    }

    @Test
    fun resourceRoutingRecognizesSmbUrisAndLeavesOtherSchemesToFallback() {
        assertTrue(isSmbResourceScheme("smb"))
        assertTrue(isSmbResourceScheme("SMB"))
        assertEquals(false, isSmbResourceScheme("https"))
        assertEquals(false, isSmbResourceScheme(null))
    }

    @Test
    fun positionalReadAheadHandlesPartialReadsAndTracksRemoteOffsets() {
        val source = SmbReadAheadBuffer(ByteArray(8))
        val content = ByteArray(32) { it.toByte() }
        val offsets = mutableListOf<Long>()
        source.seek(5L)
        val output = ByteArray(6)
        fun remoteRead(position: Long, target: ByteArray, requested: Int): Int {
            offsets += position
            val count = minOf(3, requested, content.size - position.toInt())
            content.copyInto(target, 0, position.toInt(), position.toInt() + count)
            return count
        }

        assertEquals(2, source.read(output, 0, 2, 6, ::remoteRead))
        assertEquals(1, source.read(output, 2, 4, 4, ::remoteRead))
        assertEquals(3, source.read(output, 3, 3, 3, ::remoteRead))
        assertEquals(listOf(5L, 8L), offsets)
        assertEquals(listOf<Byte>(5, 6, 7, 8, 9, 10), output.toList())
    }

    @Test
    fun seekOffsetInvalidatesBufferedBytesAndHonorsLengthLimit() {
        val source = SmbReadAheadBuffer(ByteArray(8))
        val content = ByteArray(20) { it.toByte() }
        val offsets = mutableListOf<Long>()
        val output = ByteArray(8)
        val remoteRead: (Long, ByteArray, Int) -> Int = { position, target, requested ->
            offsets += position
            val count = minOf(requested, content.size - position.toInt())
            content.copyInto(target, 0, position.toInt(), position.toInt() + count)
            count
        }

        source.seek(2L)
        assertEquals(2, source.read(output, 0, 8, 2, remoteRead))
        source.seek(12L)
        assertEquals(3, source.read(output, 0, 8, 3, remoteRead))
        assertEquals(listOf(2L, 12L), offsets)
        assertEquals(listOf<Byte>(12, 13, 14), output.take(3))
        assertEquals(0, source.read(output, 0, 0, 3, remoteRead))
        assertTrue(offsets.size == 2)
    }
}
