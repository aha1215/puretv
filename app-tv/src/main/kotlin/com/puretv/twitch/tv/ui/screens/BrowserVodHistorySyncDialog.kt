package com.puretv.twitch.tv.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.tv.data.BrowserVodSyncServer
import com.puretv.twitch.tv.ui.QrCode
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import kotlinx.coroutines.launch

@Composable
internal fun BrowserVodHistorySyncDialog(
    server: BrowserVodSyncServer,
    onDismiss: () -> Unit,
) {
    val colors = PureTvTvTheme.colors
    val scope = rememberCoroutineScope()
    val pairing by server.historyPairing.collectAsState()
    val status by server.status.collectAsState()
    val addresses = remember { server.localIpv4Addresses() }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) {
            server.beginBrowserHistoryPairing()
            started = true
        }
    }
    DisposableEffect(pairing?.code) {
        val code = pairing?.code
        onDispose {
            if (code != null) scope.launch { server.cancelBrowserHistoryPairing(code) }
        }
    }

    val setupUrl = pairing?.let { session ->
        addresses.firstOrNull()?.let { ip -> "http://$ip:47821/v1/browser-history/setup?code=${session.code}" }
    }
    val qr = remember(setupUrl) { setupUrl?.let { QrCode.generate(it, sizePx = 420) } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 640.dp, max = 900.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surfaceContainer)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sync Twitch browser history", style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (qr != null) {
                    Image(bitmap = qr, contentDescription = "Phone setup QR code", modifier = Modifier.size(250.dp))
                } else {
                    Text("No local network address is available. Connect the TV to Wi-Fi and retry.", color = colors.error)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Scan this QR code with Android Chrome.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    Text("2. Copy the one-time bookmarklet into a Chrome bookmark.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    Text("3. Open Twitch, sign in, request Desktop site, then run the bookmarklet.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    Text("Only VOD IDs and positions are sent to the TV. The pairing expires after 15 minutes.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    if (setupUrl != null) Text(setupUrl, style = MaterialTheme.typography.bodySmall, color = colors.primary)
                    if (pairing != null && !status.running && status.error != null) {
                        Text(status.error!!, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                    }
                    if (status.lastBrowserHistoryImportAtMs != null) {
                        Text(
                            "Received ${status.lastBrowserHistoryImportCount ?: 0} VODs. Check the Viewed VODs shelf.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvExpressiveButton(
                    text = "New pairing code",
                    onClick = { server.beginBrowserHistoryPairing() },
                    style = TvButtonStyle.Outlined,
                )
                TvExpressiveButton(text = "Done", onClick = onDismiss)
            }
        }
    }
}
