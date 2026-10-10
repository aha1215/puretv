package com.puretv.twitch.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.puretv.twitch.core.adblock.AdBlockEngine
import com.puretv.twitch.core.adblock.AdMarkers
import com.puretv.twitch.core.adblock.AdSimulator
import com.puretv.twitch.core.adblock.BackupStreamResolver
import com.puretv.twitch.core.adblock.PlaylistAction
import com.puretv.twitch.core.adblock.PlaylistDetect
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.stream.HlsMasterParser
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.data.SmbRoutingDataSourceFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

data class SubtitleTrack(
    val key: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/**
 * SECTION 06.3 / 07.4, TV counterpart of the phone app's `TwitchPlayer`.
 * Identical ExoPlayer/OkHttp/[AdBlockEngine] wiring (low-latency live HLS + the
 * seamless [TvAdBlockInterceptor] backup-swap pipeline), duplicated rather than
 * shared because `ExoPlayer` is an Android-only `androidx.media3` type that
 * can't live in the multiplatform `core` module (Section 12.2: app-tv shares no
 * platform/UI code with app-android, only `core`).
 */
@UnstableApi
class TvPlayer(
    private val context: Context,
    private val adBlockEngine: AdBlockEngine,
    private val backupStreamResolver: BackupStreamResolver,
    private val settingsStore: AppSettingsStore,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(TvAdBlockInterceptor(adBlockEngine, backupStreamResolver))
        .build()

    /**
     * Backing player. Held lazily and nulled on [release] so this app-wide
     * singleton can free its codec/audio resources when the Activity finishes yet
     * still hand back a FRESH player if the process survives and the user reopens
     * the app: accessing [exoPlayer] after a release rebuilds it rather than
     * handing out a released (unusable) instance.
     */
    private var _exoPlayer: ExoPlayer? = null
    private var externalSubtitleListener: Player.Listener? = null
    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks

    private val subtitleTrackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _subtitleTracks.value = buildList {
                tracks.groups.forEach { group ->
                    if (group.type != C.TRACK_TYPE_TEXT) return@forEach
                    for (index in 0 until group.length) {
                        if (!group.isTrackSupported(index)) continue
                        val format = group.mediaTrackGroup.getFormat(index)
                        add(
                            SubtitleTrack(
                                key = subtitleTrackKey(tracks, group, index),
                                label = format.label?.takeIf(String::isNotBlank)
                                    ?: format.language?.takeIf(String::isNotBlank)
                                    ?: "Subtitle ${size + 1}",
                                language = format.language?.takeIf(String::isNotBlank),
                                selected = group.isTrackSelected(index),
                            ),
                        )
                    }
                }
            }
        }
    }

    val exoPlayer: ExoPlayer
        get() = _exoPlayer ?: buildPlayer().also { _exoPlayer = it }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                // DefaultDataSource delegates HTTP(S) to the existing OkHttp client
                // (and ad-block interceptor) while supporting local content:// URIs.
                .setDataSourceFactory(
                    SmbRoutingDataSourceFactory(
                        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient)),
                        settingsStore,
                    ),
                ),
        )
        .setTrackSelector(
            DefaultTrackSelector(context).apply {
                setParameters(buildUponParameters().setSelectUndeterminedTextLanguage(true))
            },
        )
        // Request audio focus so another media app / a phone call ducks or pauses
        // us instead of being talked over, and pause when audio output goes away.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Deeper buffer than a thin low-latency profile: Fire TV Sticks and
                // Chromecast dongles jitter on Wi-Fi and a thin buffer causes chronic
                // rebuffering, which reads worse on a TV than a little extra latency.
                .setBufferDurationsMs(
                    /* minBuffer  */ 5_000,
                    /* maxBuffer  */ 30_000,
                    /* playback   */ 1_500,
                    /* rebuffer   */ 3_000,
                )
                .build(),
        )
        .setLivePlaybackSpeedControl(
            DefaultLivePlaybackSpeedControl.Builder()
                .setFallbackMaxPlaybackSpeed(1.04f)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = true
            addListener(recoveryListener)
            addListener(subtitleTrackListener)
        }

    /**
     * Bounded retry counter for transient HLS errors (see [recoveryListener]).
     * Reset on the first STATE_READY and whenever a new stream is selected via
     * [playUrl], so a healthy stream never inherits a dead sibling's exhausted
     * budget on the shared singleton.
     */
    private var errorRetries = 0

    /**
     * SECTION 06.3, keeps live HLS blips from dead-ending on a black screen.
     * BEHIND_LIVE_WINDOW (we fell off the live edge) is recovered by re-seeking to
     * the default (live) position and re-preparing. Any other error gets a BOUNDED
     * prepare() retry (max 3) so a flapping segment server cannot spin the loader
     * forever; once playback reaches STATE_READY the budget resets.
     */
    private val recoveryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exoPlayer.seekToDefaultPosition()
                exoPlayer.prepare()
                return
            }
            if (errorRetries < MAX_ERROR_RETRIES) {
                errorRetries++
                exoPlayer.prepare()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) errorRetries = 0
        }
    }

    /**
     * Selects a stream for playback, resetting the transient-error retry budget so
     * a fresh stream always gets its full allowance even if the previous one
     * exhausted it without ever reaching STATE_READY (a dead/offline channel).
     */
    fun playUrl(url: String) {
        errorRetries = 0
        with(exoPlayer) {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            play()
        }
    }

    /** Play an archive URL and optionally attach a local SRT document. */
    fun playVod(url: String, subtitleUri: Uri? = null, startPositionMs: Long = 0L) {
        errorRetries = 0
        setQuality(StreamQuality.AUTO)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val item = mediaItem(url, subtitleUri)
        with(exoPlayer) {
            setMediaItem(item, startPositionMs.coerceAtLeast(0L))
            prepare()
            play()
        }
    }

    /** Replace the current VOD item while preserving position, pause state and track caps. */
    fun setExternalSubtitle(uri: Uri?) {
        val player = exoPlayer
        val current = player.currentMediaItem ?: return
        externalSubtitleListener?.let(player::removeListener)
        externalSubtitleListener = null
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        val tracks = player.trackSelectionParameters
        player.setMediaItem(mediaItem(current.localConfiguration?.uri?.toString() ?: return, uri), position)
        player.trackSelectionParameters = tracks.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        if (uri != null) {
            // Explicitly select the external subtitle track once Media3 has parsed
            // it. Some TV track selectors leave an undetermined-language SRT
            // unselected even when the configuration carries DEFAULT.
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val textGroup = tracks.groups.firstOrNull { group ->
                        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { index ->
                            group.isTrackSupported(index) && group.mediaTrackGroup.getFormat(index).label == "External subtitles"
                        }
                    } ?: tracks.groups.firstOrNull { group ->
                        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSupported)
                    } ?: return
                    val trackIndex = (0 until textGroup.length).firstOrNull { index ->
                        textGroup.isTrackSupported(index) && textGroup.mediaTrackGroup.getFormat(index).label == "External subtitles"
                    } ?: (0 until textGroup.length).firstOrNull(textGroup::isTrackSupported) ?: return
                    externalSubtitleListener = null
                    player.removeListener(this)
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(textGroup.mediaTrackGroup, listOf(trackIndex)),
                        )
                        .build()
                }
            }
            externalSubtitleListener = listener
            player.addListener(listener)
        }
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    /** Select one supported text track, disable text tracks, or restore automatic selection. */
    fun selectSubtitleTrack(trackKey: String) {
        val player = exoPlayer
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        when (trackKey) {
            AUTO_SUBTITLE_TRACK -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            OFF_SUBTITLE_TRACK -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> {
                val currentTracks = player.currentTracks
                val match = currentTracks.groups.asSequence()
                    .filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMap { group -> (0 until group.length).asSequence().map { index -> group to index } }
                    .firstOrNull { (group, index) -> group.isTrackSupported(index) && subtitleTrackKey(currentTracks, group, index) == trackKey }
                if (match != null) {
                    val (group, index) = match
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
                } else {
                    // A saved track can disappear from a later encode. Leave automatic selection active.
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                }
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun subtitleTrackKey(tracks: Tracks, targetGroup: Tracks.Group, targetIndex: Int): String {
        val base = subtitleBaseKey(targetGroup.mediaTrackGroup.getFormat(targetIndex))
        var occurrence = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                if (group === targetGroup && index == targetIndex) return "$base#$occurrence"
                if (subtitleBaseKey(group.mediaTrackGroup.getFormat(index)) == base) occurrence++
            }
        }
        return "$base#$occurrence"
    }

    private fun subtitleBaseKey(format: androidx.media3.common.Format): String = listOf(
        format.id.orEmpty(),
        format.language.orEmpty(),
        format.label.orEmpty(),
        format.sampleMimeType.orEmpty(),
        format.roleFlags.toString(),
    ).joinToString("|")

    private fun mediaItem(url: String, subtitleUri: Uri?): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (subtitleUri != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLabel("External subtitles")
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    companion object {
        const val AUTO_SUBTITLE_TRACK = "__puretv_auto__"
        const val OFF_SUBTITLE_TRACK = "__puretv_off__"
        private const val MAX_ERROR_RETRIES = 3
    }

    /**
     * SECTION 7.4: force a max quality on the live HLS ladder via ExoPlayer
     * track selection (FAST_FORWARD/REWIND and the quality chips drive this).
     * SOURCE/AUTO clear the cap so ABR picks the best the link sustains; a
     * specific resolution caps the video size; AUDIO_ONLY disables video entirely.
     */
    fun setQuality(quality: StreamQuality) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        when (quality) {
            StreamQuality.AUDIO_ONLY -> builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            StreamQuality.SOURCE, StreamQuality.AUTO -> builder.clearVideoSizeConstraints()
            StreamQuality.P1080P60 -> builder.setMaxVideoSize(1920, 1080)
            StreamQuality.P720P60 -> builder.setMaxVideoSize(1280, 720)
            StreamQuality.P480P -> builder.setMaxVideoSize(854, 480)
            StreamQuality.P360P -> builder.setMaxVideoSize(640, 360)
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    /**
     * Stops playback and clears the queue without tearing down the player. No-op if
     * already released, so a stream-leave teardown after the Activity finished does
     * not resurrect a fresh idle player via [exoPlayer].
     */
    fun stop() {
        _exoPlayer?.run {
            externalSubtitleListener?.let(::removeListener)
            externalSubtitleListener = null
            stop()
            clearMediaItems()
        }
        _subtitleTracks.value = emptyList()
    }

    /**
     * Frees the underlying player. Safe from Activity.onDestroy: the next
     * [exoPlayer] access rebuilds a fresh instance, so a surviving-process relaunch
     * never touches a released player.
     */
    fun release() {
        externalSubtitleListener?.let { listener -> _exoPlayer?.removeListener(listener) }
        externalSubtitleListener = null
        _exoPlayer?.release()
        _exoPlayer = null
        _subtitleTracks.value = emptyList()
        errorRetries = 0
    }

}

/**
 * Removes Twitch ad pods from HLS playlist responses, seamless-first. Ported 1:1
 * from the phone app's `AdBlockInterceptor` (Section 4.2 / 6.3):
 *   1. BACKUP SWAP (primary, seamless), when a media playlist contains a
 *      stitched ad, mint a fresh manifest under an alternate player type
 *      (embed/popout), which Twitch usually serves ad-free for the same live
 *      stream, and serve that quality's CLEAN media playlist. No gap. (Green
 *      pill: AD BLOCKED.)
 *   2. STRIP (fallback), only if every backup player type is ALSO ad-showing,
 *      cut the pod out; the player briefly stalls at the splice. (Yellow pill:
 *      AD FILTERED.)
 *
 * The channel + the ad-bearing variant's quality are learned from the master
 * playlist as it passes through (the master URL carries the channel; each
 * #EXT-X-STREAM-INF carries the variant's resolution/frame-rate), keyed by the
 * variant URL path so the media refreshes that follow can look themselves up.
 */
@UnstableApi
class TvAdBlockInterceptor(
    private val adBlockEngine: AdBlockEngine,
    private val backupStreamResolver: BackupStreamResolver,
) : Interceptor {

    private data class VariantMeta(val resolution: String, val frameRate: Double)

    @Volatile private var channelLogin: String? = null
    private val variantByPath = ConcurrentHashMap<String, VariantMeta>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Honour the user's "Enable ad block" switch: when off, do no filtering.
        if (!adBlockEngine.isEnabled) {
            return chain.proceed(request)
        }

        if (PlaylistDetect.classifyRequest(request.url.encodedPath) == PlaylistAction.SKIP_SEGMENT) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request)
        val contentType = response.header("Content-Type")
        val firstLine = if ("mpegurl" in (contentType?.lowercase() ?: "")) {
            "#EXTM3U"
        } else {
            runCatching { response.peekBody(8L * 1024).string() }
                .getOrNull()
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
        }

        if (PlaylistDetect.classifyResponse(contentType, firstLine) != PlaylistAction.FILTER_PLAYLIST) {
            return response
        }

        val body = response.body ?: return response
        val rawBody = body.string()

        // A master playlist (#EXT-X-STREAM-INF) carries no ad segments; we only
        // learn its channel + variant ladder, then pass it through untouched.
        if (rawBody.contains("#EXT-X-STREAM-INF")) {
            learnFromMaster(request.url, rawBody)
            return response.newBuilder()
                .body(rawBody.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                .removeHeader("Content-Length")
                .build()
        }

        // Media playlist: AdSimulator only makes sense here (a media playlist with
        // #EXTINF/#EXT-X-MEDIA-SEQUENCE), not on the master.
        val raw = AdSimulator.inject(rawBody)
        val cleaned = resolveCleanMedia(request.url, raw)

        return response.newBuilder()
            .body(cleaned.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
            .removeHeader("Content-Length")
            .build()
    }

    /** Record the channel + per-variant quality so media refreshes can swap. */
    private fun learnFromMaster(masterUrl: HttpUrl, master: String) {
        // Usher master URL: .../api/channel/hls/{channel}.m3u8
        val channel = masterUrl.pathSegments.lastOrNull()
            ?.removeSuffix(".m3u8")
            ?.takeIf { it.isNotBlank() }
        if (channel != null && channel != channelLogin) {
            variantByPath.clear()
            channelLogin = channel
        }
        runCatching { HlsMasterParser.parseVariants(master) }.getOrNull()?.forEach { variant ->
            variant.url.toHttpUrlOrNull()?.let { url ->
                variantByPath[url.encodedPath] = VariantMeta(variant.resolution, variant.frameRate)
            }
        }
    }

    private fun resolveCleanMedia(requestUrl: HttpUrl, raw: String): String {
        if (!AdMarkers.containsAds(raw)) {
            adBlockEngine.reportAdBlocked() // clean refresh, no ad
            return raw
        }

        // Tier 1: seamless backup player-type swap (real content, no stall).
        val channel = channelLogin
        val meta = variantByPath[requestUrl.encodedPath]
        if (channel != null && meta != null) {
            val backup = runCatching {
                runBlocking {
                    backupStreamResolver.resolveAdFreeMediaPlaylist(
                        channelLogin = channel,
                        targetResolution = meta.resolution,
                        targetFrameRate = meta.frameRate,
                        nowMs = System.currentTimeMillis(),
                    )
                }
            }.getOrNull()
            if (backup != null) {
                adBlockEngine.reportAdBlocked() // swapped to a clean manifest, no gap
                return backup.mediaPlaylist
            }
        }

        // Tier 2: no clean backup available; strip the pod (brief stall).
        return try {
            val filtered = adBlockEngine.filterPlaylist(raw)
            adBlockEngine.reportFiltered(filtered)
            filtered.content
        } catch (t: Throwable) {
            // Never silently pass ads as success: report the failure so the pill
            // goes AD_BLOCK_OFF, then return the RAW playlist so playback keeps
            // running instead of dead-ending.
            adBlockEngine.reportResolutionFailure()
            raw
        }
    }
}
