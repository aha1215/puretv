package com.puretv.twitch.core.repository

import com.puretv.twitch.core.api.TwitchApiClient
import com.puretv.twitch.core.api.VideoPage
import com.puretv.twitch.core.model.VideoType
import com.puretv.twitch.core.stream.MasterPlaylistResult
import com.puretv.twitch.core.stream.VodResolver

/**
 * Discovery + playback resolution for past videos. Parallel to
 * [StreamRepository]; kept separate so live and archive concerns don't tangle.
 */
class VodRepository(
    private val apiClient: TwitchApiClient,
    private val vodResolver: VodResolver,
) {
    suspend fun videosFor(
        userId: String,
        type: VideoType? = null,
        first: Int = 20,
        after: String? = null,
    ): VideoPage =
        if (userId.isBlank()) VideoPage(emptyList(), null)
        else apiClient.getVideos(userId, type, first, after)

    suspend fun resolvePlayableVod(vodId: String, oauthToken: String? = null): MasterPlaylistResult =
        vodResolver.resolveVodMasterPlaylist(vodId, oauthToken)

    suspend fun playbackPosition(vodId: String, oauthToken: String?): Long? =
        vodResolver.playbackPosition(vodId, oauthToken)

    suspend fun playbackPositions(oauthToken: String?): Result<Map<String, Long>> =
        vodResolver.playbackPositions(oauthToken)

    suspend fun updatePlaybackPosition(
        vodId: String,
        userId: String,
        positionSeconds: Long,
        oauthToken: String?,
    ): Boolean = vodResolver.updatePlaybackPosition(vodId, userId, positionSeconds, oauthToken)

    suspend fun loadStoryboard(vodId: String): com.puretv.twitch.core.stream.Storyboard? =
        vodResolver.loadStoryboard(vodId)

    suspend fun videoComments(vodId: String, offsetSeconds: Int): com.puretv.twitch.core.stream.CommentBatch =
        vodResolver.videoComments(vodId, offsetSeconds)
}
