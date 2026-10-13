package com.puretv.twitch.tv.ui

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class VodPositionPersistenceTest {
    @Test
    fun savedBundlePersistsSettledPositionLocallyWithoutTwitchSync() = runBlocking {
        var localPosition = -1L
        var remoteCalls = 0

        persistVodPosition(
            positionMs = 12_345L,
            isSavedBundle = true,
            persistLocally = { localPosition = it },
            syncTwitch = { remoteCalls++ },
        )

        assertEquals(12_345L, localPosition)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun twitchVodPersistsLocallyAndSynchronizesPosition() = runBlocking {
        var localPosition = -1L
        var remotePosition = -1L

        persistVodPosition(
            positionMs = 8_765L,
            isSavedBundle = false,
            persistLocally = { localPosition = it },
            syncTwitch = { remotePosition = it },
        )

        assertEquals(8_765L, localPosition)
        assertEquals(8_765L, remotePosition)
    }

    @Test
    fun positionIsClampedBeforePersistence() = runBlocking {
        var localPosition = -1L

        persistVodPosition(
            positionMs = -50L,
            isSavedBundle = true,
            persistLocally = { localPosition = it },
            syncTwitch = {},
        )

        assertEquals(0L, localPosition)
    }
}
