package com.puretv.twitch.tv.ui

import com.puretv.twitch.tv.data.AppSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewedVodHistoryMergeTest {
    @Test
    fun remoteOrderIsKeptLocalOnlyItemsFollowAndDuplicateUsesFurthestPosition() {
        val local = listOf(vod("local", position = 20_000), vod("shared", title = "Local title", position = 90_000))
        val remote = listOf(vod("shared", title = "Remote title", position = 45_000), vod("remote", position = 12_000))

        val merged = mergeViewedVods(local, remote)

        assertEquals(listOf("shared", "remote", "local"), merged.map { it.id })
        assertEquals("Local title", merged.first().title)
        assertEquals(90_000L, merged.first().positionMs)
    }

    @Test
    fun emptyRemoteHistoryLeavesLocalHistoryInOrder() {
        val local = listOf(vod("first"), vod("second"))

        assertEquals(local, mergeViewedVods(local, emptyList()))
    }

    @Test
    fun checkpointsAreClampedToKnownDuration() {
        val local = listOf(vod("shared", position = 125_000), vod("local", position = 130_000))
        val remote = listOf(vod("shared", position = 110_000).copy(durationSeconds = 80L))

        val merged = mergeViewedVods(local, remote)

        assertEquals(80_000L, merged[0].positionMs)
        assertEquals(100_000L, merged[1].positionMs)
    }

    private fun vod(id: String, title: String = id, position: Long = 0L) =
        AppSettingsStore.ViewedVod(
            id = id,
            channelLogin = "channel",
            title = title,
            thumbnailUrl = "",
            durationSeconds = 100L,
            lastViewedEpochMs = 0L,
            positionMs = position,
        )
}
