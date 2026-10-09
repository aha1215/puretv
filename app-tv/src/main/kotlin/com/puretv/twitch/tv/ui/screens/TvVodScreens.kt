package com.puretv.twitch.tv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.ui.TvVodListViewModel
import com.puretv.twitch.tv.ui.TvVodPlayerViewModel
import com.puretv.twitch.tv.ui.components.TvButtonSize
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.components.TvExpressiveIconButton
import com.puretv.twitch.tv.ui.components.TvPageTitle
import com.puretv.twitch.tv.ui.components.TvPanel
import com.puretv.twitch.tv.ui.components.ExpressiveIcons
import com.puretv.twitch.tv.ui.components.TvChatOverlay
import com.puretv.twitch.tv.ui.components.TvSectionHeading
import com.puretv.twitch.tv.ui.components.tvFocusClickable
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.compose.koinInject

@Composable
fun TvVodListScreen(channelLogin: String, onOpenVod: (VideoInfo) -> Unit, onBack: () -> Unit,
                    viewModel: TvVodListViewModel = koinViewModel(parameters = { parametersOf(channelLogin) })) {
    val state by viewModel.state.collectAsState()
    val c = PureTvTvTheme.colors
    val firstVideoFocus = remember { FocusRequester() }
    LaunchedEffect(state.videos.isNotEmpty()) {
        if (state.videos.isNotEmpty()) runCatching { firstVideoFocus.requestFocus() }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, viewModel, state.videos.size, state.hasMore, state.loadingMore, state.loadMoreError) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && state.hasMore && !state.loadingMore && state.loadMoreError == null &&
                lastVisible >= state.videos.size - 5
        }.collect { shouldLoad -> if (shouldLoad) viewModel.loadMore() }
    }
    Column(Modifier.fillMaxSize().background(c.surface).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvExpressiveIconButton(icon = ExpressiveIcons.Back, contentDescription = "Back", onClick = onBack)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TvPageTitle("Past videos")
                Text("$channelLogin · Use the D-pad to choose · OK to play", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        when {
            state.loading -> Text("Loading past videos…", color = c.onSurfaceVariant)
            state.error != null -> Text(state.error!!, color = c.onSurfaceVariant)
            state.videos.isEmpty() -> Text("No past videos found.", color = c.onSurfaceVariant)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(state.videos, key = { _, video -> video.id }) { index, video ->
                    val interaction = remember(video.id) { MutableInteractionSource() }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .then(if (index == 0) Modifier.focusRequester(firstVideoFocus) else Modifier)
                            .tvFocusClickable(
                                interaction = interaction,
                                onClick = { onOpenVod(video) },
                                restRadius = 18.dp,
                                focusRadius = 22.dp,
                                color = c.surfaceLow,
                                focusColor = c.surfaceHigh,
                                scale = 1.035f,
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)) {
                            val thumbnail = video.thumbnailUrl
                                .replace("%{width}", "640")
                                .replace("%{height}", "360")
                            AsyncImage(model = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text(duration(video.durationSeconds), color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(video.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${video.type.name.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${video.viewCount} views", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                if (state.loadingMore || state.loadMoreError != null || state.hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center) {
                            when {
                                state.loadingMore -> Text("Loading more videos…", color = c.onSurfaceVariant)
                                state.loadMoreError != null -> TvExpressiveButton("Retry loading videos", { viewModel.loadMore() },
                                    style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                                else -> Text("Scroll for more videos", color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun TvVodPlayerScreen(channelLogin: String, vodId: String, title: String, thumbnailUrl: String, durationSeconds: Long, onBack: () -> Unit,
                      viewModel: TvVodPlayerViewModel = koinViewModel(parameters = { parametersOf(channelLogin, vodId, title, thumbnailUrl, durationSeconds) })) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val player = viewModel.player
    val settingsStore = koinInject<AppSettingsStore>()
    val appSettings by settingsStore.flow.collectAsState(initial = com.puretv.twitch.core.model.AppSettings())
    val chatAppearance by settingsStore.chatAppearance.collectAsState(initial = AppSettingsStore.ChatAppearance())
    val seekProfile by settingsStore.vodSeekProfile.collectAsState(initial = AppSettingsStore.VodSeekProfile.Standard)
    val c = PureTvTvTheme.colors
    var pickerMessage by remember { mutableStateOf<String?>(null) }
    var chatVisible by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull().orEmpty()
        if (!name.endsWith(".srt", ignoreCase = true) || !hasSrtCue(context, uri)) {
            pickerMessage = "Choose an .srt subtitle file."
            return@rememberLauncherForActivityResult
        }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModel.addSubtitle(uri)
        pickerMessage = null
    }
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    var seekingFraction by remember(vodId) { mutableStateOf<Float?>(null) }
    var seekBarFocused by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(player.exoPlayer.isPlaying) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        runCatching { focus.requestFocus() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(nowPlaying: Boolean) {
                isPlaying = nowPlaying
            }
        }
        player.exoPlayer.addListener(listener)
        onDispose { viewModel.savePlaybackPosition(); player.exoPlayer.removeListener(listener); player.stop() }
    }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsActivity by remember { mutableStateOf(0) }
    var controlsHaveFocus by remember { mutableStateOf(false) }
    var positionVisible by remember { mutableStateOf(false) }
    var positionActivity by remember { mutableStateOf(0) }
    var heldSeekDirection by remember { mutableStateOf<Key?>(null) }
    var heldSeekStartedAtMs by remember { mutableStateOf(0L) }
    var lastSeekStepMinutes by remember { mutableStateOf(1) }
    fun showControls() {
        controlsVisible = true
        controlsActivity++
    }
    fun showPosition() {
        controlsVisible = false
        controlsHaveFocus = false
        positionVisible = true
        heldSeekDirection = null
        heldSeekStartedAtMs = 0L
        lastSeekStepMinutes = seekProfile.stepsMinutes.first()
        positionActivity++
    }
    fun seekFromRemote(direction: Key) {
        if (!positionVisible) {
            viewModel.seekBy(if (direction == Key.DirectionLeft) -10_000L else 10_000L)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (heldSeekDirection != direction || heldSeekStartedAtMs == 0L) {
            heldSeekDirection = direction
            heldSeekStartedAtMs = now
        }
        val heldForMs = now - heldSeekStartedAtMs
        val stage = when {
            heldForMs >= 6_000L -> 3
            heldForMs >= 3_500L -> 2
            heldForMs >= 1_200L -> 1
            else -> 0
        }
        val minutes = seekProfile.stepsMinutes[stage.coerceIn(seekProfile.stepsMinutes.indices)]
        lastSeekStepMinutes = minutes
        val sign = if (direction == Key.DirectionLeft) -1L else 1L
        viewModel.seekBy(sign * minutes * 60_000L)
        positionActivity++
    }
    var playbackPosition by remember { mutableStateOf(0L) }
    var playbackDuration by remember { mutableStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            playbackPosition = player.exoPlayer.currentPosition.coerceAtLeast(0L)
            playbackDuration = player.exoPlayer.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(500)
        }
    }
    LaunchedEffect(controlsVisible, controlsActivity) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(8_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(positionVisible, positionActivity) {
        if (positionVisible) {
            kotlinx.coroutines.delay(4_500)
            positionVisible = false
            heldSeekDirection = null
            heldSeekStartedAtMs = 0L
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp) {
                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    heldSeekDirection = null
                    heldSeekStartedAtMs = 0L
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Back -> {
                    when {
                        positionVisible -> positionVisible = false
                        controlsVisible -> controlsVisible = false
                        chatVisible -> chatVisible = false
                        else -> onBack()
                    }
                    true
                }
                Key.Menu -> {
                    chatVisible = !chatVisible
                    controlsVisible = false
                    positionVisible = false
                    true
                }
                Key.DirectionDown -> {
                    positionVisible = false
                    if (!controlsVisible) {
                        showControls()
                        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { seekFocus.requestFocus() } }
                        true
                    } else false
                }
                Key.DirectionUp -> {
                    showPosition()
                    true
                }
                Key.DirectionLeft -> {
                    if (controlsVisible && (controlsHaveFocus || seekBarFocused)) false
                    else { seekFromRemote(Key.DirectionLeft); true }
                }
                Key.DirectionRight -> {
                    if (controlsVisible && (controlsHaveFocus || seekBarFocused)) false
                    else { seekFromRemote(Key.DirectionRight); true }
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (controlsVisible) false
                    else {
                        positionVisible = false
                        player.togglePlayPause()
                        true
                    }
                }
                else -> false
            }
        }
        .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        showControls()
        when (event.key) {
            Key.MediaPlayPause -> { player.togglePlayPause(); true }
            Key.MediaRewind -> { viewModel.seekBy(-10_000); true }
            Key.MediaFastForward -> { viewModel.seekBy(10_000); true }
            else -> false
        }
    }) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { playerContext -> PlayerView(playerContext).apply {
            useController = false
            this.player = player.exoPlayer
            // Android TV caption preferences can make external SRT cues tiny or
            // low-contrast on a 10-foot display. Give the native SubtitleView a
            // legible TV style while keeping Media3 responsible for cue timing.
            subtitleView?.apply {
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)
                setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28f)
                setStyle(
                    androidx.media3.ui.CaptionStyleCompat(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.argb(190, 0, 0, 0),
                        android.graphics.Color.TRANSPARENT,
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK,
                        android.graphics.Typeface.DEFAULT_BOLD,
                    ),
                )
                setBottomPaddingFraction(0.1f)
            }
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } }, update = { view -> view.player = player.exoPlayer })

        TvChatOverlay(
            visible = chatVisible,
            messages = state.chatMessages,
            appSettings = appSettings,
            appearance = chatAppearance,
        )

        if (controlsVisible) {
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = .58f)).padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$channelLogin  ·  VOD", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f))
                    }
                    Text("BACK to exit", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall)
                }
                (pickerMessage ?: state.subtitleMessage)?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.loading) Text("Loading video…", color = Color.White)
                state.error?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodyLarge) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .7f)).padding(horizontal = 32.dp, vertical = 18.dp)
                    .onFocusChanged { controlsHaveFocus = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playerTime(playbackPosition / 1_000), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(if (chatVisible) "Chat on" else "Chat off", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                    Text(playerTime(playbackDuration / 1_000), color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                val fraction = (seekingFraction ?: if (playbackDuration > 0L) playbackPosition.toFloat() / playbackDuration else 0f).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(32.dp)
                        .border(2.dp, if (seekBarFocused) c.primary else Color.Transparent, RoundedCornerShape(8.dp))
                        .focusRequester(seekFocus)
                        .onKeyEvent { seekEvent ->
                            if (seekEvent.type != KeyEventType.KeyDown || playbackDuration <= 0L) return@onKeyEvent false
                            val step = if (playbackDuration > 3_600_000L) 0.01f else 0.025f
                            val target = when (seekEvent.key) {
                                Key.DirectionLeft -> (fraction - step).coerceAtLeast(0f)
                                Key.DirectionRight -> (fraction + step).coerceAtMost(1f)
                                else -> return@onKeyEvent false
                            }
                            seekingFraction = target
                            viewModel.seekTo((playbackDuration * target).toLong())
                            showControls()
                            true
                        }
                        .onFocusChanged { seekBarFocused = it.isFocused }
                        .focusable(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(6.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .28f))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.primary))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    TvExpressiveButton("−1 min", { showControls(); viewModel.seekBy(-60_000) }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    TvExpressiveButton(if (isPlaying) "Pause" else "Play", { showControls(); player.togglePlayPause() }, style = TvButtonStyle.Filled, size = TvButtonSize.Medium, modifier = Modifier.focusRequester(playFocus))
                    TvExpressiveButton("+1 min", { showControls(); viewModel.seekBy(60_000) }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    TvExpressiveButton(if (chatVisible) "Hide chat" else "Show chat", {
                        chatVisible = !chatVisible
                        if (chatVisible) controlsVisible = false else showControls()
                    }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    TvExpressiveButton(
                        "Load subtitles",
                        {
                            showControls()
                            try {
                                launcher.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream", "*/*"))
                            } catch (_: ActivityNotFoundException) {
                                pickerMessage = "No file picker is available on this TV."
                            }
                        },
                        style = TvButtonStyle.Tonal,
                        size = TvButtonSize.Medium,
                        modifier = Modifier.focusRequester(subtitleFocus),
                    )
                    if (state.subtitleMessage?.contains("loaded", ignoreCase = true) == true) {
                        TvExpressiveButton("Clear subtitles", { showControls(); viewModel.clearSubtitle() }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    }
                }
                Text("Remote: UP position · LEFT/RIGHT seek · CENTER play/pause · MENU chat", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }

        if (positionVisible && playbackDuration > 0L) {
            val fraction = (playbackPosition.toFloat() / playbackDuration).coerceIn(0f, 1f)
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 28.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = .84f))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("PLAYBACK POSITION", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playerTime(playbackPosition / 1_000), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text("−${playerTime(((playbackDuration - playbackPosition).coerceAtLeast(0L)) / 1_000)}", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.titleMedium)
                    Text(playerTime(playbackDuration / 1_000), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .28f))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.primary))
                }
                Text(
                    "LEFT/RIGHT  ·  ${lastSeekStepMinutes} min jump  ·  Hold for larger jumps",
                    color = Color.White.copy(alpha = .78f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun duration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun playerTime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
}

private fun hasSrtCue(context: android.content.Context, uri: android.net.Uri): Boolean = runCatching {
    var found = false
    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
        repeat(200) {
            val line = reader.readLine() ?: return@use
            if (line.matches(Regex("\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s+-->\\s+\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}.*"))) {
                found = true
                return@use
            }
        }
    }
    found
}.getOrDefault(false)
