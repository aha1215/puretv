package com.puretv.twitch.tv.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.tv.player.TvPlayer
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.ui.StreamViewModel
import com.puretv.twitch.tv.ui.components.TvChatOverlay
import com.puretv.twitch.tv.ui.components.TvChatEditorDialog
import com.puretv.twitch.tv.ui.components.TvSubtitleOverlay
import com.puretv.twitch.tv.ui.components.TvControlsOverlay
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * SECTION 07.4 [CRITICAL] — immersive fullscreen player.
 *
 * Behavior, mapped 1:1 to the spec:
 *   • Starts fullscreen immediately, no chrome.
 *   • ANY D-pad key shows [TvControlsOverlay]; a 3-second inactivity timer
 *     hides it again (`controlsVisible` + [LaunchedEffect] restart-on-input).
 *   • Chat can be opened directly from the player controls, then repositioned
 *     or resized with the D-pad without interrupting playback.
 *   • FAST_FORWARD / REWIND step [StreamQuality] up/down (live streams can't
 *     seek — repurposed exactly as Section 7.4 specifies).
 *   • DPAD_CENTER / ENTER / PLAY_PAUSE toggle playback via [TvPlayer.togglePlayPause].
 *   • BACK saves and leaves chat editing when active; otherwise it exits the
 *     screen (`onBack`) immediately, even when chat is visible. App-level back
 *     handling lives in `TvMainActivity` and the Activity default behavior.
 *
 * The whole screen is wrapped in a single `focusable` + `onKeyEvent` host so
 * every remote key reaches this handler regardless of which (invisible, by
 * default) child currently holds focus — avoiding the need to scatter key
 * handlers across the overlay's buttons.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvStreamScreen(
    channelLogin: String,
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit,
    viewModel: StreamViewModel = koinViewModel(parameters = { parametersOf(channelLogin) }),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val tvPlayer = koinInject<TvPlayer>()
    val settingsStore = koinInject<AppSettingsStore>()
    val appSettings by settingsStore.flow.collectAsState(initial = com.puretv.twitch.core.model.AppSettings())
    val chatAppearance by settingsStore.chatAppearance.collectAsState(initial = AppSettingsStore.ChatAppearance())
    val subtitleAppearance by settingsStore.subtitleAppearance.collectAsState(initial = AppSettingsStore.SubtitleAppearance())
    val rootFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    var controlsVisible by remember { mutableStateOf(true) }
    var chatVisible by remember { mutableStateOf(false) }
    var chatEditing by remember { mutableStateOf(false) }
    var chatSaving by remember { mutableStateOf(false) }
    var draftChatAppearance by remember { mutableStateOf(chatAppearance) }
    var draftChatFontSize by remember { mutableStateOf(appSettings.chatFontSize) }
    var resumeChatAfterEdit by remember { mutableStateOf(false) }
    var subtitleCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(StreamQuality.SOURCE) }
    var lastInputAt by remember { mutableStateOf(0L) }

    fun beginChatEditing() {
        resumeChatAfterEdit = tvPlayer.exoPlayer.isPlaying
        if (resumeChatAfterEdit) tvPlayer.exoPlayer.pause()
        draftChatAppearance = if (
            chatAppearance.positionXFraction == null &&
            chatAppearance.positionYFraction == null &&
            chatAppearance.heightFraction >= 0.98f
        ) chatAppearance.copy(heightFraction = 0.7f) else chatAppearance
        draftChatFontSize = appSettings.chatFontSize
        chatVisible = true
        chatEditing = true
        controlsVisible = false
        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { rootFocusRequester.requestFocus() } }
    }

    fun finishChatEditing() {
        if (chatSaving) return
        chatSaving = true
        val draft = draftChatAppearance
        scope.launch {
            try {
                settingsStore.updateChatAppearance {
                    draft
                }
                settingsStore.update { it.copy(chatFontSize = draftChatFontSize) }
                chatVisible = true
                chatEditing = false
                if (resumeChatAfterEdit) tvPlayer.exoPlayer.play()
                resumeChatAfterEdit = false
            } catch (_: Exception) {
                // Keep the editor open so Back can retry if preferences fail to save.
            } finally {
                chatSaving = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { rootFocusRequester.requestFocus() }
        // Reveal the chrome on entry, then let the auto-hide timer take it away
        // after 3s (kick-starts the LaunchedEffect(lastInputAt) countdown below).
        lastInputAt = System.currentTimeMillis()
    }

    LaunchedEffect(state.playableUrl) {
        val url = state.playableUrl ?: return@LaunchedEffect
        tvPlayer.playUrl(url)
        isPlaying = true
    }

    // Apply the selected quality to the player whenever it changes (the quality
    // chips and FAST_FORWARD/REWIND both update `quality`). Runs on first
    // composition too, so the initial SOURCE selection clears any stale cap the
    // shared singleton player carried over from a previous stream.
    LaunchedEffect(quality) {
        runCatching { tvPlayer.setQuality(quality) }
    }

    // Auto-hide: any input bumps `lastInputAt`; this effect restarts its 3s
    // countdown each time that happens and hides the chrome once it elapses
    // with no further input (Section 7.4: "auto-hide after 3 seconds").
    LaunchedEffect(chatEditing, lastInputAt) {
        if (lastInputAt == 0L) return@LaunchedEffect
        if (chatEditing) {
            controlsVisible = false
            return@LaunchedEffect
        }
        controlsVisible = true
        delay(3_000)
        controlsVisible = false
    }

    DisposableEffect(Unit) {
        val player = tvPlayer.exoPlayer
        val window = context.findActivity()?.window
        val playbackListener = object : Player.Listener {
            override fun onIsPlayingChanged(nowPlaying: Boolean) {
                isPlaying = nowPlaying
                if (nowPlaying) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleCues = cueGroup.cues
            }
        }

        player.addListener(playbackListener)
        playbackListener.onIsPlayingChanged(player.isPlaying)

        // TvPlayer is an app-wide Koin singleton with playWhenReady=true, so
        // leaving this screen must STOP it, otherwise audio keeps playing after
        // the viewer navigates back. We stop (not release): the singleton is reused
        // for the next stream and rebuilds itself if it was ever released.
        // The screen-on flag is tied to actual playback and always cleared here,
        // allowing Fire TV's screensaver to work normally in menus or when paused.
        onDispose {
            player.removeListener(playbackListener)
            subtitleCues = emptyList()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvPlayer.stop()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureTvTvTheme.colors.surfaceLowest)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    // Resolve Back before ordinary input so it cannot restart the
                    // controls auto-hide timer and keep the player on screen.
                    event.key == Key.Back -> {
                        if (chatEditing) {
                            finishChatEditing()
                        } else if (controlsVisible) {
                            controlsVisible = false
                            scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { rootFocusRequester.requestFocus() } }
                        } else {
                            onBack()
                        }
                        true
                    }
                    chatEditing -> false
                    event.key == Key.DirectionUp && !controlsVisible && !chatEditing -> {
                        chatVisible = !chatVisible
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                lastInputAt = System.currentTimeMillis()

                if (chatEditing) return@onKeyEvent false

                when (event.key) {
                    Key.DirectionRight -> {
                        if (controlsVisible) false else if (!chatVisible) { chatVisible = true; true } else false
                    }
                    Key.DirectionLeft -> {
                        if (controlsVisible) false else if (chatVisible) { chatVisible = false; true } else false
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        // With no playable URL there is nothing to pause, so OK
                        // becomes the retry affordance the error panel advertises.
                        if (state.playbackError != null) {
                            viewModel.retry()
                        } else {
                            tvPlayer.togglePlayPause()
                            isPlaying = tvPlayer.exoPlayer.isPlaying
                        }
                        true
                    }
                    Key.MediaFastForward -> {
                        quality = stepQuality(quality, up = true)
                        true
                    }
                    Key.MediaRewind -> {
                        quality = stepQuality(quality, up = false)
                        true
                    }
                    Key.Menu -> {
                        chatVisible = !chatVisible
                        true
                    }
                    Key.DirectionDown -> {
                        controlsVisible = true
                        false
                    }
                    else -> {
                        // Any other D-pad/remote input still resets the auto-hide
                        // timer (handled above) and reveals the chrome.
                        controlsVisible = true
                        false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (state.playableUrl != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(context).apply {
                        useController = false
                        player = tvPlayer.exoPlayer
                        subtitleView?.visibility = android.view.View.INVISIBLE
                        // The player surface must never take D-pad focus: the root
                        // Box owns every remote key via onKeyEvent (incl. BACK). If
                        // PlayerView grabbed focus, key events would bypass that
                        // handler and BACK/quality/chat would stop responding.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                update = { view ->
                    view.player = tvPlayer.exoPlayer
                    view.subtitleView?.visibility = android.view.View.INVISIBLE
                },
            )
        }

        // Resolution failed: say so instead of leaving a black rectangle. Not
        // tied to `controlsVisible` — the auto-hide timer must never take the
        // only explanation of the failure away with it.
        if (state.playbackError != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = state.playbackError!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = PureTvTvTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Press OK to try again, or BACK to choose another channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PureTvTvTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        TvControlsOverlay(
            visible = controlsVisible,
            title = state.streamInfo?.title ?: state.channel?.displayName ?: channelLogin,
            viewerCount = state.streamInfo?.viewerCount?.toLong() ?: 0L,
            isPlaying = isPlaying,
            currentQuality = quality,
            adBlockStatus = state.adBlockStatus,
            onBack = onBack,
            onOpenChannel = { onOpenChannel(channelLogin) },
            onTogglePlayPause = {
                tvPlayer.togglePlayPause()
                isPlaying = tvPlayer.exoPlayer.isPlaying
            },
            onEditChat = ::beginChatEditing,
            onSelectQuality = { quality = it },
            modifier = Modifier.fillMaxSize(),
        )

        TvChatOverlay(
            visible = chatVisible,
            messages = state.chatMessages,
            appSettings = if (chatEditing) appSettings.copy(chatFontSize = draftChatFontSize) else appSettings,
            appearance = if (chatEditing) draftChatAppearance else chatAppearance,
            editing = false,
            modifier = Modifier.fillMaxSize(),
        )
        TvSubtitleOverlay(subtitleCues, subtitleAppearance, Modifier.fillMaxSize())
        if (chatEditing) {
            TvChatEditorDialog(
                settings = appSettings,
                appearance = draftChatAppearance,
                chatMessages = state.chatMessages,
                fontSize = draftChatFontSize,
                onFontSizeChange = { draftChatFontSize = it },
                onAppearanceChange = { draftChatAppearance = it },
                onSave = ::finishChatEditing,
            )
        }
    }
}

/** Section 7.4 — FAST_FORWARD/REWIND remap to quality up/down on live (unseekable) streams. */
private fun stepQuality(current: StreamQuality, up: Boolean): StreamQuality {
    val ordered = StreamQuality.entries.sortedBy { it.sortOrder }
    val index = ordered.indexOf(current).let { if (it < 0) 0 else it }
    val next = if (up) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(ordered.lastIndex)
    return ordered[next]
}

/** Unwraps Compose's themed context to the Activity that owns the TV window. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
