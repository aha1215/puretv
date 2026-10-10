package com.puretv.twitch.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun TvDownloadsScreen(
    onBack: () -> Unit,
    onOpenSavedVods: (() -> Unit)? = null,
    onPlaySavedVod: (() -> Unit)? = null,
    downloads: SmbVodDownloads = koinInject(),
) {
    val state by downloads.downloadState.collectAsState()
    val c = PureTvTvTheme.colors
    var savedCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(downloads) {
        downloads.refreshShareSpace()
        savedCount = runCatching { downloads.savedVods().size }.getOrNull()
    }

    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TvExpressiveIconButton(ExpressiveIcons.Back, "Back to video", onBack)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TvPageTitle("Downloads")
                Text("VOD transfers continue while you browse.", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(PureTvTvTheme.shapes.cardShape).background(c.surfaceContainer).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val video = state.video
            Text(if (video == null) "Current download" else video.title, color = c.onSurface, style = MaterialTheme.typography.titleLarge)
            if (video != null) Text(video.userLogin, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

            when (state.status) {
                SmbVodDownloads.DownloadStatus.IDLE -> Text("No download has been started yet.", color = c.onSurfaceVariant)
                SmbVodDownloads.DownloadStatus.RUNNING,
                SmbVodDownloads.DownloadStatus.CANCELLING -> {
                    val progress = state.progress
                    Text(
                        progress?.let { describeProgress(it) } ?: "Preparing download…",
                        color = c.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    progress?.fraction?.let { fraction ->
                        Box(
                            Modifier.fillMaxWidth().height(12.dp).clip(PureTvTvTheme.shapes.pillShape)
                                .background(c.surfaceHigh),
                        ) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxSize().clip(PureTvTvTheme.shapes.pillShape).background(c.primary))
                        }
                        Text("${(fraction * 100f).toInt()}%", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    progress?.let { details ->
                        val total = details.estimatedTotalBytes
                        val sizeLine = if (total != null) {
                            "${formatBytes(details.bytesTransferred)} / ~${formatBytes(total)}"
                        } else if (details.bytesTransferred > 0L) {
                            "${formatBytes(details.bytesTransferred)} transferred"
                        } else null
                        sizeLine?.let { Text(it, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                        details.bytesPerSecond?.let {
                            Text("${formatBytes(it)}/s", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.status == SmbVodDownloads.DownloadStatus.CANCELLING) {
                        Text("Cancelling safely…", color = c.onSurfaceVariant)
                    } else {
                        TvExpressiveButton(
                            "Cancel download",
                            onClick = downloads::cancelDownload,
                            style = TvButtonStyle.Outlined,
                            size = TvButtonSize.Medium,
                            icon = ExpressiveIcons.Close,
                        )
                    }
                }
                SmbVodDownloads.DownloadStatus.COMPLETE -> {
                    Text("Saved to the SMB share", color = c.primary, style = MaterialTheme.typography.titleMedium)
                    state.savedBytes?.let { Text("Video size · ${formatBytes(it)}", color = c.onSurfaceVariant) }
                }
                SmbVodDownloads.DownloadStatus.FAILED -> Text(state.error ?: "The download failed.", color = c.error)
                SmbVodDownloads.DownloadStatus.CANCELLED -> Text("Download cancelled. Any incomplete transfer was removed.", color = c.onSurfaceVariant)
            }
            state.error?.takeIf { state.status != SmbVodDownloads.DownloadStatus.FAILED }?.let {
                Text(it, color = c.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(PureTvTvTheme.shapes.cardShape).background(c.surfaceContainer).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SMB storage", color = c.onSurface, style = MaterialTheme.typography.titleMedium)
            when {
                state.shareSpaceLoading -> Text("Reading share capacity…", color = c.onSurfaceVariant)
                state.shareSpace != null -> {
                    val space = requireNotNull(state.shareSpace)
                    Text(
                        "${formatBytes(space.usedBytes)} used · ${formatBytes(space.freeBytes)} free · ${formatBytes(space.totalBytes)} total",
                        color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.shareSpaceError != null -> Text(requireNotNull(state.shareSpaceError), color = c.onSurfaceVariant)
                else -> Text("Share capacity unavailable", color = c.onSurfaceVariant)
            }
            savedCount?.let { count ->
                Text("$count saved VOD${if (count == 1) "" else "s"}", color = c.onSurfaceVariant)
            }
            if (state.status == SmbVodDownloads.DownloadStatus.COMPLETE && onPlaySavedVod != null) {
                TvExpressiveButton(
                    "Play saved VOD",
                    onClick = onPlaySavedVod,
                    style = TvButtonStyle.Filled,
                    size = TvButtonSize.Medium,
                    icon = ExpressiveIcons.Play,
                )
            }
            onOpenSavedVods?.let { openSavedVods ->
                TvExpressiveButton(
                    "Open saved VODs",
                    onClick = openSavedVods,
                    style = TvButtonStyle.Tonal,
                    size = TvButtonSize.Medium,
                )
            }
        }
    }
}

private fun describeProgress(progress: SmbVodDownloads.Progress): String {
    val item = when (progress.phase) {
        "Downloading video" -> "${progress.completed}/${progress.total} segments"
        "Translating chat" -> "${progress.completed}/${progress.total} messages"
        else -> progress.total.takeIf { it > 0 }?.let { "${progress.completed}/$it" }
    }
    return listOfNotNull(progress.phase, item).joinToString(" · ")
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}
