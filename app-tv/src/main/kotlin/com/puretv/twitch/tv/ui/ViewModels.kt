package com.puretv.twitch.tv.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.twitch.core.adblock.AdBlockEngine
import com.puretv.twitch.core.adblock.AdBlockStatus
import com.puretv.twitch.core.api.DeviceAuth
import com.puretv.twitch.core.api.DevicePollResult
import com.puretv.twitch.core.api.HelixApiException
import com.puretv.twitch.core.chat.TwitchChatClient
import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.follows.FollowRow
import com.puretv.twitch.core.follows.FollowedChannelsSource
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.ChatEvent
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.model.GameInfo
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.core.model.VideoType
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.StreamRepository
import com.puretv.twitch.core.repository.UserRepository
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.stream.TwitchResumeHistoryUnavailableException
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.data.ChatslatorTranslationClient
import com.puretv.twitch.tv.data.TokenRefresher
import com.puretv.twitch.tv.data.db.CachedStreamDao
import com.puretv.twitch.tv.data.db.toCachedStream
import com.puretv.twitch.tv.data.db.toStreamInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

/**
 * SECTION 11 / 07 — ViewModels backing the TV screens. Functionally
 * identical to the phone app's `ViewModels.kt` (same `core` repositories,
 * same UI-state shape) — duplicated rather than shared because Android
 * `ViewModel`/`viewModelScope` and this app's `AppSettingsStore` are
 * platform/app-specific (Section 12.2).
 */

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val followedLive: List<StreamInfo> = emptyList(),
    val topStreams: List<StreamInfo> = emptyList(),
    val latestFollowedVods: List<VideoInfo> = emptyList(),
    val latestFollowedVodsLoading: Boolean = false,
    val viewedVods: List<AppSettingsStore.ViewedVod> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

internal fun mergeViewedVods(
    local: List<AppSettingsStore.ViewedVod>,
    remote: List<AppSettingsStore.ViewedVod>,
): List<AppSettingsStore.ViewedVod> {
    val localById = local.associateBy { it.id }
    val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
    val mergedRemote = remote.map { remoteVod ->
        val localVod = localById[remoteVod.id]
        val durationSeconds = remoteVod.durationSeconds.takeIf { it > 0L }
            ?: localVod?.durationSeconds?.coerceAtLeast(0L)
            ?: 0L
        (localVod ?: remoteVod).copy(
            durationSeconds = durationSeconds,
            positionMs = clampVodPosition(maxOf(localVod?.positionMs ?: 0L, remoteVod.positionMs), durationSeconds),
        )
    }
    return mergedRemote + local.filterNot { it.id in remoteIds }.map { vod ->
        vod.copy(positionMs = clampVodPosition(vod.positionMs, vod.durationSeconds))
    }
}

internal fun clampVodPosition(positionMs: Long, durationSeconds: Long): Long {
    val safePosition = positionMs.coerceAtLeast(0L)
    if (durationSeconds <= 0L) return safePosition
    val durationMs = if (durationSeconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else durationSeconds * 1_000L
    return minOf(safePosition, durationMs)
}

/**
 * Maps a browse/load failure to a message a viewer can act on. Twitch Helix now
 * requires an OAuth token even for public reads (/streams, /games/top,
 * /search/channels), so a signed-out viewer hits HTTP 401 rather than an empty
 * list. "Sign in" is the actionable thing to say; leaking "HTTP 401" or blaming
 * the connection (when the network is fine) sends the viewer down the wrong path.
 */
internal fun friendlyLoadError(e: Throwable, fallback: String): String = when {
    e is HelixApiException && e.statusCode == 401 ->
        "Twitch now requires sign-in to browse. Choose Sign in from the nav rail."
    e is HelixApiException && e.statusCode == 429 ->
        "Twitch is rate-limiting this app. Try again in a moment."
    else -> fallback
}

/**
 * A user-scoped read that 401s while the app still holds a session means the
 * stored token is no longer accepted (expired or revoked). This is NOT a
 * connectivity problem, so it must not be presented as "check your connection"
 * — the only recovery is to sign in again.
 *
 * Why the TV cannot just refresh it away: refresh needs the app's Twitch
 * `client_secret`, and a fork that does not ship one gets `403 invalid client
 * secret` (verified against id.twitch.tv; omitting it is `400 missing client
 * secret`). [com.puretv.twitch.tv.data.TokenRefresher] still tries, so builds
 * WITH a secret recover transparently, but the fork must fall back to asking
 * the viewer to sign in rather than showing a dead list.
 */
internal fun isAuthFailure(e: Throwable): Boolean =
    e is HelixApiException && (e.statusCode == 401 || e.statusCode == 403)

/** Actionable copy for [isAuthFailure]; shown instead of a generic load error. */
internal const val TV_SESSION_EXPIRED_MESSAGE =
    "Your Twitch session expired. Sign in again to reload."

/**
 * Landing-screen state. Rebuilt (from the one-shot loader the TV app shipped
 * with) to match the phone app's resilience:
 *   • Cached-first paint — last session's streams show instantly from Room,
 *     so the grid is never empty on a cold, offline, or token-expired start.
 *   • Write-through — every fresh network result is persisted (24h TTL).
 *   • [refresh] is re-runnable and IS wired (resume + periodic, see
 *     `TvHomeScreen`), so "Live Now" repopulates instead of freezing on the
 *     first snapshot. On a failed refresh it refreshes the token once and
 *     retries, recovering the expired-token case that used to 401 silently.
 */
class HomeViewModel(
    private val streamRepository: StreamRepository,
    private val userRepository: UserRepository,
    private val settings: AppSettingsStore,
    private val vodRepository: VodRepository,
    private val cachedStreamDao: CachedStreamDao,
    private val tokenRefresher: TokenRefresher,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Once the network returns a fresh top-streams list, stop letting the cached
    // snapshot overwrite it (the cache is only for the instant first paint).
    @Volatile private var freshTopStreamsArrived = false

    // Cancel-and-replace so a resume + timer refresh landing together can't run
    // two overlapping loads that flicker isLoading against each other.
    private var refreshJob: Job? = null
    private var viewedVodPositionRefreshJob: Job? = null
    private var followedVodsRefreshJob: Job? = null
    private var lastViewedVodPositionRefreshAtMs = 0L
    private var lastFollowedVodsRefreshAtMs = 0L
    private var followedVodChannelIds: List<String> = emptyList()
    private var remoteHistoryUnavailableToken: String? = null
    private var localViewedVods: List<AppSettingsStore.ViewedVod> = emptyList()
    private var importedViewedVods: List<AppSettingsStore.ViewedVod> = emptyList()
    private var importedViewedVodsUserId: String? = null
    private var browserVodHistories: List<AppSettingsStore.BrowserVodHistory> = emptyList()
    private var importedBrowserVods: List<AppSettingsStore.ViewedVod> = emptyList()
    private var activeTwitchUserId: String? = null
    private var accountSessionGeneration = 0

    init {
        viewModelScope.launch {
            settings.viewedVods.collect { vods ->
                localViewedVods = vods
                publishViewedVods()
            }
        }
        // Cached-first: paint last session's streams instantly, before any network.
        viewModelScope.launch {
            cachedStreamDao.observeAll().collect { cached ->
                if (!freshTopStreamsArrived && cached.isNotEmpty()) {
                    _state.update { it.copy(topStreams = cached.map { c -> c.toStreamInfo() }, isLoading = false) }
                }
            }
        }
        // Fresh top streams from the network + write-through to the cache.
        viewModelScope.launch {
            streamRepository.topStreams.collect { top ->
                if (top.isNotEmpty()) {
                    freshTopStreamsArrived = true
                    _state.update { it.copy(topStreams = top, error = null) }
                    runCatching {
                        val now = System.currentTimeMillis()
                        cachedStreamDao.upsertAll(top.map { it.toCachedStream(now) })
                        // Bound the cache so the instant-paint snapshot can't accumulate
                        // thousands of stale rows.
                        cachedStreamDao.pruneStaleEntries(now - CACHE_TTL_MS)
                    }
                }
            }
        }
        // Login flag, recomputed whenever the session changes.
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _state.update { it.copy(isLoggedIn = prefs.accessToken.isNotBlank()) }
                val nextUserId = prefs.userId.takeIf { it.isNotBlank() }
                if (nextUserId != activeTwitchUserId) {
                    activeTwitchUserId = nextUserId
                    accountSessionGeneration++
                    viewedVodPositionRefreshJob?.cancel()
                    importedViewedVods = emptyList()
                    importedViewedVodsUserId = null
                    remoteHistoryUnavailableToken = null
                    refreshBrowserHistoryForActiveAccount()
                    publishViewedVods()
                    if (nextUserId != null) refreshViewedVodPositions(force = true)
                }
                if (prefs.accessToken.isBlank()) {
                    userRepository.clearFollows()
                    followedVodsRefreshJob?.cancel()
                    lastFollowedVodsRefreshAtMs = 0L
                    _state.update { it.copy(latestFollowedVods = emptyList(), latestFollowedVodsLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            settings.browserVodHistories.collect { histories ->
                browserVodHistories = histories
                refreshBrowserHistoryForActiveAccount()
                publishViewedVods()
            }
        }
        viewModelScope.launch {
            settings.webResumeSessionRevision.collect {
                accountSessionGeneration++
                viewedVodPositionRefreshJob?.cancel()
                remoteHistoryUnavailableToken = null
                importedViewedVods = emptyList()
                importedViewedVodsUserId = null
                publishViewedVods()
                if (settings.webResumeSessionLinked.value) refreshViewedVodPositions(force = true)
            }
        }
        // "Following — Live now": query live status directly for the followed
        // channels, recomputed whenever the follow set changes. Previously this
        // filtered the global top-streams set for followed logins, so a followed
        // channel that wasn't one of the biggest streams on all of Twitch never
        // showed up here even while live, matching the "followed channels aren't
        // showing" report. streamsForChannels handles Helix's 100-login/20-result
        // caps internally.
        viewModelScope.launch {
            userRepository.followedLogins.collect { follows ->
                val live = if (follows.isEmpty()) emptyList()
                    else runCatching { streamRepository.streamsForChannels(follows) }.getOrDefault(emptyList())
                _state.update { it.copy(followedLive = live) }
            }
        }
        viewModelScope.launch {
            userRepository.followedChannels.collect { follows ->
                val channelIds = follows.map { it.broadcaster_id }
                if (channelIds != followedVodChannelIds) {
                    followedVodChannelIds = channelIds
                    refreshLatestFollowedVods(force = true)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        refreshViewedVodPositions()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.topStreams.isEmpty(), error = null) }
            val prefs = settings.flow.first()
            if (prefs.userId.isNotBlank()) runCatching { userRepository.loadFollows(prefs.userId) }
            if (userRepository.followedChannels.value.map { it.broadcaster_id } == followedVodChannelIds) {
                refreshLatestFollowedVods()
            }
            val failure = loadTopStreamsResilient()
            _state.update {
                it.copy(
                    isLoading = false,
                    error = if (failure != null && it.topStreams.isEmpty()) {
                        friendlyLoadError(failure, "Couldn't load. Check your connection and try again.")
                    } else null,
                )
            }
        }
    }

    /** Load each followed channel's newest archive, with bounded API concurrency. */
    private fun refreshLatestFollowedVods(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFollowedVodsRefreshAtMs < FOLLOWED_VODS_REFRESH_INTERVAL_MS) return
        lastFollowedVodsRefreshAtMs = now
        val follows = userRepository.followedChannels.value
        followedVodsRefreshJob?.cancel()
        if (follows.isEmpty()) {
            _state.update { it.copy(latestFollowedVods = emptyList(), latestFollowedVodsLoading = false) }
            return
        }

        _state.update { it.copy(latestFollowedVods = emptyList(), latestFollowedVodsLoading = true) }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val requestLimit = Semaphore(FOLLOWED_VOD_REQUEST_CONCURRENCY)
            try {
                // Twitch may expose an archive while its broadcast is still live.
                // Capture the current start time so the shelf can skip that growing,
                // unfinished recording and use the latest completed archive instead.
                val liveByUserId = runCatching {
                    streamRepository.streamsForChannels(follows.map { it.broadcaster_login })
                        .associateBy { it.userId }
                }.getOrDefault(emptyMap())
                val videos = coroutineScope {
                    follows.map { channel ->
                        async {
                            requestLimit.withPermit {
                                try {
                                    val liveStartedAt = liveByUserId[channel.broadcaster_id]
                                        ?.startedAt
                                        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                                    vodRepository.videosFor(
                                        userId = channel.broadcaster_id,
                                        type = VideoType.ARCHIVE,
                                        // One extra result lets us fall back to the last
                                        // completed broadcast when Twitch returns the
                                        // current live archive at the top of the list.
                                        first = if (liveStartedAt != null) 2 else 1,
                                    ).videos.firstOrNull { video ->
                                        val createdAt = runCatching { Instant.parse(video.createdAt) }.getOrNull()
                                        liveStartedAt == null || createdAt?.isBefore(liveStartedAt) == true
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }.awaitAll()
                }
                _state.update { current ->
                    current.copy(
                        latestFollowedVods = videos.filterNotNull()
                            .distinctBy(VideoInfo::id)
                            .sortedByDescending(VideoInfo::createdAt)
                            .take(MAX_LATEST_FOLLOWED_VODS),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (followedVodsRefreshJob === job) {
                    _state.update { it.copy(latestFollowedVodsLoading = false) }
                }
            }
        }
        followedVodsRefreshJob = job
        job.start()
    }

    /** Reconcile recent history against Twitch once per refresh window; keep the farther checkpoint. */
    private fun refreshViewedVodPositions(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastViewedVodPositionRefreshAtMs < VIEWED_VOD_POSITION_REFRESH_INTERVAL_MS) return
        lastViewedVodPositionRefreshAtMs = now
        viewedVodPositionRefreshJob?.cancel()
        viewedVodPositionRefreshJob = viewModelScope.launch {
            val generation = accountSessionGeneration
            try {
                val appSettings = settings.flow.first()
                val accessToken = settings.webResumeTokenForUser(appSettings.userId)
                    ?: appSettings.accessToken.takeIf(String::isNotBlank)
                if (accessToken == null) {
                    remoteHistoryUnavailableToken = null
                    Log.i(TAG, "Skipping remote VOD history refresh: no Twitch session")
                    return@launch
                }
                if (remoteHistoryUnavailableToken == accessToken) return@launch
                remoteHistoryUnavailableToken = null

                val history = vodRepository.playbackHistory(accessToken).getOrElse { error ->
                    if (error is TwitchResumeHistoryUnavailableException) {
                        remoteHistoryUnavailableToken = accessToken
                        Log.i(
                            TAG,
                            "Twitch private resume history is unavailable; retaining local VOD checkpoints.",
                        )
                    } else {
                        Log.w(TAG, "Twitch VOD history lookup failed: ${error.message}", error)
                    }
                    return@launch
                }
                if (generation != accountSessionGeneration) return@launch

                val historyUserId = history.userId?.takeIf(String::isNotBlank)
                    ?: settings.flow.first().userId.takeIf(String::isNotBlank)
                if (historyUserId == null) {
                    Log.i(TAG, "Twitch VOD history returned no viewer ID; skipping account-scoped import")
                    return@launch
                }
                if (importedViewedVodsUserId != historyUserId) {
                    importedViewedVods = emptyList()
                    importedViewedVodsUserId = historyUserId
                    publishViewedVods()
                }

                val metadata = vodRepository.videosByIds(history.entries.map { it.vodId })
                    .associateBy { it.id }
                if (generation != accountSessionGeneration) return@launch
                val imported = history.entries.mapNotNull { entry ->
                    val video = metadata[entry.vodId] ?: return@mapNotNull null
                    AppSettingsStore.ViewedVod(
                        id = video.id,
                        channelLogin = video.userLogin,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl,
                        durationSeconds = video.durationSeconds,
                        lastViewedEpochMs = 0L,
                        positionMs = clampVodPosition(entry.positionMs, video.durationSeconds),
                    )
                }
                importedViewedVods = imported
                importedViewedVodsUserId = historyUserId
                publishViewedVods()

                val recentVods = settings.viewedVods.first()
                val matched = recentVods.count { vod -> history.entries.any { it.vodId == vod.id } }
                Log.i(TAG, "Twitch VOD history refresh: ${history.entries.size} remote entries, " +
                    "${imported.size} imported, $matched/${recentVods.size} local matches")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Twitch VOD history refresh failed", error)
            }
        }
    }

    private fun publishViewedVods() {
        // The explicit browser import reflects the position/order seen on Twitch's
        // web player, so keep its order first when both remote sources are present.
        val remote = mergeViewedVods(importedBrowserVods, importedViewedVods)
        _state.update { it.copy(viewedVods = mergeViewedVods(localViewedVods, remote)) }
    }

    private fun refreshBrowserHistoryForActiveAccount() {
        val activeUserId = activeTwitchUserId
        val history = browserVodHistories.firstOrNull { it.viewerId == activeUserId }
        importedBrowserVods = history?.vods.orEmpty()
    }

    /**
     * Refresh top streams; if it fails (a Helix 401 on an expired user token is
     * the common cause) refresh the token once and retry. On success the
     * write-through collector above updates the UI + cache.
     */
    private suspend fun loadTopStreamsResilient(): Throwable? {
        val first = runCatching { streamRepository.refreshTopStreams() }
        if (first.isSuccess) return null
        runCatching { tokenRefresher.refreshIfPossible() }
        val second = runCatching { streamRepository.refreshTopStreams() }
        return second.exceptionOrNull() ?: first.exceptionOrNull()
    }

    private companion object {
        const val TAG = "TvHomeViewModel"
        // Streams older than this are dropped from the instant-paint cache.
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        const val VIEWED_VOD_POSITION_REFRESH_INTERVAL_MS = 60_000L
        const val FOLLOWED_VODS_REFRESH_INTERVAL_MS = 15L * 60 * 1000
        const val FOLLOWED_VOD_REQUEST_CONCURRENCY = 8
        const val MAX_LATEST_FOLLOWED_VODS = 24
    }
}

data class TvFollowingUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val live: List<FollowRow> = emptyList(),
    val offline: List<FollowRow> = emptyList(),
    val errored: Boolean = false,
    /**
     * The stored session can no longer authenticate (a 401/403 while the app
     * still considered itself signed in). Kept SEPARATE from [errored] because
     * the remedy differs: a network error wants "Try again", a dead session
     * wants "Sign in again" (see [isAuthFailure]).
     */
    val sessionExpired: Boolean = false,
)

/**
 * Drives the Following destination (its own nav-rail entry, not just Home's
 * shelf). TV ships no local-pin store the way desktop does, so every row
 * comes straight from the Twitch follow graph and [FollowedChannelsSource.load]
 * always sees an empty pin list.
 *
 * Mirrors the desktop app's `FollowedRailViewModel`: a single in-flight load,
 * cancelled and replaced on [refresh] so a screen re-entry and a manual retry
 * can't race; a fast partial paint the moment live status resolves (avatars
 * may still be loading); and a logged-out reset that clears the profile cache
 * so a different account signing in within the same process never reuses it.
 * The signed-in user id is read the same way [HomeViewModel.refresh] reads
 * it, from `settings.flow.first()`, rather than a synchronous accessor.
 */
class TvFollowingViewModel(
    private val source: FollowedChannelsSource,
    private val settings: AppSettingsStore,
    private val tokenRefresher: TokenRefresher,
) : ViewModel() {
    private val _state = MutableStateFlow(TvFollowingUiState())
    val state: StateFlow<TvFollowingUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _state.update { it.copy(isLoggedIn = prefs.accessToken.isNotBlank()) }
            }
        }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadOnce() }
    }

    private suspend fun loadOnce() {
        val userId = settings.flow.first().userId
        if (userId.isBlank()) {
            // Signed out (manually or via token-expiry): drop cached profiles so a
            // different account signing in next within this process never reuses them.
            source.clear()
            _state.update {
                it.copy(isLoggedIn = false, isLoading = false, live = emptyList(), offline = emptyList(), sessionExpired = false)
            }
            return
        }
        _state.update {
            it.copy(isLoggedIn = true, isLoading = it.live.isEmpty() && it.offline.isEmpty(), sessionExpired = false)
        }
        var result = runCatching { loadRails(userId) }
        if (result.isFailure && isAuthFailure(result.exceptionOrNull()!!)) {
            // The stored access token is dead. Try one refresh first (recovers on
            // builds that ship a client secret), then retry once before deciding.
            runCatching { tokenRefresher.refreshIfPossible(force = true) }
            result = runCatching { loadRails(userId) }
        }
        result
            .onSuccess { full ->
                _state.update { it.copy(isLoading = false, errored = false, sessionExpired = false, live = full.live, offline = full.offline) }
            }
            .onFailure { e ->
                // A dead session is not a network fault: surface a sign-in action
                // rather than "check your connection", which is wrong (they ARE
                // signed in) and gives the viewer nothing to do.
                if (isAuthFailure(e)) {
                    _state.update { it.copy(isLoading = false, errored = false, sessionExpired = true) }
                } else {
                    _state.update { it.copy(isLoading = false, errored = true) }
                }
            }
    }

    private suspend fun loadRails(userId: String) =
        source.load(userId, emptyList()) { partial ->
            // Fast partial: live channels are in (avatars may still be loading). Drop
            // the loading state now so the list appears without waiting on avatars.
            _state.update { it.copy(isLoading = false, errored = false, live = partial.live, offline = partial.offline) }
        }
}

data class BrowseUiState(
    val games: List<GameInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Category grid. Previously a one-shot load that swallowed any failure into an
 * empty list — so a transient network/token failure blanked the grid until a
 * full relaunch (the reported "categories just disappear" bug). Now it keeps the
 * last-known games on failure, surfaces an error + retry only when it has nothing
 * to show, and refreshes the token once before giving up.
 */
class BrowseViewModel(
    private val channelRepository: ChannelRepository,
    private val tokenRefresher: TokenRefresher,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init { refresh() }

    fun retry() = refresh()

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.games.isEmpty(), error = null) }
            loadGamesResilient()
                .onSuccess { games ->
                    // ifEmpty: a transient empty response must not wipe a good grid.
                    _state.update { it.copy(games = games.ifEmpty { it.games }, isLoading = false, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = if (it.games.isEmpty()) friendlyLoadError(e, "Couldn't load categories.") else null,
                        )
                    }
                }
        }
    }

    private suspend fun loadGamesResilient(): Result<List<GameInfo>> {
        val first = runCatching { channelRepository.topGames() }
        if (first.isSuccess) return first
        runCatching { tokenRefresher.refreshIfPossible() }
        return runCatching { channelRepository.topGames() }
    }
}

data class CategoryUiState(
    val gameName: String = "",
    val streams: List<StreamInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** Streams currently live in a single category (game), reached by tapping a game tile. */
class CategoryViewModel(
    private val gameId: String,
    private val streamRepository: StreamRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CategoryUiState())
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    init { load() }

    fun retry() = load()

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        runCatching { streamRepository.streamsForGame(gameId) }
            .onSuccess { streams ->
                _state.update {
                    it.copy(streams = streams, gameName = streams.firstOrNull()?.gameName ?: it.gameName, isLoading = false)
                }
            }
            .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load this category.") } }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<com.puretv.twitch.core.api.ChannelSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    /**
     * Why the last search returned nothing, or null when it genuinely matched
     * nothing. Empty results with no explanation was the "dead no results"
     * screen: a dead session and a query with no hits looked identical.
     */
    val error: String? = null,
)

class SearchViewModel(private val channelRepository: ChannelRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        if (query.length < 2) {
            _state.update { it.copy(results = emptyList(), isSearching = false, error = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, error = null) }
            runCatching { channelRepository.search(query) }
                .onSuccess { results ->
                    _state.update { it.copy(results = results, isSearching = false, error = null) }
                }
                .onFailure { e ->
                    val message = if (isAuthFailure(e)) {
                        TV_SESSION_EXPIRED_MESSAGE
                    } else {
                        "Search failed. Check your connection and try again."
                    }
                    _state.update { it.copy(results = emptyList(), isSearching = false, error = message) }
                }
        }
    }
}

data class StreamUiState(
    val channel: com.puretv.twitch.core.model.ChannelInfo? = null,
    val streamInfo: StreamInfo? = null,
    val playableUrl: String? = null,
    val adBlockStatus: AdBlockStatus = AdBlockStatus.UNKNOWN,
    val chatMessages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * Why playback could not start, or null while it is fine. Stream resolution
     * is the ONLY path that produces [playableUrl], so when it fails the screen
     * has nothing to render: without this the viewer gets a black rectangle with
     * no audio and no explanation, and chat keeps scrolling beside it (it is a
     * separate coroutine), which reads as "the app works, the stream is gone".
     * Mirrors the phone app's `StreamUiState.playbackError`.
     */
    val playbackError: String? = null,
)

class StreamViewModel(
    private val channelLogin: String,
    private val streamRepository: StreamRepository,
    private val channelRepository: ChannelRepository,
    private val chatClient: TwitchChatClient,
    private val emoteRepository: EmoteRepository,
    private val adBlockEngine: AdBlockEngine,
    private val settingsStore: AppSettingsStore,
    private val chatTranslator: ChatslatorTranslationClient,
) : ViewModel() {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()
    private val emoteNamesReady = CompletableDeferred<Set<String>>()

    init {
        viewModelScope.launch {
            val channel = runCatching { channelRepository.getChannel(channelLogin) }.getOrNull()
            val liveInfo = runCatching {
                streamRepository.streamsForChannels(listOf(channelLogin)).firstOrNull()
            }.getOrNull()
            _state.update { it.copy(channel = channel, streamInfo = liveInfo, isLoading = false) }

            viewModelScope.launch {
                val globalEmotes = runCatching { emoteRepository.loadGlobalEmotes() }.getOrDefault(emptyList())
                val channelEmotes = channel?.let {
                    runCatching { emoteRepository.loadChannelEmotes(it.id, it.login) }.getOrDefault(emptyList())
                }.orEmpty()
                emoteNamesReady.complete((globalEmotes + channelEmotes).mapTo(mutableSetOf()) { it.name })
            }
            resolvePlayable()
        }
        viewModelScope.launch {
            adBlockEngine.status.collect { status -> _state.update { it.copy(adBlockStatus = status) } }
        }
        viewModelScope.launch {
            // Section 7.4 — TV viewers are overwhelmingly unauthenticated/lean-back;
            // fall back to the anonymous `justinfanNNNN` IRC identity exactly like
            // the phone app when no OAuth session is present (Section 5.1).
            val settings = settingsStore.flow.first()
            val token = settings.accessToken.takeIf { it.isNotBlank() }
            val username = settings.username.takeIf { it.isNotBlank() }
            chatClient.connect(channelLogin, token, username)
            chatClient.events.collect { event ->
                if (event is ChatEvent.Message) {
                    _state.update { current ->
                        current.copy(chatMessages = (current.chatMessages + event.message).takeLast(200))
                    }
                    translateChatMessage(event.message)
                }
            }
        }
    }

    private fun translateChatMessage(message: ChatMessage) {
        if (message.isSystem || message.deleted) return
        viewModelScope.launch {
            val translationSettings = settingsStore.chatTranslation.first()
            if (!translationSettings.enabled) return@launch
            val knownEmoteNames = emoteNamesReady.await()
            val translation = chatTranslator.translate(message, translationSettings.targetLanguage, knownEmoteNames) ?: return@launch
            _state.update { current ->
                current.copy(
                    chatMessages = current.chatMessages.map { row ->
                        if (row.id == message.id) row.copy(translatedMessage = translation.text) else row
                    },
                )
            }
        }
    }

    /**
     * Resolve the signed master-playlist URL the player consumes.
     *
     * The failure branch is the point of this function. `runCatching { }` with
     * only an `onSuccess` swallows every cause — a GQL schema change, a blocked
     * usher host, an offline channel, a dead network — and leaves [playableUrl]
     * null, which the screen renders as an indistinguishable black frame. Every
     * one of those needs a different fix, so the message is surfaced verbatim
     * and logged under [TAG] for `adb logcat`.
     */
    private suspend fun resolvePlayable() {
        _state.update { it.copy(playbackError = null) }
        runCatching { streamRepository.resolvePlayableStream(channelLogin) }
            .onSuccess { playable ->
                _state.update { it.copy(playableUrl = playable.masterUrl, playbackError = null) }
            }
            .onFailure { e ->
                Log.w(TAG, "Stream resolution failed for $channelLogin", e)
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                _state.update {
                    it.copy(
                        playbackError = "Could not load this stream. It may be offline." +
                            if (detail.isBlank()) "" else "\n\n$detail",
                    )
                }
            }
    }

    /** Re-run resolution after a failure (OK on the remote). */
    fun retry() = viewModelScope.launch { resolvePlayable() }

    fun sendChatMessage(text: String) = viewModelScope.launch {
        chatClient.sendMessage(channelLogin, text)
    }

    override fun onCleared() {
        super.onCleared()
        chatClient.disconnect()
    }

    private companion object {
        const val TAG = "StreamViewModel"
    }
}

data class ChannelUiState(
    val channel: com.puretv.twitch.core.model.ChannelInfo? = null,
    val isLive: Boolean = false,
    val viewerCount: Long = 0L,
)

class ChannelViewModel(
    private val channelLogin: String,
    private val channelRepository: ChannelRepository,
    private val streamRepository: StreamRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val channel = runCatching { channelRepository.getChannel(channelLogin) }.getOrNull()
            val stream = runCatching {
                streamRepository.streamsForChannels(listOf(channelLogin)).firstOrNull()
            }.getOrNull()
            _state.update {
                it.copy(channel = channel, isLive = stream != null, viewerCount = stream?.viewerCount?.toLong() ?: 0L)
            }
        }
    }
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoggedIn: Boolean = false,
    val loginUsername: String? = null,
)

class SettingsViewModel(private val settingsStore: AppSettingsStore) : ViewModel() {
    val state: StateFlow<SettingsUiState> = settingsStore.flow.let { flow ->
        val mutable = MutableStateFlow(SettingsUiState())
        viewModelScope.launch {
            flow.collect { prefs ->
                mutable.update {
                    it.copy(
                        settings = prefs,
                        isLoggedIn = prefs.accessToken.isNotBlank(),
                        loginUsername = prefs.username.takeIf { name -> name.isNotBlank() },
                    )
                }
            }
        }
        mutable.asStateFlow()
    }

    fun setPreferredQuality(quality: com.puretv.twitch.core.model.StreamQuality) = viewModelScope.launch {
        settingsStore.update { it.copy(preferredQuality = quality.name.lowercase()) }
    }

    fun setAdBlockEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsStore.update { it.copy(adBlockEnabled = enabled) }
    }

    fun setAdBlockStrategy(strategy: com.puretv.twitch.core.adblock.AdBlockStrategy) = viewModelScope.launch {
        settingsStore.update { it.copy(adBlockStrategy = strategy.name.lowercase()) }
    }

    fun setProxyUrl(url: String) = viewModelScope.launch {
        settingsStore.update { it.copy(customProxyUrl = url) }
    }

    fun logOut() = settingsStore.clearSession()
}

data class LoginUiState(
    val isAuthenticating: Boolean = false,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
)

/**
 * SECTION 03.2 / 07: Twitch Device Code Grant flow, the correct 10-foot login.
 *
 * Twitch does not accept custom-scheme redirect URIs, so the authorization-code
 * + `puretv-twitch://auth` redirect approach cannot work on a TV (there is no
 * browser round-trip back into the app, and no keyboard to type credentials).
 * The device flow needs no redirect: [TvLoginScreen] shows [userCode] and
 * points the viewer at [verificationUri] (twitch.tv/activate) to enter it on a
 * phone/computer, while this VM polls until Twitch reports the code authorized,
 * then persists the session. Same flow the phone and desktop apps use.
 */
class LoginViewModel(
    private val httpClient: HttpClient,
    private val settingsStore: AppSettingsStore,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var loginJob: Job? = null

    fun beginLogin() {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            _state.value = LoginUiState(isAuthenticating = true)

            val device = runCatching { DeviceAuth.requestDeviceCode(httpClient) }.getOrElse { e ->
                _state.update { it.copy(isAuthenticating = false, error = e.message ?: "Could not start sign-in.") }
                return@launch
            }
            _state.update { it.copy(userCode = device.userCode, verificationUri = device.verificationUri) }

            var intervalMs = device.intervalSeconds.coerceAtLeast(1) * 1_000
            val expiresMs = device.expiresInSeconds.coerceAtLeast(1) * 1_000
            var elapsedMs = 0L
            while (elapsedMs < expiresMs) {
                delay(intervalMs)
                elapsedMs += intervalMs
                when (val result = runCatching { DeviceAuth.pollOnce(httpClient, device.deviceCode) }.getOrNull()) {
                    is DevicePollResult.Success -> {
                        val token = result.token
                        // Persist token-first so the API client is authenticated, then
                        // look up the account identity and persist it so chat sends
                        // under the right name and Settings shows the username.
                        settingsStore.setSession(accessToken = token.accessToken, refreshToken = token.refreshToken)
                        val me = runCatching { userRepository.getCurrentUser() }.getOrNull()
                        if (me != null) {
                            settingsStore.setSession(
                                accessToken = token.accessToken,
                                refreshToken = token.refreshToken,
                                username = me.login,
                                userId = me.id,
                            )
                        }
                        _state.update { it.copy(isAuthenticating = false, isLoggedIn = true) }
                        return@launch
                    }
                    is DevicePollResult.SlowDown -> intervalMs += 5_000
                    is DevicePollResult.Expired -> {
                        _state.update {
                            it.copy(isAuthenticating = false, userCode = null, verificationUri = null, error = "Sign-in expired, please try again.")
                        }
                        return@launch
                    }
                    // Pending, or a transient null from a network hiccup: keep polling.
                    else -> {}
                }
            }
            _state.update {
                it.copy(isAuthenticating = false, userCode = null, verificationUri = null, error = "Sign-in timed out, please try again.")
            }
        }
    }

    override fun onCleared() {
        loginJob?.cancel()
        super.onCleared()
    }
}
