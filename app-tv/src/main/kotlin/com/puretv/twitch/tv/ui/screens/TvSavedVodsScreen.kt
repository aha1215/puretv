package com.puretv.twitch.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.tv.data.SavedVod
import com.puretv.twitch.tv.data.SmbVodDownloads
import com.puretv.twitch.tv.ui.components.ExpressiveIcons
import com.puretv.twitch.tv.ui.components.TvButtonSize
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.components.TvExpressiveIconButton
import com.puretv.twitch.tv.ui.components.TvPageTitle
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import org.koin.compose.koinInject

@Composable
fun TvSavedVodsScreen(
    onOpenVod: (SavedVod) -> Unit,
    onBack: () -> Unit,
    downloads: SmbVodDownloads = koinInject(),
) {
    val c = PureTvTvTheme.colors
    var videos by remember { mutableStateOf<List<SavedVod>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(downloads) {
        runCatching { downloads.savedVods() }
            .onSuccess { videos = it }
            .onFailure { error = it.message ?: "Couldn't read the SMB share" }
        loading = false
    }
    Column(
        Modifier.fillMaxSize().background(c.surface).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvExpressiveIconButton(ExpressiveIcons.Back, "Back", onBack)
            Column {
                TvPageTitle("Saved VODs")
                Text("${videos.size} saved on the SMB share", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        when {
            loading -> Text("Loading saved videos…", color = c.onSurfaceVariant)
            error != null -> Text(error!!, color = c.error)
            videos.isEmpty() -> Text("No saved VODs yet. Open a Twitch VOD and choose Save to SMB.", color = c.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(videos, key = { it.id }) { vod ->
                    TvExpressiveButton(
                        text = "${vod.title}  ·  ${vod.channelLogin}  ·  ${duration(vod.durationSeconds)}",
                        onClick = { onOpenVod(vod) },
                        style = TvButtonStyle.Tonal,
                        size = TvButtonSize.Large,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun duration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
