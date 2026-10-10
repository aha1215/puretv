package com.puretv.twitch.tv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.window.Dialog
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
import com.puretv.twitch.tv.ui.components.TvChatEditDirection
import com.puretv.twitch.tv.ui.components.TvChatEditMode
import com.puretv.twitch.tv.ui.components.TvSectionHeading
import com.puretv.twitch.tv.ui.components.adjustTvChatAppearance
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
    val subtitleAppearance by settingsStore.subtitleAppearance.collectAsState(initial = AppSettingsStore.SubtitleAppearance())
    val subtitleTracks by player.subtitleTracks.collectAsState()
    val configuration = LocalConfiguration.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val c = PureTvTvTheme.colors
    var pickerMessage by remember { mutableStateOf<String?>(null) }
    var subtitlePanelVisible by remember { mutableStateOf(false) }
    var chatVisible by remember { mutableStateOf(true) }
    var chatEditing by remember { mutableStateOf(false) }
    var chatEditMode by remember { mutableStateOf(TvChatEditMode.Reposition) }
    var chatSaving by remember { mutableStateOf(false) }
    var draftChatAppearance by remember { mutableStateOf(chatAppearance) }
    var draftChatFontSize by remember { mutableStateOf(appSettings.chatFontSize) }
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
    val subtitlePanelFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    var seekingFraction by remember(vodId) { mutableStateOf<Float?>(null) }
    var seekBarFocused by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(player.exoPlayer.isPlaying) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.yield()
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(state.loading, state.subtitleTrackPreference) {
        if (state.loading) return@LaunchedEffect
        val preference = state.subtitleTrackPreference
        if (preference == com.puretv.twitch.tv.player.TvPlayer.AUTO_SUBTITLE_TRACK ||
            preference == com.puretv.twitch.tv.player.TvPlayer.OFF_SUBTITLE_TRACK
        ) {
            viewModel.applySubtitleTrackPreference(preference)
        } else {
            val available = withTimeoutOrNull(15_000L) {
                snapshotFlow { player.subtitleTracks.value to player.exoPlayer.playbackState }
                    .first { (tracks, playbackState) ->
                        tracks.any { it.key == preference } || playbackState == Player.STATE_READY
                    }
            }?.first.orEmpty()
            if (available.any { it.key == preference }) viewModel.applySubtitleTrackPreference(preference)
            else viewModel.selectSubtitleTrack(com.puretv.twitch.tv.player.TvPlayer.AUTO_SUBTITLE_TRACK)
        }
    }
    LaunchedEffect(subtitlePanelVisible) {
        if (subtitlePanelVisible) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { subtitlePanelFocus.requestFocus() }
        }
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
    fun showControls() {
        controlsVisible = true
        controlsActivity++
    }
    fun beginChatEditing() {
        draftChatAppearance = if (
            chatAppearance.positionXFraction == null &&
            chatAppearance.positionYFraction == null &&
            chatAppearance.heightFraction >= 0.98f
        ) chatAppearance.copy(heightFraction = 0.7f) else chatAppearance
        draftChatFontSize = appSettings.chatFontSize
        chatVisible = true
        chatEditing = true
        chatEditMode = TvChatEditMode.Reposition
        controlsVisible = false
        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { focus.requestFocus() } }
    }
    fun openSubtitlePanel() {
        subtitlePanelVisible = true
        controlsVisible = false
    }
    fun updateSubtitleAppearance(transform: (AppSettingsStore.SubtitleAppearance) -> AppSettingsStore.SubtitleAppearance) {
        scope.launch { settingsStore.updateSubtitleAppearance(transform) }
    }
    fun finishChatEditing() {
        if (chatSaving) return
        chatSaving = true
        val draft = draftChatAppearance
        scope.launch {
            try {
                settingsStore.updateChatAppearance {
                    it.copy(
                        widthDp = draft.widthDp,
                        side = draft.side,
                        positionXFraction = draft.positionXFraction,
                        positionYFraction = draft.positionYFraction,
                        heightFraction = draft.heightFraction,
                    )
                }
                settingsStore.update { it.copy(chatFontSize = draftChatFontSize) }
                chatVisible = true
                chatEditing = false
                chatEditMode = TvChatEditMode.Reposition
            } catch (_: Exception) {
                // Keep the editor open so Back can retry if preferences fail to save.
            } finally {
                chatSaving = false
            }
        }
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
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (chatEditing) {
                if (chatSaving) return@onPreviewKeyEvent true
                if (event.key == Key.Back) {
                    finishChatEditing()
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        chatEditMode = when (chatEditMode) {
                            TvChatEditMode.Reposition -> TvChatEditMode.Resize
                            TvChatEditMode.Resize -> TvChatEditMode.FontSize
                            TvChatEditMode.FontSize -> TvChatEditMode.Reposition
                        }
                    }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        val direction = when (event.key) {
                            Key.DirectionLeft -> TvChatEditDirection.Left
                            Key.DirectionRight -> TvChatEditDirection.Right
                            Key.DirectionUp -> TvChatEditDirection.Up
                            else -> TvChatEditDirection.Down
                        }
                        if (chatEditMode == TvChatEditMode.FontSize) {
                            draftChatFontSize = when (direction) {
                                TvChatEditDirection.Up -> (draftChatFontSize + 1f).coerceAtMost(14f)
                                TvChatEditDirection.Down -> (draftChatFontSize - 1f).coerceAtLeast(8f)
                                else -> draftChatFontSize
                            }
                        } else {
                            draftChatAppearance = adjustTvChatAppearance(
                                appearance = draftChatAppearance,
                                direction = direction,
                                resizeMode = chatEditMode == TvChatEditMode.Resize,
                                availableWidthDp = (configuration.screenWidthDp - 24).coerceAtLeast(280).toFloat(),
                                availableHeightDp = (configuration.screenHeightDp - 24).coerceAtLeast(240).toFloat(),
                            )
                        }
                    }
                    else -> Unit
                }
                return@onPreviewKeyEvent true
            }
            if (subtitlePanelVisible) {
                if (event.key == Key.Back) {
                    subtitlePanelVisible = false
                    showControls()
                }
                return@onPreviewKeyEvent true
            }
            when (event.key) {
                Key.Back -> {
                    if (controlsVisible) {
                        controlsVisible = false
                        seekBarFocused = false
                        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { focus.requestFocus() } }
                    } else {
                        onBack()
                    }
                    true
                }
                Key.Menu -> {
                    chatVisible = !chatVisible
                    controlsVisible = false
                    true
                }
                Key.DirectionDown -> {
                    if (!controlsVisible) {
                        showControls()
                        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { playFocus.requestFocus() } }
                        true
                    } else false
                }
                Key.DirectionUp -> {
                    if (controlsVisible) {
                        showControls()
                        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { seekFocus.requestFocus() } }
                    } else {
                        chatVisible = !chatVisible
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (controlsVisible) false
                    else {
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
            applySubtitleAppearance(this, subtitleAppearance)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } }, update = { view ->
            view.player = player.exoPlayer
            applySubtitleAppearance(view, subtitleAppearance)
        })

        TvChatOverlay(
            visible = chatVisible,
            messages = state.chatMessages,
            appSettings = if (chatEditing) appSettings.copy(chatFontSize = draftChatFontSize) else appSettings,
            appearance = if (chatEditing) draftChatAppearance else chatAppearance,
            editing = chatEditing,
            editMode = chatEditMode,
        )

        if (controlsVisible) {
            val fraction = if (playbackDuration > 0L) {
                (playbackPosition.toFloat() / playbackDuration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Column(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth(.94f)
                    .widthIn(max = 1_360.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = .72f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvExpressiveIconButton(
                        icon = ExpressiveIcons.Back,
                        contentDescription = "Exit video",
                        onClick = onBack,
                        style = TvButtonStyle.Tonal,
                        boxSize = 48.dp,
                        iconSize = 22.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(state.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$channelLogin  ·  VOD", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .76f))
                    }
                    Text("BACK  Exit", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.labelMedium)
                }
                (pickerMessage ?: state.subtitleMessage)?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.loading) Text("Loading video…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = c.error, style = MaterialTheme.typography.bodyMedium) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(.94f)
                    .widthIn(max = 1_360.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = .78f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playerTime(playbackPosition / 1_000), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(if (chatVisible) "CHAT ON" else "CHAT OFF", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelMedium)
                    Text("−${playerTime(((playbackDuration - playbackPosition).coerceAtLeast(0L)) / 1_000)}  /  ${playerTime(playbackDuration / 1_000)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                BoxWithConstraints(
                    Modifier.fillMaxWidth()
                        .height(40.dp)
                        .border(2.dp, if (seekBarFocused) c.primary else Color.White.copy(alpha = .24f), RoundedCornerShape(12.dp))
                        .focusRequester(seekFocus)
                        .onKeyEvent { seekEvent ->
                            if (seekEvent.type != KeyEventType.KeyDown || !seekBarFocused || playbackDuration <= 0L) return@onKeyEvent false
                            val current = (seekingFraction ?: fraction).coerceIn(0f, 1f)
                            val step = 10_000f / playbackDuration.toFloat()
                            val target = when (seekEvent.key) {
                                Key.DirectionLeft -> (current - step).coerceAtLeast(0f)
                                Key.DirectionRight -> (current + step).coerceAtMost(1f)
                                else -> return@onKeyEvent false
                            }
                            seekingFraction = target
                            viewModel.seekTo((playbackDuration * target).toLong())
                            showControls()
                            true
                        }
                        .onFocusChanged {
                            seekBarFocused = it.isFocused
                            if (!it.isFocused) seekingFraction = null
                        }
                        .focusable(),
                ) {
                    val trackWidth = (maxWidth - 32.dp).coerceAtLeast(0.dp)
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)
                            .fillMaxWidth().height(6.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = .28f)),
                    ) {
                        Box(Modifier.fillMaxWidth((seekingFraction ?: fraction).coerceIn(0f, 1f)).fillMaxHeight().background(c.primary))
                    }
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .offset(x = 16.dp + trackWidth * (seekingFraction ?: fraction).coerceIn(0f, 1f) - 9.dp)
                            .size(18.dp).clip(CircleShape)
                            .background(if (seekBarFocused) c.primary else Color.White),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    TvExpressiveButton("−1 min", { showControls(); viewModel.seekBy(-60_000) }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    TvExpressiveButton(if (isPlaying) "Pause" else "Play", { showControls(); player.togglePlayPause() }, style = TvButtonStyle.Filled, size = TvButtonSize.Medium, modifier = Modifier.focusRequester(playFocus))
                    TvExpressiveButton("+1 min", { showControls(); viewModel.seekBy(60_000) }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    TvExpressiveButton("Edit chat", ::beginChatEditing, style = TvButtonStyle.Tonal, size = TvButtonSize.Medium, icon = ExpressiveIcons.Chat)
                    TvExpressiveButton("Subtitles", ::openSubtitlePanel, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium, modifier = Modifier.focusRequester(subtitleFocus))
                }
                Text(
                    "DOWN controls  ·  UP seek bar / chat when hidden  ·  LEFT/RIGHT seek when selected  ·  BACK hide controls / exit",
                    color = Color.White.copy(alpha = .78f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        if (subtitlePanelVisible) {
            Dialog(onDismissRequest = { subtitlePanelVisible = false; showControls() }) {
                Column(
                    Modifier.fillMaxWidth(0.86f)
                        .widthIn(max = 860.dp)
                        .heightIn(max = 700.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xF0181A20))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Subtitles", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("Playback continues while this panel is open", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .72f))
                        }
                        TvExpressiveButton("Done", { subtitlePanelVisible = false; showControls() }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                    }

                    Text("TRACK", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .72f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvExpressiveButton(
                            if (state.subtitleTrackPreference == com.puretv.twitch.tv.player.TvPlayer.OFF_SUBTITLE_TRACK) "✓ Off" else "Off",
                            { viewModel.selectSubtitleTrack(com.puretv.twitch.tv.player.TvPlayer.OFF_SUBTITLE_TRACK) },
                            style = if (state.subtitleTrackPreference == com.puretv.twitch.tv.player.TvPlayer.OFF_SUBTITLE_TRACK) TvButtonStyle.Filled else TvButtonStyle.Outlined,
                            size = TvButtonSize.Medium,
                            modifier = Modifier.focusRequester(subtitlePanelFocus),
                        )
                        TvExpressiveButton(
                            if (state.subtitleTrackPreference == com.puretv.twitch.tv.player.TvPlayer.AUTO_SUBTITLE_TRACK) "✓ Auto" else "Auto",
                            { viewModel.selectSubtitleTrack(com.puretv.twitch.tv.player.TvPlayer.AUTO_SUBTITLE_TRACK) },
                            style = if (state.subtitleTrackPreference == com.puretv.twitch.tv.player.TvPlayer.AUTO_SUBTITLE_TRACK) TvButtonStyle.Filled else TvButtonStyle.Outlined,
                            size = TvButtonSize.Medium,
                        )
                    }
                    if (subtitleTracks.isEmpty()) {
                        Text("No supported subtitle tracks are available.", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        subtitleTracks.forEach { track ->
                            val selected = state.subtitleTrackPreference == track.key ||
                                (state.subtitleTrackPreference == com.puretv.twitch.tv.player.TvPlayer.AUTO_SUBTITLE_TRACK && track.selected)
                            val language = track.language?.let { " · $it" }.orEmpty()
                            TvExpressiveButton(
                                (if (selected) "✓ " else "") + track.label + language,
                                { viewModel.selectSubtitleTrack(track.key) },
                                style = if (selected) TvButtonStyle.Tonal else TvButtonStyle.Outlined,
                                size = TvButtonSize.Medium,
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvExpressiveButton(
                            "Load SRT",
                            {
                                subtitlePanelVisible = false
                                try {
                                    launcher.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream", "*/*"))
                                } catch (_: ActivityNotFoundException) {
                                    pickerMessage = "No file picker is available on this TV."
                                    showControls()
                                }
                            },
                            style = TvButtonStyle.Tonal,
                            size = TvButtonSize.Medium,
                        )
                        if (state.subtitleMessage?.contains("SRT subtitles loaded", ignoreCase = true) == true ||
                            state.subtitleMessage?.contains("Remembered SRT subtitles loaded", ignoreCase = true) == true
                        ) {
                            TvExpressiveButton("Clear SRT", { viewModel.clearSubtitle() }, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
                        }
                    }

                    Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .72f))
                    SubtitleSettingRow(
                        label = "Text size",
                        value = "${subtitleAppearance.sizeSp.toInt()} sp",
                        onDecrease = { updateSubtitleAppearance { it.copy(sizeSp = (it.sizeSp - 2f).coerceAtLeast(20f)) } },
                        onIncrease = { updateSubtitleAppearance { it.copy(sizeSp = (it.sizeSp + 2f).coerceAtMost(40f)) } },
                    )
                    SubtitleSettingRow(
                        label = "Text color",
                        value = subtitleAppearance.textColor.uppercase(),
                        onDecrease = { updateSubtitleAppearance { it.copy(textColor = subtitleColorStep(it.textColor, -1)) } },
                        onIncrease = { updateSubtitleAppearance { it.copy(textColor = subtitleColorStep(it.textColor, 1)) } },
                    )
                    SubtitleSettingRow(
                        label = "Background",
                        value = "${(subtitleAppearance.backgroundOpacity * 100).toInt()}%",
                        onDecrease = { updateSubtitleAppearance { it.copy(backgroundOpacity = (it.backgroundOpacity - 0.25f).coerceAtLeast(0f)) } },
                        onIncrease = { updateSubtitleAppearance { it.copy(backgroundOpacity = (it.backgroundOpacity + 0.25f).coerceAtMost(1f)) } },
                    )
                    SubtitleSettingRow(
                        label = "Vertical position",
                        value = "${(subtitleAppearance.bottomPaddingFraction * 100).toInt()}%",
                        onDecrease = { updateSubtitleAppearance { it.copy(bottomPaddingFraction = (it.bottomPaddingFraction - 0.05f).coerceAtLeast(0.05f)) } },
                        onIncrease = { updateSubtitleAppearance { it.copy(bottomPaddingFraction = (it.bottomPaddingFraction + 0.05f).coerceAtMost(0.25f)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleSettingRow(label: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TvExpressiveButton("−", onDecrease, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(92.dp))
        TvExpressiveButton("+", onIncrease, style = TvButtonStyle.Outlined, size = TvButtonSize.Medium)
    }
}

private fun subtitleColorStep(current: String, delta: Int): String {
    val colors = listOf("white", "yellow", "cyan")
    val index = colors.indexOf(current).let { if (it < 0) 0 else it }
    return colors[(index + delta + colors.size) % colors.size]
}

private fun applySubtitleAppearance(playerView: PlayerView, appearance: AppSettingsStore.SubtitleAppearance) {
    playerView.subtitleView?.apply {
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, appearance.sizeSp)
        val foreground = when (appearance.textColor) {
            "yellow" -> android.graphics.Color.YELLOW
            "cyan" -> android.graphics.Color.CYAN
            else -> android.graphics.Color.WHITE
        }
        setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                foreground,
                android.graphics.Color.argb((appearance.backgroundOpacity * 255).toInt(), 0, 0, 0),
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                android.graphics.Typeface.DEFAULT_BOLD,
            ),
        )
        setBottomPaddingFraction(appearance.bottomPaddingFraction)
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
