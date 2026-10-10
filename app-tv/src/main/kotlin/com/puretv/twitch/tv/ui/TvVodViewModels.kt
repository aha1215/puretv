package com.puretv.twitch.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.twitch.tv.data.ChatslatorTranslationClient
import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.emotes.buildEmoteIndex
import com.puretv.twitch.core.model.ChatMessage
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
)

private data class LoadedVod(
    val url: String,
    val subtitleUri: android.net.Uri?,
    val positionMs: Long,
    val subtitleTrack: String,
)

class TvVodPlayerViewModel(
    private val channelLogin: String,
    private val vodId: String,
    private val title: String,
    private val thumbnailUrl: String,
    private val durationSeconds: Long,
    private val vodRepository: VodRepository,
    val player: TvPlayer,
    private val channels: ChannelRepository,
    private val emoteRepository: EmoteRepository,
    private val settingsStore: AppSettingsStore,
    private val chatTranslator: ChatslatorTranslationClient,
    private val tokenRefresher: TokenRefresher,
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

    init {
        load()
        viewModelScope.launch {
            emoteIndex = runCatching {
                val channel = channels.getChannel(channelLogin)
                val global = emoteRepository.loadGlobalEmotes()
                val local = channel?.let { emoteRepository.loadChannelEmotes(it.id, it.login) }.orEmpty()
                buildEmoteIndex(thirdPartyChannel = local, thirdPartyGlobal = global)
            }.getOrDefault(emptyMap())
            emoteNamesReady.complete(emoteIndex.keys)
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
                if (
                    second > 0L && second != lastRemotePositionSeconds &&
                    System.currentTimeMillis() - lastRemotePositionAttemptMs >= TWITCH_POSITION_SYNC_INTERVAL_MS
                ) {
                    scheduleTwitchPositionSync(debounce = false, force = false)
                }
                if ((replay.isEmpty() || (hasMore && second >= nextOffset - 20)) &&
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
            runCatching { tokenRefresher.refreshIfPossible() }
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
            val position = clampVodPosition(maxOf(twitchPosition ?: 0L, localPosition), durationSeconds)
            LoadedVod(url, subtitle, position, subtitleTrack)
        }.onSuccess { loaded ->
            player.playVod(loaded.url, loaded.subtitleUri, loaded.positionMs)
            settingsStore.recordViewedVod(
                AppSettingsStore.ViewedVod(
                    id = vodId,
                    channelLogin = channelLogin,
                    title = title,
                    thumbnailUrl = thumbnailUrl,
                    durationSeconds = durationSeconds,
                    lastViewedEpochMs = System.currentTimeMillis(),
                    positionMs = loaded.positionMs,
                ),
            )
            _state.value = _state.value.copy(
                loading = false,
                subtitleMessage = if (loaded.subtitleUri != null) "Remembered SRT subtitles loaded" else null,
                subtitleTrackPreference = loaded.subtitleTrack,
            )
        }.onFailure { e ->
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Couldn't load this video")
        }
    }

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
