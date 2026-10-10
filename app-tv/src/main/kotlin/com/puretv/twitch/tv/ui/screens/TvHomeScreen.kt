package com.puretv.twitch.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.model.VideoInfo
import kotlinx.coroutines.launch
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.ui.HomeViewModel
import com.puretv.twitch.tv.ui.components.ExpressiveIcons
import com.puretv.twitch.tv.ui.components.TvButtonSize
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.components.TvFilterChip
import com.puretv.twitch.tv.ui.components.TvLivePill
import com.puretv.twitch.tv.ui.components.TvNavDestination
import com.puretv.twitch.tv.ui.components.TvNavDrawer
import com.puretv.twitch.tv.ui.components.TvSectionHeading
import com.puretv.twitch.tv.ui.components.TvShieldPill
import com.puretv.twitch.tv.ui.components.TvStreamCard
import com.puretv.twitch.tv.ui.components.tvFocusClickable
import com.puretv.twitch.tv.ui.components.formatTvViewerCount
import com.puretv.twitch.tv.ui.components.TvPanel
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType
import com.puretv.twitch.tv.update.TvUpdateManager
import com.puretv.twitch.tv.update.TvUpdateState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * SECTION 07.2 / 07.3 [CRITICAL] — landing screen: persistent left
 * [TvNavDrawer], followed and viewed VOD shelves, then live channel shelves.
 *
 * Launch focus stays on the selected Home navigation item while asynchronous
 * shelves load, keeping the page heading anchored at the top of the screen.
 * `onPreviewKeyEvent` on the content column intercepts DPAD_LEFT when focus
 * is already on the leftmost column and redirects it into the nav rail —
 * satisfying Section 7.3's rule "D-pad LEFT on the leftmost item opens the
 * nav drawer" without fighting Compose's default focus-search (which would
 * otherwise have nowhere to go).
 */
@Composable
fun TvHomeScreen(
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenVod: (AppSettingsStore.ViewedVod) -> Unit,
    onOpenLatestVod: (VideoInfo) -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val updateManager = koinInject<TvUpdateManager>()
    val updateState by updateManager.state.collectAsState()
    val c = PureTvTvTheme.colors
    val drawerFocusRequester = remember { FocusRequester() }
    val latestRowFocus = rememberHomeRowFocusTarget()
    val viewedRowFocus = rememberHomeRowFocusTarget()
    val followedLiveRowFocus = rememberHomeRowFocusTarget()
    val liveRowFocus = rememberHomeRowFocusTarget()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var homeContentFocusClaimed by remember { mutableStateOf(false) }
    val hasLatestRow = state.isLoggedIn && state.latestFollowedVods.isNotEmpty()
    val hasViewedRow = state.viewedVods.isNotEmpty()
    val hasFollowedLiveRow = state.followedLive.isNotEmpty()
    val hasLiveRow = state.topStreams.isNotEmpty()
    val initialRowFocusTarget = when {
        hasLatestRow -> latestRowFocus
        hasViewedRow -> viewedRowFocus
        hasFollowedLiveRow -> followedLiveRowFocus
        hasLiveRow -> liveRowFocus
        else -> null
    }

    fun focusFirstIn(target: HomeRowFocusTarget?) {
        if (target == null) return
        scope.launch {
            target.listState.scrollToItem(0)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { target.requester.requestFocus() }
        }
    }

    // Start on the first available video card, not on the navigation rail. Home
    // data arrives asynchronously, so wait until a real shelf card exists and
    // claim focus only once to avoid stealing it after the user starts browsing.
    LaunchedEffect(state.isLoggedIn, state.latestFollowedVods, state.viewedVods, state.followedLive, state.topStreams) {
        if (homeContentFocusClaimed) return@LaunchedEffect
        if (initialRowFocusTarget != null) {
            focusFirstIn(initialRowFocusTarget)
            homeContentFocusClaimed = true
        }
    }

    // Keep "Live Now" current. The TV app used to load top streams exactly once
    // at ViewModel creation, so a long-lived (Fire TV never kills the process)
    // session froze on the first snapshot. Refresh on every return to the
    // foreground and then poll while the screen stays resumed; the loop is
    // cancelled the moment the app is backgrounded so it never polls unseen.
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(HOME_REFRESH_INTERVAL_MS)
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(c.surface)) {
        TvNavDrawer(
            selected = TvNavDestination.HOME,
            isLoggedIn = state.isLoggedIn,
            onSelect = { dest ->
                when (dest) {
                    TvNavDestination.HOME -> Unit
                    TvNavDestination.FOLLOWING -> onOpenFollowing()
                    TvNavDestination.BROWSE -> onOpenBrowse()
                    TvNavDestination.SEARCH -> onOpenSearch()
                    TvNavDestination.SETTINGS -> onOpenSettings()
                    TvNavDestination.ACCOUNT -> if (state.isLoggedIn) onOpenSettings() else onOpenLogin()
                }
            },
            modifier = Modifier.focusRequester(drawerFocusRequester),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    // Section 7.3: DPAD LEFT on the leftmost column re-targets focus
                    // into the rail instead of being swallowed by Compose's focus search.
                    if (event.key == Key.DirectionLeft) {
                        // Only hand focus to the rail when there is genuinely
                        // nothing further left. Swallowing every LEFT press made
                        // it impossible to walk back along a shelf or a grid row,
                        // since the rail stole the very first one.
                        if (event.type == KeyEventType.KeyDown &&
                            !focusManager.moveFocus(FocusDirection.Left)
                        ) {
                            runCatching { drawerFocusRequester.requestFocus() }
                        }
                        true
                    } else {
                        false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LatestFollowedVodRow(
                vods = state.latestFollowedVods,
                loading = state.latestFollowedVodsLoading,
                isLoggedIn = state.isLoggedIn,
                onOpenVod = onOpenLatestVod,
                rowFocus = latestRowFocus,
                upAction = null,
                downAction = { focusFirstIn(if (hasViewedRow) viewedRowFocus else if (hasFollowedLiveRow) followedLiveRowFocus else liveRowFocus.takeIf { hasLiveRow }) },
            )
            ViewedVodRow(
                state.viewedVods,
                onOpenVod,
                rowFocus = viewedRowFocus,
                upAction = { focusFirstIn(latestRowFocus.takeIf { hasLatestRow }) },
                downAction = { focusFirstIn(if (hasFollowedLiveRow) followedLiveRowFocus else liveRowFocus.takeIf { hasLiveRow }) },
            )

            if (state.followedLive.isNotEmpty()) {
                ContentRow(
                    title = "Following · Live now",
                    streams = state.followedLive,
                    onOpenStream = onOpenStream,
                    rowFocus = followedLiveRowFocus,
                    upAction = { focusFirstIn(if (hasViewedRow) viewedRowFocus else latestRowFocus.takeIf { hasLatestRow }) },
                    downAction = { focusFirstIn(liveRowFocus.takeIf { hasLiveRow }) },
                )
            }
            ContentRow(
                title = "Live Channels",
                streams = state.topStreams,
                onOpenStream = onOpenStream,
                rowFocus = liveRowFocus,
                upAction = { focusFirstIn(if (hasFollowedLiveRow) followedLiveRowFocus else if (hasViewedRow) viewedRowFocus else latestRowFocus.takeIf { hasLatestRow }) },
                downAction = null,
            )

            // Keep update actions visible without pushing the followed VOD shelf
            // below the first screenful of Home.
            when (val u = updateState) {
                is TvUpdateState.Available -> u.info.versionName
                is TvUpdateState.NeedsInstallConsent -> u.info.versionName
                else -> null
            }?.let { versionName ->
                UpdateBanner(versionName = versionName, onOpenSettings = onOpenSettings)
            }

            // Only surfaces when there's genuinely nothing to show (no cache, no
            // network) — the cached-first paint keeps this hidden in the common case.
            if (state.topStreams.isEmpty() && !state.isLoading) {
                // A bare line of text reads as a rendering failure on a ten-foot
                // screen. Give the message the same panel every other block has,
                // so an empty Home still looks deliberate.
                TvPanel(modifier = Modifier.padding(horizontal = 48.dp)) {
                    Column {
                        Text(
                            text = if (state.error != null) "Could not load" else "Nothing live right now",
                            style = MaterialTheme.typography.headlineSmall,
                            color = c.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.error ?: "This refreshes on its own, so it will fill in as channels go live.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// Poll cadence for the foreground "Live Now" refresh. 90s keeps viewer counts /
// who's-live reasonably fresh without hammering Helix on a lean-back screen.
private const val HOME_REFRESH_INTERVAL_MS = 90_000L

/** Update-available banner: tertiary tonal strip, jumps to Settings to install. */
@Composable
private fun UpdateBanner(versionName: String, onOpenSettings: () -> Unit) {
    val c = PureTvTvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PureTvTvTheme.shapes.cardShape)
            .background(c.tertiaryContainer)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Update available: $versionName",
            style = MaterialTheme.typography.titleMedium,
            color = c.onTertiaryContainer,
        )
        TvExpressiveButton(
            text = "Open Settings",
            onClick = onOpenSettings,
            style = TvButtonStyle.FilledTertiary,
            size = TvButtonSize.Medium,
        )
    }
}

@Composable
private fun ContentRow(
    title: String,
    streams: List<StreamInfo>,
    onOpenStream: (String) -> Unit,
    rowFocus: HomeRowFocusTarget,
    upAction: (() -> Unit)?,
    downAction: (() -> Unit)?,
) {
    if (streams.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvSectionHeading(title)
        LazyRow(
            state = rowFocus.listState,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(streams, key = { _, stream -> stream.id }) { index, stream ->
                TvStreamCard(
                    stream = stream,
                    onClick = { onOpenStream(stream.userLogin) },
                    modifier = Modifier.homeRowNavigation(upAction, downAction).then(
                        if (index == 0) Modifier.focusRequester(rowFocus.requester) else Modifier,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ViewedVodRow(
    vods: List<AppSettingsStore.ViewedVod>,
    onOpenVod: (AppSettingsStore.ViewedVod) -> Unit,
    rowFocus: HomeRowFocusTarget,
    upAction: (() -> Unit)?,
    downAction: (() -> Unit)?,
) {
    if (vods.isEmpty()) return
    val c = PureTvTvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvSectionHeading("Viewed VODs")
        LazyRow(
            state = rowFocus.listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(vods, key = { _, vod -> vod.id }) { index, vod ->
                val interaction = remember(vod.id) { MutableInteractionSource() }
                Column(
                    Modifier
                        .homeRowNavigation(upAction, downAction)
                        .then(if (index == 0) Modifier.focusRequester(rowFocus.requester) else Modifier)
                        .width(300.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .tvFocusClickable(
                            interaction = interaction,
                            onClick = { onOpenVod(vod) },
                            restRadius = 18.dp,
                            focusRadius = 22.dp,
                            color = c.surfaceLow,
                            focusColor = c.surfaceHigh,
                            scale = 1.035f,
                        )
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer),
                    ) {
                        val thumbnail = templatedUrl(vod.thumbnailUrl, 640, 360)
                        if (thumbnail.isNotBlank()) {
                            AsyncImage(model = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Text(
                            text = viewedVodProgress(vod),
                            style = MaterialTheme.typography.labelLarge,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        )
                    }
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(vod.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(vod.channelLogin, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LatestFollowedVodRow(
    vods: List<VideoInfo>,
    loading: Boolean,
    isLoggedIn: Boolean,
    onOpenVod: (VideoInfo) -> Unit,
    rowFocus: HomeRowFocusTarget,
    upAction: (() -> Unit)?,
    downAction: (() -> Unit)?,
) {
    if (!isLoggedIn) return
    val c = PureTvTvTheme.colors
    var sortByViews by rememberSaveable { mutableStateOf(false) }
    val sortedVods = remember(vods, sortByViews) {
        if (sortByViews) vods.sortedByDescending(VideoInfo::viewCount)
        else vods.sortedByDescending(VideoInfo::createdAt)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvSectionHeading("Latest from channels you follow", modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvFilterChip("Recent", selected = !sortByViews, onClick = { sortByViews = false })
                TvFilterChip("Most viewed", selected = sortByViews, onClick = { sortByViews = true })
            }
        }
        if (vods.isEmpty()) {
            Text(
                text = if (loading) "Loading recent videos…" else "No recent broadcasts to show",
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            return@Column
        }
        LazyRow(
            state = rowFocus.listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(sortedVods, key = { _, vod -> vod.id }) { index, vod ->
                val interaction = remember(vod.id) { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .homeRowNavigation(upAction, downAction)
                        .then(if (index == 0) Modifier.focusRequester(rowFocus.requester) else Modifier)
                        .width(300.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .tvFocusClickable(
                            interaction = interaction,
                            onClick = { onOpenVod(vod) },
                            restRadius = 18.dp,
                            focusRadius = 22.dp,
                            color = c.surfaceLow,
                            focusColor = c.surfaceHigh,
                            scale = 1.035f,
                        )
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(169.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceContainer))),
                    ) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(c.surfaceHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    ExpressiveIcons.Play,
                                    contentDescription = null,
                                    tint = c.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("PAST BROADCAST", style = PureTvTvType.kicker, color = c.onSurfaceVariant)
                                Text(
                                    vod.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = c.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val thumbnail = templatedUrl(vod.thumbnailUrl, 640, 360)
                        if (thumbnail.isNotBlank() && !thumbnail.contains("/_404/", ignoreCase = true)) {
                            AsyncImage(
                                model = thumbnail,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).background(c.bottomScrim),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${formatTvViewerCount(vod.viewCount.toLong())} views",
                                    style = PureTvTvType.dataSmall,
                                    color = androidx.compose.ui.graphics.Color.White,
                                )
                                Text(
                                    videoAgeLabel(vod.createdAt),
                                    style = PureTvTvType.dataSmall,
                                    color = androidx.compose.ui.graphics.Color.White,
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            vod.title.ifBlank { "Past broadcast" },
                            style = MaterialTheme.typography.titleMedium,
                            color = c.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            vod.userName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun videoAgeLabel(createdAt: String, now: Instant = Instant.now()): String {
    val created = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return "Release date unavailable"
    val seconds = Duration.between(created, now).seconds.coerceAtLeast(0L)
    return when {
        seconds < 60L -> "Just now"
        seconds < 3_600L -> "${seconds / 60L}m ago"
        seconds < 86_400L -> "${seconds / 3_600L}h ago"
        seconds < 604_800L -> "${seconds / 86_400L}d ago"
        seconds < 2_592_000L -> "${seconds / 604_800L}w ago"
        seconds < 31_536_000L -> "${seconds / 2_592_000L}mo ago"
        else -> "${seconds / 31_536_000L}y ago"
    }
}

private fun viewedVodProgress(vod: AppSettingsStore.ViewedVod): String {
    if (vod.durationSeconds > 0 && vod.positionMs >= vod.durationSeconds * 950L) return "Watched"
    val totalSeconds = (vod.positionMs / 1_000L).coerceAtLeast(0L)
    if (totalSeconds == 0L) return "Viewed"

    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val timecode = if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
    return "Continue · $timecode"
}

/** Twitch thumbnail URLs are templates carrying `{width}`/`{height}`. */
private fun templatedUrl(url: String, width: Int, height: Int): String =
    url
        .replace("%7Bwidth%7D", width.toString(), ignoreCase = true)
        .replace("%7Bheight%7D", height.toString(), ignoreCase = true)
        .replace("{{width}}", width.toString())
        .replace("{{height}}", height.toString())
        .replace("%{width}", width.toString())
        .replace("%{height}", height.toString())
        .replace("{width}", width.toString())
        .replace("{height}", height.toString())

private class HomeRowFocusTarget(
    val requester: FocusRequester,
    val listState: LazyListState,
)

@Composable
private fun rememberHomeRowFocusTarget(): HomeRowFocusTarget = HomeRowFocusTarget(
    requester = remember { FocusRequester() },
    listState = rememberLazyListState(),
)

private fun Modifier.homeRowNavigation(
    upAction: (() -> Unit)?,
    downAction: (() -> Unit)?,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionUp -> upAction?.let { it(); true } ?: false
        Key.DirectionDown -> downAction?.let { it(); true } ?: false
        else -> false
    }
}
