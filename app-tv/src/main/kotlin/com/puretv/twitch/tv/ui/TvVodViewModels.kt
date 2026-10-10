package com.puretv.twitch.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.twitch.tv.data.ChatslatorTranslationClient
import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.emotes.buildEmoteIndex
import com.puretv.twitch.core.model.Badge
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.stream.ReplayBuffer
import com.puretv.twitch.core.stream.VodResolver
import com.puretv.twitch.core.stream.withThirdPartyEmotes
import com.puretv.twitch.tv.player.TvPlayer
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.data.TokenRefresher
import com.puretv.twitch.tv.data.SmbVodDownloads
import com.puretv.twitch.tv.data.SmbSpaceStats
import com.puretv.twitch.tv.data.SavedChatMessage
import com.puretv.twitch.core.model.VideoType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class TvVodListState(
    val videos: List<VideoInfo> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val loadMoreError: String? = null,
)

class TvVodListViewModel(
    private val channelLogin: String,
    private val channels: ChannelRepository,
    private val vods: VodRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TvVodListState())
    val state: StateFlow<TvVodListState> = _state.asStateFlow()
    private var channelId: String? = null
    private var nextCursor: String? = null

    init {
        viewModelScope.launch {
            runCatching {
                val channel = channels.getChannel(channelLogin) ?: error("Channel not found")
                channelId = channel.id
                vods.videosFor(channel.id)
            }.onSuccess { page ->
                nextCursor = page.cursor
                _state.value = TvVodListState(videos = page.videos, loading = false, hasMore = page.cursor != null)
            }
                .onFailure { _state.value = TvVodListState(loading = false, error = it.message ?: "Couldn't load past videos") }
        }
    }

    fun loadMore() {
        val cursor = nextCursor ?: return
        val id = channelId ?: return
        if (_state.value.loading || _state.value.loadingMore || _state.value.loadMoreError != null) return

        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, loadMoreError = null)
            runCatching { vods.videosFor(id, after = cursor) }
                .onSuccess { page ->
                    val current = _state.value
                    val added = page.videos.filterNot { video -> current.videos.any { it.id == video.id } }
                    val next = page.cursor?.takeIf { it != cursor }
                    nextCursor = next
                    _state.value = current.copy(
                        videos = current.videos + added,
                        loadingMore = false,
                        hasMore = next != null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loadingMore = false,
                        loadMoreError = error.message ?: "Couldn't load more videos",
                    )
                }
        }
    }
}

data class TvVodPlayerState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "Past video",
    val subtitleMessage: String? = null,
    val subtitleTrackPreference: String = TvPlayer.AUTO_SUBTITLE_TRACK,
    val chatMessages: List<ChatMessage> = emptyList(),
    val downloadProgress: SmbVodDownloads.Progress? = null,
    val downloadError: String? = null,
    val downloadMessage: String? = null,
    val smbSpace: SmbSpaceStats? = null,
    val smbSpaceLoading: Boolean = false,
    val smbSpaceError: String? = null,
    val downloadedVideoBytes: Long? = null,
    val downloadQualities: List<TvVodDownloadQuality> = emptyList(),
    val downloadQualitiesLoading: Boolean = false,
    val downloadQualitiesError: String? = null,
    val savedChatLoading: Boolean = false,
    val savedBundle: Boolean = false,
)

data class TvVodDownloadQuality(
    val quality: StreamQuality,
    val label: String,
    val bandwidth: Long,
)

private data class LoadedVod(
    val url: String,
    val subtitleUri: android.net.Uri?,
    val positionMs: Long,
    val subtitleTrack: String,
    val savedChat: List<SavedChatMessage>? = null,
    val savedBundle: Boolean = false,
)

class TvVodPlayerViewModel(
    private val channelLogin: String,
    private val vodId: String,
    private val title: String,
    private val thumbnailUrl: String,
    private val durationSeconds: Long,
    private val preferSavedBundle: Boolean,
    private val vodRepository: VodRepository,
    val player: TvPlayer,
    private val channels: ChannelRepository,
    private val emoteRepository: EmoteRepository,
    private val settingsStore: AppSettingsStore,
    private val chatTranslator: ChatslatorTranslationClient,
    private val tokenRefresher: TokenRefresher,
    private val smbDownloads: SmbVodDownloads,
) : ViewModel() {
    private val _state = MutableStateFlow(TvVodPlayerState(title = title))
    val state: StateFlow<TvVodPlayerState> = _state.asStateFlow()
    private val replay = ReplayBuffer()
    @Volatile private var emoteIndex = emptyMap<String, com.puretv.twitch.core.emotes.ResolvedEmote>()
    private val emoteNamesReady = CompletableDeferred<Set<String>>()
    @Volatile private var lastSecond = -1L
    @Volatile private var nextOffset = 0
    @Volatile private var hasMore = true
    @Volatile private var loadingComments = false
    @Volatile private var commentGeneration = 0
    @Volatile private var retryCommentsAtMs = 0L
    private val chatTranslationSettings = settingsStore.chatTranslation.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettingsStore.ChatTranslation(),
    )
    private val translationsByMessage = linkedMapOf<String, String>()
    private val processedTranslations = linkedSetOf<String>()
    private val translationRequests = mutableMapOf<String, Job>()
    private var lastRemotePositionSeconds = -1L
    private var lastRemotePositionAttemptMs = 0L
    private var positionSyncJob: kotlinx.coroutines.Job? = null
    private var savedChatLoadJob: Job? = null
    @Volatile private var savedChat: List<SavedChatMessage>? = null
    @Volatile private var isSavedBundle = false

    init {
        load()
        viewModelScope.launch {
            smbDownloads.downloadState.collect { download ->
                if (download.video?.id != vodId) return@collect
                val active = download.status == SmbVodDownloads.DownloadStatus.RUNNING ||
                    download.status == SmbVodDownloads.DownloadStatus.CANCELLING
                _state.value = _state.value.copy(
                    downloadProgress = download.progress.takeIf { active },
                    downloadError = download.error,
                    downloadMessage = when (download.status) {
                        SmbVodDownloads.DownloadStatus.COMPLETE -> "Saved to SMB share"
                        SmbVodDownloads.DownloadStatus.CANCELLED -> "Download cancelled"
                        else -> null
                    },
                    smbSpace = download.shareSpace,
                    smbSpaceLoading = download.shareSpaceLoading,
                    smbSpaceError = download.shareSpaceError,
                    downloadedVideoBytes = download.savedBytes,
                )
            }
        }
        viewModelScope.launch {
            if (preferSavedBundle) {
                emoteNamesReady.complete(emptySet())
            } else {
                emoteIndex = runCatching {
                    val channel = channels.getChannel(channelLogin)
                    val global = emoteRepository.loadGlobalEmotes()
                    val local = channel?.let { emoteRepository.loadChannelEmotes(it.id, it.login) }.orEmpty()
                    buildEmoteIndex(thirdPartyChannel = local, thirdPartyGlobal = global)
                }.getOrDefault(emptyMap())
                emoteNamesReady.complete(emoteIndex.keys)
            }
            publishChat(lastSecond.coerceAtLeast(0))
        }
        viewModelScope.launch {
            var lastPersistedSecond = -1L
            while (true) {
                val second = (player.exoPlayer.currentPosition.coerceAtLeast(0L) / 1_000L)
                val previousSecond = lastSecond
                if (previousSecond >= 0L && kotlin.math.abs(second - previousSecond) > SEEK_JUMP_THRESHOLD_SECONDS) {
                    commentGeneration++
                    replay.reset()
                    nextOffset = second.toInt()
                    hasMore = true
                    loadingComments = false
                    translationRequests.values.forEach { it.cancel() }
                }
                lastSecond = second
                if (second > 0L && second % 5L == 0L && second != lastPersistedSecond) {
                    lastPersistedSecond = second
                    runCatching {
                        settingsStore.saveVodPlaybackPosition(vodId, player.exoPlayer.currentPosition.coerceAtLeast(0L))
                    }
                }
                if (!preferSavedBundle && !isSavedBundle &&
                    second > 0L && second != lastRemotePositionSeconds &&
                    System.currentTimeMillis() - lastRemotePositionAttemptMs >= TWITCH_POSITION_SYNC_INTERVAL_MS
                ) {
                    scheduleTwitchPositionSync(debounce = false, force = false)
                }
                if (!preferSavedBundle && !isSavedBundle && (replay.isEmpty() || (hasMore && second >= nextOffset - 20)) &&
                    !loadingComments && System.currentTimeMillis() >= retryCommentsAtMs
                ) loadComments(nextOffset)
                publishChat(second)
                delay(1_000)
            }
        }
    }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            // Refresh an aged device-flow token before looking up Twitch's
            // account checkpoint or requesting the VOD playback token. This
            // keeps resume history and subscriber access working after a long
            // app session; fetchVodToken also has an anonymous fallback for
            // public VODs if the saved session can no longer be refreshed.
            if (!preferSavedBundle) runCatching { tokenRefresher.refreshIfPossible() }
            // If this VOD has already been saved, prefer its SMB copy even when
            // entered from the ordinary Twitch VOD list. This also keeps deleted
            // Twitch VODs playable from their existing local copy.
            val saved = if (preferSavedBundle) null else runCatching { smbDownloads.savedVod(vodId) }.getOrNull()
            if (preferSavedBundle || saved != null) {
                val savedPosition = settingsStore.vodPlaybackPosition(vodId)
                LoadedVod(
                    url = smbDownloads.mediaUri(vodId),
                    subtitleUri = runCatching { smbDownloads.subtitleUri(vodId) }.getOrNull()
                        ?: settingsStore.vodSubtitleUri(vodId)?.let(android.net.Uri::parse),
                    positionMs = clampVodPosition(savedPosition, saved?.durationSeconds ?: durationSeconds),
                    subtitleTrack = TvPlayer.AUTO_SUBTITLE_TRACK,
                    savedBundle = true,
                )
            } else {
            val settings = settingsStore.flow.first()
            val oauthToken = settings.accessToken.takeIf { it.isNotBlank() }
            val master = vodRepository.resolvePlayableVod(vodId, oauthToken)
            val preferred = settings.preferredQuality
            val quality = runCatching { StreamQuality.valueOf(preferred.uppercase()) }.getOrDefault(StreamQuality.AUTO)
            val url = VodResolver.playableUrlFor(master, quality)
            val subtitle = settingsStore.vodSubtitleUri(vodId)?.let { android.net.Uri.parse(it) }
            val subtitleTrack = settingsStore.vodSubtitleTrack(vodId) ?: TvPlayer.AUTO_SUBTITLE_TRACK
            val resumeToken = settingsStore.webResumeTokenForUser(settingsStore.currentUserId()) ?: oauthToken
            val twitchPosition = withTimeoutOrNull(TWITCH_RESUME_LOOKUP_TIMEOUT_MS) {
                vodRepository.playbackPosition(vodId, resumeToken)
            }
            val localPosition = settingsStore.vodPlaybackPosition(vodId)
            val browserPosition = settingsStore.browserVodPosition(settings.userId, vodId)
            val position = clampVodPosition(maxOf(twitchPosition ?: 0L, browserPosition, localPosition), durationSeconds)
            LoadedVod(url, subtitle, position, subtitleTrack)
            }
        }.onSuccess { loaded ->
            savedChat = loaded.savedChat
            isSavedBundle = loaded.savedBundle || loaded.savedChat != null
            player.playVod(loaded.url, loaded.subtitleUri, loaded.positionMs)
            val viewedThumbnailUrl = thumbnailUrl.ifBlank {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        vodRepository.videosByIds(listOf(vodId)).firstOrNull()?.thumbnailUrl
                    }
                }.getOrNull().orEmpty()
            }
            settingsStore.recordViewedVod(
                AppSettingsStore.ViewedVod(
                    id = vodId,
                    channelLogin = channelLogin,
                    title = title,
                    thumbnailUrl = viewedThumbnailUrl,
                    durationSeconds = durationSeconds,
                    lastViewedEpochMs = System.currentTimeMillis(),
                    positionMs = loaded.positionMs,
                ),
            )
            _state.value = _state.value.copy(
                loading = false,
                subtitleMessage = if (loaded.subtitleUri != null) "Remembered SRT subtitles loaded" else null,
                subtitleTrackPreference = loaded.subtitleTrack,
                savedBundle = loaded.savedBundle,
            )
            if (loaded.savedBundle) loadSavedChatInBackground()
        }.onFailure { e ->
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Couldn't load this video")
        }
    }

    fun loadDownloadQualities() = viewModelScope.launch {
        _state.value = _state.value.copy(downloadQualitiesLoading = true, downloadQualitiesError = null)
        runCatching {
            runCatching { tokenRefresher.refreshIfPossible() }
            val token = settingsStore.flow.first().accessToken.takeIf(String::isNotBlank)
            val master = vodRepository.resolvePlayableVod(vodId, token)
            master.variants
                .filter { it.quality != StreamQuality.AUDIO_ONLY && it.quality != StreamQuality.AUTO }
                .groupBy { it.quality }
                .mapNotNull { (quality, variants) ->
                    val variant = variants.maxByOrNull { it.bandwidth } ?: return@mapNotNull null
                    val details = buildList {
                        variant.resolution.takeIf(String::isNotBlank)?.let(::add)
                        variant.frameRate.takeIf { it > 0.0 }?.let { add("${it.toInt()} fps") }
                        variant.bandwidth.takeIf { it > 0L }?.let { add("${(it / 1_000_000.0).toInt()} Mbps") }
                    }.joinToString(" · ")
                    TvVodDownloadQuality(quality, if (details.isBlank()) quality.label else "${quality.label} · $details", variant.bandwidth)
                }
                .sortedBy { it.quality.sortOrder }
                .also { if (it.isEmpty()) error("Twitch did not provide downloadable video qualities") }
        }.onSuccess { qualities ->
            _state.value = _state.value.copy(downloadQualities = qualities, downloadQualitiesLoading = false)
        }.onFailure { error ->
            _state.value = _state.value.copy(
                downloadQualitiesLoading = false,
                downloadQualitiesError = error.message ?: "Couldn't load VOD qualities",
            )
        }
    }

    fun downloadToSmb(quality: StreamQuality) {
        val video = VideoInfo(
            id = vodId,
            userId = "",
            userLogin = channelLogin,
            userName = channelLogin,
            title = title,
            description = "",
            type = VideoType.ARCHIVE,
            durationSeconds = durationSeconds,
            createdAt = "",
            publishedAt = "",
            thumbnailUrl = thumbnailUrl,
            viewCount = 0,
            mutedSegments = emptyList(),
        )
        smbDownloads.startDownload(video, quality)
    }

    /** Switch this player to its SMB copy without leaving the player route. */
    fun playSavedBundle() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            LoadedVod(
                url = smbDownloads.mediaUri(vodId),
                subtitleUri = runCatching { smbDownloads.subtitleUri(vodId) }.getOrNull()
                    ?: settingsStore.vodSubtitleUri(vodId)?.let(android.net.Uri::parse),
                positionMs = clampVodPosition(settingsStore.vodPlaybackPosition(vodId), durationSeconds),
                subtitleTrack = TvPlayer.AUTO_SUBTITLE_TRACK,
                savedBundle = true,
            )
        }.onSuccess { loaded ->
            savedChat = null
            isSavedBundle = loaded.savedBundle || loaded.savedChat != null
            player.playVod(loaded.url, loaded.subtitleUri, loaded.positionMs)
            _state.value = _state.value.copy(
                loading = false,
                error = null,
                chatMessages = emptyList(),
                subtitleMessage = null,
                subtitleTrackPreference = loaded.subtitleTrack,
                savedBundle = true,
            )
            if (loaded.savedBundle) loadSavedChatInBackground()
        }.onFailure { error ->
            _state.value = _state.value.copy(
                loading = false,
                error = error.message ?: "Couldn't open the saved VOD",
            )
        }
    }

    /** Chat archives can be huge for long VODs. Start the video first, then load timed chat off the playback path. */
    private fun loadSavedChatInBackground() {
        savedChatLoadJob?.cancel()
        savedChatLoadJob = viewModelScope.launch {
            _state.value = _state.value.copy(savedChatLoading = true)
            // A TwitchDownloader archive for a long VOD can be large enough to
            // compete with MP4 index parsing on a TV. Let Media3 reach READY
            // before spending CPU and memory decoding the saved chat archive.
            kotlinx.coroutines.withTimeoutOrNull(2 * 60_000L) {
                while (player.exoPlayer.playbackState != androidx.media3.common.Player.STATE_READY) {
                    kotlinx.coroutines.delay(250)
                }
            }
            if (player.exoPlayer.playbackState != androidx.media3.common.Player.STATE_READY) {
                _state.value = _state.value.copy(savedChatLoading = false)
                return@launch
            }
            val messages = try {
                smbDownloads.chat(vodId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            savedChat = messages
            publishChat(player.exoPlayer.currentPosition.coerceAtLeast(0L) / 1_000L)
            _state.value = _state.value.copy(savedChatLoading = false)
            // Only use the archive fallback when the sidecar contains no saved
            // Twitch emote metadata at all. Most ordinary chat messages have an
            // empty emote list, so `any { empty }` re-read and decoded the huge
            // original archive on every playback despite emotes already being saved.
            if (messages.isNotEmpty() && messages.none { it.twitchEmotes.isNotEmpty() }) {
                val enriched = try {
                    smbDownloads.attachArchivedEmotes(vodId, messages)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    messages
                }
                if (enriched !== messages) {
                    savedChat = enriched
                    publishChat(player.exoPlayer.currentPosition.coerceAtLeast(0L) / 1_000L)
                }
            }
        }
    }

    fun cancelDownload() = smbDownloads.cancelDownload()

    fun addSubtitle(uri: android.net.Uri) {
        runCatching { player.setExternalSubtitle(uri) }
            .onSuccess {
                viewModelScope.launch { settingsStore.saveVodSubtitleUri(vodId, uri.toString()) }
                selectSubtitleTrack(TvPlayer.AUTO_SUBTITLE_TRACK)
                _state.value = _state.value.copy(subtitleMessage = "SRT subtitles loaded")
            }
            .onFailure { _state.value = _state.value.copy(subtitleMessage = "Couldn't load this subtitle file") }
    }

    fun clearSubtitle() {
        runCatching { player.setExternalSubtitle(null) }
            .onSuccess {
                viewModelScope.launch { settingsStore.saveVodSubtitleUri(vodId, null) }
                selectSubtitleTrack(TvPlayer.AUTO_SUBTITLE_TRACK)
                _state.value = _state.value.copy(subtitleMessage = "Subtitles cleared")
            }
    }

    fun selectSubtitleTrack(trackKey: String) {
        player.selectSubtitleTrack(trackKey)
        _state.value = _state.value.copy(subtitleTrackPreference = trackKey)
        viewModelScope.launch { settingsStore.saveVodSubtitleTrack(vodId, trackKey) }
    }

    fun applySubtitleTrackPreference(trackKey: String) {
        player.selectSubtitleTrack(trackKey)
    }

    fun seekBy(deltaMs: Long) {
        val player = player.exoPlayer
        if (player.duration > 0) {
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, player.duration))
            scheduleTwitchPositionSync(debounce = true, force = true)
        }
    }

    fun seekTo(positionMs: Long) {
        val duration = player.exoPlayer.duration
        if (duration > 0L) {
            player.exoPlayer.seekTo(positionMs.coerceIn(0L, duration))
            scheduleTwitchPositionSync(debounce = true, force = true)
        }
    }

    fun savePlaybackPosition() {
        viewModelScope.launch {
            val position = player.exoPlayer.currentPosition.coerceAtLeast(0L)
            settingsStore.saveVodPlaybackPosition(vodId, position)
            syncTwitchPlaybackPosition(position, force = true)
        }
    }

    private fun scheduleTwitchPositionSync(debounce: Boolean, force: Boolean) {
        if (!force && positionSyncJob?.isActive == true) return
        positionSyncJob?.cancel()
        positionSyncJob = viewModelScope.launch {
            if (debounce) delay(TWITCH_POSITION_SEEK_DEBOUNCE_MS)
            val position = player.exoPlayer.currentPosition.coerceAtLeast(0L)
            settingsStore.saveVodPlaybackPosition(vodId, position)
            syncTwitchPlaybackPosition(position, force)
        }
    }

    private suspend fun syncTwitchPlaybackPosition(positionMs: Long, force: Boolean) {
        val seconds = positionMs / 1_000L
        val now = System.currentTimeMillis()
        if (!force && (now - lastRemotePositionAttemptMs < TWITCH_POSITION_SYNC_INTERVAL_MS || seconds == lastRemotePositionSeconds)) return
        val settings = settingsStore.flow.first()
        val oauthToken = settingsStore.webResumeTokenForUser(settings.userId)
            ?: settings.accessToken.takeIf { it.isNotBlank() }
            ?: return
        val userId = settings.userId

        lastRemotePositionAttemptMs = now
        val updated = runCatching {
            vodRepository.updatePlaybackPosition(vodId, userId, seconds, oauthToken)
        }.getOrDefault(false)
        if (updated) lastRemotePositionSeconds = seconds
    }

    private fun loadComments(offset: Int) {
        if (loadingComments || !hasMore) return
        loadingComments = true
        val generation = commentGeneration
        viewModelScope.launch {
            runCatching { vodRepository.videoComments(vodId, offset) }
                .onSuccess { batch ->
                    if (generation == commentGeneration) {
                        retryCommentsAtMs = 0L
                        replay.add(batch.comments)
                        hasMore = batch.hasNextPage
                        val maxOffset = batch.comments.maxOfOrNull { it.offsetSeconds }
                        nextOffset = maxOf(offset + 1, maxOffset ?: offset)
                    }
                }
                .onFailure { if (generation == commentGeneration) retryCommentsAtMs = System.currentTimeMillis() + 5_000 }
            if (generation == commentGeneration) {
                loadingComments = false
                publishChat(lastSecond.coerceAtLeast(0))
            }
        }
    }

    private fun publishChat(second: Long) {
        val now = System.currentTimeMillis()
        savedChat?.let { saved ->
            var low = 0
            var high = saved.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (saved[middle].offsetSeconds.toLong() <= second) low = middle + 1 else high = middle
            }
            val translationSettings = chatTranslationSettings.value
            val messages = saved.subList((low - 150).coerceAtLeast(0), low)
                .map { item ->
                    val message = ChatMessage(
                        id = item.id,
                        channel = channelLogin,
                        username = item.username,
                        displayName = item.displayName,
                        color = item.color,
                        message = item.message,
                        parsedParts = restoreSavedTwitchEmotes(item.message, item.twitchEmotes),
                        badges = item.badges.map { Badge(setId = it.setId, version = it.version) },
                        timestamp = now - (second - item.offsetSeconds).coerceAtLeast(0) * 1_000L,
                        isSubscriber = item.badges.any { it.setId.equals("subscriber", ignoreCase = true) },
                        isModerator = item.badges.any { it.setId.equals("moderator", ignoreCase = true) },
                        isBroadcaster = item.badges.any { it.setId.equals("broadcaster", ignoreCase = true) },
                        translatedMessage = item.translatedMessage,
                    )
                    com.puretv.twitch.core.stream.ReplayComment(item.offsetSeconds, message)
                        .let { listOf(it).withThirdPartyEmotes(emoteIndex).first().message }
                }
            val visibleMessages = messages.map { message ->
                if (!translationSettings.enabled || message.isSystem || message.deleted || !message.translatedMessage.isNullOrBlank()) {
                    message
                } else {
                    val key = "${translationSettings.targetLanguage.lowercase()}:${message.id}"
                    translationsByMessage[key]?.let { message.copy(translatedMessage = it) } ?: message
                }
            }
            _state.value = _state.value.copy(chatMessages = visibleMessages)
            if (translationSettings.enabled) {
                messages.forEach { message ->
                    if (!message.isSystem && !message.deleted && message.translatedMessage.isNullOrBlank()) {
                        val key = "${translationSettings.targetLanguage.lowercase()}:${message.id}"
                        if (key !in translationsByMessage) {
                            requestChatTranslation(message, translationSettings.targetLanguage, key)
                        }
                    }
                }
            }
            return
        }
        val translationSettings = chatTranslationSettings.value
        val messages = replay.due(second).takeLast(150).withThirdPartyEmotes(emoteIndex).map { comment ->
            val message = comment.message.copy(timestamp = now - (second - comment.offsetSeconds).coerceAtLeast(0) * 1_000L)
            val translationKey = "${translationSettings.targetLanguage.lowercase()}:${message.id}"
            if (translationSettings.enabled && !message.isSystem && !message.deleted) {
                val translated = translationsByMessage[translationKey]
                if (translated != null) message.copy(translatedMessage = translated)
                else {
                    requestChatTranslation(message, translationSettings.targetLanguage, translationKey)
                    message
                }
            } else message
        }
        _state.value = _state.value.copy(chatMessages = messages)
    }

    private fun requestChatTranslation(message: ChatMessage, targetLanguage: String, key: String) {
        if (key in processedTranslations || key in translationRequests) return
        lateinit var job: Job
        job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val knownEmoteNames = emoteNamesReady.await()
                val result = chatTranslator.translate(message, targetLanguage, knownEmoteNames)
                processedTranslations += key
                while (processedTranslations.size > MAX_CACHED_CHAT_TRANSLATIONS * 2) {
                    processedTranslations.remove(processedTranslations.first())
                }
                if (result != null) {
                    translationsByMessage[key] = result.text
                    while (translationsByMessage.size > MAX_CACHED_CHAT_TRANSLATIONS) {
                        translationsByMessage.remove(translationsByMessage.keys.first())
                    }
                    if (chatTranslationSettings.value.enabled &&
                        chatTranslationSettings.value.targetLanguage == targetLanguage &&
                        _state.value.chatMessages.any { it.id == message.id }
                    ) {
                        _state.value = _state.value.copy(
                            chatMessages = _state.value.chatMessages.map { row ->
                                if (row.id == message.id) row.copy(translatedMessage = result.text) else row
                            },
                        )
                    }
                }
            } finally {
                if (translationRequests[key] === job) translationRequests.remove(key)
            }
        }
        translationRequests[key] = job
        job.start()
    }

    override fun onCleared() { player.stop() }

    private companion object {
        const val TWITCH_RESUME_LOOKUP_TIMEOUT_MS = 4_000L
        const val TWITCH_POSITION_SYNC_INTERVAL_MS = 15_000L
        const val TWITCH_POSITION_SEEK_DEBOUNCE_MS = 1_800L
        const val SEEK_JUMP_THRESHOLD_SECONDS = 10L
        const val MAX_CACHED_CHAT_TRANSLATIONS = 500
    }
}

/** Restore Twitch-native emote ranges retained by the downloader for offline chat. */
private fun restoreSavedTwitchEmotes(
    message: String,
    emotes: List<com.puretv.twitch.tv.data.SavedTwitchEmote>,
): List<MessagePart> {
    if (emotes.isEmpty()) return listOf(MessagePart.Text(message))
    val parts = mutableListOf<MessagePart>()
    var cursor = 0
    emotes.forEach { emote ->
        val start = message.indexOf(emote.name, cursor)
        if (start < 0) return@forEach
        val end = start + emote.name.length
        if (start > cursor) parts += MessagePart.Text(message.substring(cursor, start))
        parts += MessagePart.TwitchEmote(emote.id, emote.name)
        cursor = end
    }
    if (cursor < message.length) parts += MessagePart.Text(message.substring(cursor))
    return parts.ifEmpty { listOf(MessagePart.Text(message)) }
}
