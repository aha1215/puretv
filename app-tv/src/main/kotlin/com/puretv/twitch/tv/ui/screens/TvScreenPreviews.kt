package com.puretv.twitch.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.core.model.GameInfo
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.tv.ui.components.ExpressiveIcons
import com.puretv.twitch.tv.ui.components.TvButtonSize
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.components.TvFilterChip
import com.puretv.twitch.tv.ui.components.TvGameCard
import com.puretv.twitch.tv.ui.components.TvLivePill
import com.puretv.twitch.tv.ui.components.TvNavDestination
import com.puretv.twitch.tv.ui.components.TvNavDrawer
import com.puretv.twitch.tv.ui.components.TvPageTitle
import com.puretv.twitch.tv.ui.components.TvPanel
import com.puretv.twitch.tv.ui.components.TvSectionHeading
import com.puretv.twitch.tv.ui.components.TvStreamCard
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme

private val previewStreams = listOf(
    StreamInfo(
        id = "preview-1", userId = "1001", userLogin = "pixelpilot", userName = "PixelPilot",
        gameId = "509658", gameName = "Just Chatting", title = "Cozy late night stream",
        viewerCount = 12_400, startedAt = "2026-10-09T18:30:00Z",
    ),
    StreamInfo(
        id = "preview-2", userId = "1002", userLogin = "orbitplays", userName = "OrbitPlays",
        gameId = "516575", gameName = "VALORANT", title = "Ranked climb continues",
        viewerCount = 8_250, startedAt = "2026-10-09T17:45:00Z",
    ),
    StreamInfo(
        id = "preview-3", userId = "1003", userLogin = "mossyquest", userName = "MossyQuest",
        gameId = "27471", gameName = "Minecraft", title = "Building a tiny village",
        viewerCount = 3_180, startedAt = "2026-10-09T16:10:00Z",
    ),
)

private val previewGames = listOf(
    GameInfo("509658", "Just Chatting"),
    GameInfo("516575", "VALORANT"),
    GameInfo("27471", "Minecraft"),
    GameInfo("33214", "Fortnite"),
)

/** A 1080p TV canvas with the real app theme and navigation rail. */
@Composable
private fun TvScreenPreviewFrame(
    selected: TvNavDestination,
    title: String,
    content: @Composable () -> Unit,
) {
    PureTvTvTheme {
        Row(Modifier.fillMaxSize().background(PureTvTvTheme.colors.surface)) {
            TvNavDrawer(
                selected = selected,
                isLoggedIn = true,
                onSelect = {},
                modifier = Modifier.fillMaxHeight(),
            )
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, top = 26.dp, end = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TvPageTitle(title)
                content()
            }
        }
    }
}

@Composable
private fun PreviewStreamShelf(title: String, streams: List<StreamInfo> = previewStreams) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSectionHeading(title)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            streams.forEach { TvStreamCard(it, onClick = {}) }
        }
    }
}

@Composable
private fun PreviewVideoTile(title: String, creator: String, modifier: Modifier = Modifier) {
    val colors = PureTvTvTheme.colors
    Column(modifier.width(270.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("VOD THUMBNAIL", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(creator, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "Home · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvHomeScreenPreview() = TvScreenPreviewFrame(TvNavDestination.HOME, "Home") {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvFilterChip("For you", selected = true, onClick = {})
        TvFilterChip("Following", selected = false, onClick = {})
    }
    PreviewStreamShelf("Live now")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSectionHeading("Latest from channels you follow")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PreviewVideoTile("The new season starts today", "PixelPilot")
            PreviewVideoTile("Building the ultimate base", "MossyQuest")
            PreviewVideoTile("Weekend ranked session", "OrbitPlays")
        }
    }
}

@Preview(name = "Following · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvFollowingScreenPreview() = TvScreenPreviewFrame(TvNavDestination.FOLLOWING, "Following") {
    PreviewStreamShelf("Live now", previewStreams.take(2))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSectionHeading("Offline")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("LunaBuilds", "RetroReplay", "TeaAndTactics").forEach { name ->
                TvPanel(Modifier.width(300.dp), padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(PureTvTvTheme.colors.primary))
                        Column {
                            Text(name, color = PureTvTvTheme.colors.onSurface, style = MaterialTheme.typography.titleMedium)
                            Text("Offline", color = PureTvTvTheme.colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Browse · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvBrowseScreenPreview() = TvScreenPreviewFrame(TvNavDestination.BROWSE, "Browse categories") {
    Text("Popular games and categories", color = PureTvTvTheme.colors.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        previewGames.forEach { TvGameCard(it, onClick = {}) }
    }
}

@Preview(name = "Category · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvCategoryScreenPreview() = TvScreenPreviewFrame(TvNavDestination.BROWSE, "VALORANT") {
    Text("Live channels playing VALORANT", color = PureTvTvTheme.colors.onSurfaceVariant)
    PreviewStreamShelf("Top live channels")
}

@Preview(name = "Search · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvSearchScreenPreview() = TvScreenPreviewFrame(TvNavDestination.SEARCH, "Search") {
    TvPanel(padding = 18.dp) {
        Text("Search channels  ·  pixel", color = PureTvTvTheme.colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
    listOf("PixelPilot", "PixelPlays", "PixelArtDaily").forEachIndexed { index, name ->
        TvPanel(padding = 18.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(PureTvTvTheme.colors.surfaceHigh))
                Column(Modifier.weight(1f)) {
                    Text(name, color = PureTvTvTheme.colors.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text(if (index == 0) "Just Chatting" else "Offline", color = PureTvTvTheme.colors.onSurfaceVariant)
                }
                if (index == 0) TvLivePill()
            }
        }
    }
}

@Preview(name = "Channel · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvChannelScreenPreview() = TvScreenPreviewFrame(TvNavDestination.HOME, "PixelPilot") {
    TvPanel(Modifier.fillMaxWidth().height(220.dp), padding = 28.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(PureTvTvTheme.colors.primary))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PixelPilot", style = MaterialTheme.typography.headlineLarge, color = PureTvTvTheme.colors.onSurface)
                TvLivePill(trailing = "12.4K viewers")
                Text("Live now · Just Chatting", color = PureTvTvTheme.colors.onSurfaceVariant)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvExpressiveButton("Watch now", {}, style = TvButtonStyle.Filled, size = TvButtonSize.Large, icon = ExpressiveIcons.Play)
        TvExpressiveButton("Past videos", {}, style = TvButtonStyle.Outlined)
    }
    TvPanel { Text("About this channel", color = PureTvTvTheme.colors.onSurface) }
}

@Preview(name = "Sign in · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvLoginScreenPreview() = TvScreenPreviewFrame(TvNavDestination.ACCOUNT, "Sign in") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Sign in once, then just watch.", style = MaterialTheme.typography.headlineLarge, color = PureTvTvTheme.colors.onSurface)
            Text("Approve access on Twitch. Your password stays on Twitch's own sign-in page.", color = PureTvTvTheme.colors.onSurfaceVariant)
            TvExpressiveButton("Get a new code", {}, style = TvButtonStyle.Outlined, icon = ExpressiveIcons.Refresh)
        }
        TvPanel(Modifier.width(350.dp), padding = 28.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Go to twitch.tv/activate", color = PureTvTvTheme.colors.onSurfaceVariant)
                Text("ABCD-EFGH", style = MaterialTheme.typography.headlineLarge, color = PureTvTvTheme.colors.onSurface)
                TvExpressiveButton("Open activation page", {}, size = TvButtonSize.Medium)
            }
        }
    }
}

@Preview(name = "Settings · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvSettingsScreenPreview() = TvScreenPreviewFrame(TvNavDestination.SETTINGS, "Settings") {
    listOf(
        "Playback quality" to "Auto · best available",
        "VOD seek previews" to "Standard storyboard",
        "Ad blocking" to "Enabled · proxy mode",
        "Chat overlay" to "Enabled · 14 sp",
        "Theme" to "Violet Dusk",
    ).forEach { (label, value) ->
        TvPanel(padding = 18.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = PureTvTvTheme.colors.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(value, color = PureTvTvTheme.colors.onSurfaceVariant)
            }
        }
    }
}

@Preview(name = "Live player · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvStreamScreenPreview() = TvScreenPreviewFrame(TvNavDestination.HOME, "Live stream") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF15131C)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvLivePill(trailing = "12.4K")
                Text("Live video preview", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
        TvPanel(Modifier.width(360.dp).height(500.dp), padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvSectionHeading("Chat")
                listOf("That clutch was unreal", "Let's go!", "Welcome in 👋", "Great stream today").forEach {
                    Text(it, color = PureTvTvTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvExpressiveButton("Pause", {}, icon = ExpressiveIcons.Pause)
        TvExpressiveButton("Quality · Source", {}, style = TvButtonStyle.Tonal)
        TvExpressiveButton("Chat", {}, style = TvButtonStyle.Outlined)
    }
}

@Preview(name = "Past videos · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvVodListScreenPreview() = TvScreenPreviewFrame(TvNavDestination.HOME, "Past videos") {
    Text("PixelPilot · use the D-pad to choose", color = PureTvTvTheme.colors.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PreviewVideoTile("The new season starts today", "3h 12m · 42K views")
        PreviewVideoTile("Late night community games", "2h 48m · 31K views")
        PreviewVideoTile("A very close final round", "1h 55m · 26K views")
    }
}

@Preview(name = "VOD player · Android TV 1080p", device = "spec:width=1920dp,height=1080dp,dpi=240", showBackground = true)
@Composable
private fun TvVodPlayerScreenPreview() = TvScreenPreviewFrame(TvNavDestination.HOME, "The new season starts today") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF15131C)), contentAlignment = Alignment.Center) {
            Text("VOD playback surface", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
        TvPanel(Modifier.width(330.dp).height(460.dp), padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvSectionHeading("Replay chat")
                listOf("Welcome back!", "That was a great round", "GGs", "Clip that moment").forEach {
                    Text(it, color = PureTvTvTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvExpressiveButton("Play", {}, icon = ExpressiveIcons.Play)
        TvExpressiveButton("-30 sec", {}, style = TvButtonStyle.Tonal)
        TvExpressiveButton("+30 sec", {}, style = TvButtonStyle.Tonal)
        TvFilterChip("Chat visible", selected = true, onClick = {})
    }
}
