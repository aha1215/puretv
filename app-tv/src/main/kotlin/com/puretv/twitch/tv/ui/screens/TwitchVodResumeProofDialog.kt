package com.puretv.twitch.tv.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.stream.VodPlaybackHistory
import com.puretv.twitch.core.stream.VodPlaybackHistoryEntry
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TWITCH_LOGIN_URL = "https://www.twitch.tv/login"
private const val TWITCH_COOKIE_URL = "https://www.twitch.tv"

private data class ResumeProofReport(
    val history: VodPlaybackHistory,
    val gilticusVods: List<GilticusResumeEntry>,
)

private data class GilticusResumeEntry(
    val checkpoint: VodPlaybackHistoryEntry,
    val title: String,
    val durationSeconds: Long,
)

/**
 * On-device feasibility check for reading Twitch's private website resume history.
 * The extracted cookie is never logged or sent anywhere except Twitch's GraphQL
 * endpoint, and is saved only after a successful history response for the app's
 * currently signed-in account.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TwitchVodResumeProofDialog(
    settingsStore: AppSettingsStore,
    vodRepository: VodRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = PureTvTvTheme.colors
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign into Twitch in this TV window, then check the website history.") }
    var report by remember { mutableStateOf<ResumeProofReport?>(null) }
    var activeToken by remember { mutableStateOf("") }

    fun testHistory(token: String, websiteSession: Boolean) {
        activeToken = token
        loading = true
        status = "Checking Twitch account and VOD history…"
        scope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    vodRepository.playbackHistory(token).getOrThrow()
                }
                val expectedUserId = settingsStore.currentUserId()?.takeIf(String::isNotBlank)
                if (history.userId.isNullOrBlank()) {
                    status = "Twitch returned history without an account ID. The session was not saved."
                    report = null
                    return@launch
                }
                if (expectedUserId != null && history.userId != expectedUserId) {
                    if (websiteSession) settingsStore.clearWebResumeToken()
                    status = "Website account ${history.userId} does not match the TV app account $expectedUserId. Switch accounts and try again."
                    report = null
                    return@launch
                }
                val videos = withContext(Dispatchers.IO) {
                    runCatching { vodRepository.videosByIds(history.entries.map { it.vodId }.distinct()) }
                        .getOrDefault(emptyList())
                }
                val videosById = videos.associateBy { it.id }
                val gilticus = history.entries.mapNotNull { entry ->
                    val video = videosById[entry.vodId] ?: return@mapNotNull null
                    if (!video.userLogin.equals("gilticus", ignoreCase = true)) return@mapNotNull null
                    GilticusResumeEntry(entry, video.title, video.durationSeconds)
                }
                if (websiteSession) settingsStore.saveWebResumeToken(token, history.userId)
                report = ResumeProofReport(history, gilticus)
                status = if (websiteSession) {
                    "Website history query succeeded for Twitch user ${history.userId}; the session is saved securely on this TV."
                } else {
                    "TV app OAuth history query succeeded for Twitch user ${history.userId}. This token was not saved as a website session."
                }
            } catch (error: Exception) {
                status = "History check failed: ${error.message ?: error::class.simpleName ?: "unknown error"}. The session was not saved."
                report = null
            } finally {
                loading = false
            }
        }
    }

    fun checkBrowserCookie() {
        val rawCookie = CookieManager.getInstance().getCookie(TWITCH_COOKIE_URL).orEmpty()
        val token = rawCookie.split(';')
            .asSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("auth-token=") }
            ?.substringAfter('=')
            ?.let(Uri::decode)
            ?.takeIf { it.length in 20..512 && it.none(Char::isWhitespace) }
        if (token == null) {
            status = "No Twitch auth-token cookie found yet. Complete sign-in in the page, then try again."
            return
        }
        testHistory(token, websiteSession = true)
    }

    fun testSavedSession() {
        val token = settingsStore.webResumeToken()?.takeIf(String::isNotBlank)
        if (token == null) {
            status = "There is no saved TV website session to test."
            return
        }
        testHistory(token, websiteSession = false)
    }

    fun testAppOAuth() {
        scope.launch {
            val token = settingsStore.flow.first().accessToken.takeIf(String::isNotBlank)
            if (token == null) {
                status = "The TV app is signed out. Sign into the app first, then test its OAuth history access."
                return@launch
            }
            testHistory(token, websiteSession = false)
        }
    }

    fun verifyWrite() {
        val currentReport = report ?: return
        val gilticusVod = currentReport.gilticusVods.firstOrNull()
        if (gilticusVod == null || activeToken.isBlank()) {
            status = "The recent history did not include a Gilticus VOD to use for the write check."
            return
        }
        val currentSeconds = gilticusVod.checkpoint.positionMs / 1_000L
        val durationSeconds = gilticusVod.durationSeconds
        val nextSeconds = if (durationSeconds > 0L) minOf(currentSeconds + 1L, durationSeconds - 1L) else currentSeconds + 1L
        if (nextSeconds <= currentSeconds) {
            status = "This VOD is already at its end, so there is no valid forward checkpoint to test."
            return
        }
        loading = true
        status = "Testing the Twitch checkpoint write by advancing this VOD by one second…"
        scope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) {
                    vodRepository.updatePlaybackPosition(
                        vodId = gilticusVod.checkpoint.vodId,
                        userId = currentReport.history.userId.orEmpty(),
                        positionSeconds = nextSeconds,
                        oauthToken = activeToken,
                    )
                }
                if (!accepted) {
                    status = "Twitch did not accept the checkpoint write."
                    return@launch
                }
                val refreshed = withContext(Dispatchers.IO) {
                    vodRepository.playbackHistory(activeToken).getOrThrow()
                }
                val readBack = refreshed.entries.firstOrNull { it.vodId == gilticusVod.checkpoint.vodId }?.positionMs ?: 0L
                status = if (readBack >= nextSeconds * 1_000L) {
                    "Checkpoint write and read-back succeeded at ${readBack / 1_000L}s."
                } else {
                    "Twitch accepted the write, but the follow-up history read returned ${readBack / 1_000L}s."
                }
            } catch (error: Exception) {
                status = "Checkpoint write check failed: ${error.message ?: error::class.simpleName ?: "unknown error"}."
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 1_100.dp)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surfaceHigh)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                Text("Test Twitch VOD resume on this TV", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Text(
                    "This uses Twitch's private website history query. The TV app does not use a browser extension. Sign into the Twitch website below, then verify account, Gilticus checkpoint, and (optionally) a one-second write/read-back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { viewContext ->
                        WebView(viewContext).also { view ->
                            webView = view
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.loadsImagesAutomatically = true
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(view, true)
                            }
                            view.webChromeClient = WebChromeClient()
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url?.startsWith("https://www.twitch.tv") == true) {
                                        status = "Twitch page loaded. Sign in, then check the website history below."
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        status = "Twitch login page could not load: ${error?.description ?: "network error"}."
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                                        status = "Twitch login page returned HTTP ${errorResponse?.statusCode}."
                                    }
                                }
                            }
                            view.loadUrl(TWITCH_LOGIN_URL)
                        }
                    },
                )
                Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                report?.let { proof ->
                    Text(
                        "Twitch user ${proof.history.userId} · ${proof.history.entries.size} history entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primary,
                    )
                    if (proof.gilticusVods.isEmpty()) {
                        Text("No Gilticus VOD was found in the returned recent history.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                        proof.gilticusVods.take(3).forEach { vod ->
                            Text("${vod.title} · ${vod.checkpoint.positionMs / 1_000L}s · VOD ${vod.checkpoint.vodId}", style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) Text("Working…", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                    TvExpressiveButton(
                        text = "Test TV app OAuth",
                        onClick = ::testAppOAuth,
                        enabled = !loading,
                        style = TvButtonStyle.Outlined,
                    )
                    TvExpressiveButton(
                        text = "Check signed-in website",
                        onClick = ::checkBrowserCookie,
                        enabled = !loading,
                    )
                    TvExpressiveButton(
                        text = "Test saved session",
                        onClick = ::testSavedSession,
                        enabled = !loading && !settingsStore.webResumeToken().isNullOrBlank(),
                        style = TvButtonStyle.Outlined,
                    )
                    if (report?.gilticusVods?.isNotEmpty() == true) {
                        TvExpressiveButton(
                            text = "Verify write +1s",
                            onClick = ::verifyWrite,
                            enabled = !loading,
                            style = TvButtonStyle.Outlined,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TvExpressiveButton(text = "Close", onClick = onDismiss, style = TvButtonStyle.Outlined)
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}

internal fun clearTwitchWebsiteCookies() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
}

