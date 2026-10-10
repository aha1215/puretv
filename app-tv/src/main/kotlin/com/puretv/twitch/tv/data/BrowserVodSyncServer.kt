package com.puretv.twitch.tv.data

import android.util.Log
import com.puretv.twitch.core.api.TwitchConfig
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.stream.RESUME_WATCHING_VIDEOS_QUERY
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Receives VOD checkpoints and account-scoped history from the user's paired Twitch browser over the LAN. */
class BrowserVodSyncServer(
    private val settings: AppSettingsStore,
    private val vodRepository: VodRepository,
) {
    data class Status(
        val running: Boolean = false,
        val error: String? = null,
        val lastSyncAtMs: Long? = null,
        val receivedCount: Int = 0,
        val lastBrowserHistoryImportAtMs: Long? = null,
        val lastBrowserHistoryImportCount: Int? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private var configJob: kotlinx.coroutines.Job? = null
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val webSessionChallenges = ConcurrentHashMap<String, ChallengeState>()
    data class HistoryPairing(val code: String, val expiresAtMs: Long)
    private val _historyPairing = MutableStateFlow<HistoryPairing?>(null)
    val historyPairing: StateFlow<HistoryPairing?> = _historyPairing.asStateFlow()
    private var pairingExpiryJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (configJob?.isActive == true) return
        configJob = scope.launch {
            settings.browserVodSync
                .map { it.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) startServer()
                    else if (_historyPairing.value == null) stopServer()
                }
        }
    }

    fun beginBrowserHistoryPairing(): HistoryPairing {
        val pairing = HistoryPairing(createHistoryPairingCode(), System.currentTimeMillis() + HISTORY_PAIRING_TTL_MS)
        pairingExpiryJob?.cancel()
        _historyPairing.value = pairing
        pairingExpiryJob = scope.launch {
            delay(HISTORY_PAIRING_TTL_MS)
            if (_historyPairing.value == pairing) {
                _historyPairing.value = null
                if (!settings.browserVodSync.first().enabled) stopServer()
            }
        }
        scope.launch { startServer() }
        return pairing
    }

    suspend fun cancelBrowserHistoryPairing(code: String? = null) {
        val current = _historyPairing.value ?: return
        if (code != null && current.code != code) return
        _historyPairing.value = null
        pairingExpiryJob?.cancel()
        pairingExpiryJob = null
        if (!settings.browserVodSync.first().enabled) stopServer()
    }

    fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { address -> !address.isLoopbackAddress && address.isSiteLocalAddress }
            .map { address -> address.hostAddress.orEmpty() }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private suspend fun startServer() = lifecycleMutex.withLock {
        if (engine != null) return@withLock
        val candidate = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
            routing {
                options(POSITION_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@options
                    }
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    call.respond(HttpStatusCode.NoContent)
                }

                options(WEB_SESSION_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@options
                    }
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type")
                    call.respond(HttpStatusCode.NoContent)
                }

                options(BROWSER_HISTORY_PATH) {
                    if (!call.allowTwitchPageCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@options
                    }
                    call.response.headers.append("Access-Control-Allow-Methods", "POST, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    if (call.request.headers["Access-Control-Request-Private-Network"] == "true") {
                        call.response.headers.append("Access-Control-Allow-Private-Network", "true")
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                get(BROWSER_HISTORY_SETUP_PATH) {
                    val pairing = _historyPairing.value
                    val suppliedCode = call.request.queryParameters["code"].orEmpty()
                    if (pairing == null || pairing.expiresAtMs < System.currentTimeMillis() ||
                        !constantTimeEquals(pairing.code, suppliedCode)
                    ) {
                        call.respond(HttpStatusCode.Gone, "TV pairing expired. Start a new sync from PureTV settings.")
                        return@get
                    }
                    call.respondText(
                        BrowserVodHistorySetupPage.render(pairing.code, TwitchConfig.GQL_CLIENT_ID, RESUME_WATCHING_VIDEOS_QUERY),
                        contentType = ContentType.Text.Html,
                    )
                }

                post(BROWSER_HISTORY_PATH) {
                    if (!call.allowTwitchPageCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }
                    val pairingCode = call.historyPairingBearer().orEmpty()
                    if (!call.hasValidHistoryPairing(pairingCode)) {
                        call.respond(HttpStatusCode.Unauthorized, "TV pairing code expired or invalid")
                        return@post
                    }
                    val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (length != null && length > MAX_HISTORY_BODY_BYTES) {
                        call.respond(HttpStatusCode.BadRequest, "History payload too large")
                        return@post
                    }
                    val payload = runCatching { JSON.decodeFromString<BrowserHistoryPayload>(call.receiveText()) }.getOrNull()
                    if (payload == null || !payload.viewerId.isValidTwitchId() || payload.entries.isEmpty() ||
                        payload.entries.size > MAX_HISTORY_ENTRIES || payload.entries.any { entry ->
                            !entry.vodId.isValidTwitchId() || entry.positionMs !in 0L..MAX_POSITION_MS
                        }
                    ) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid Twitch history payload")
                        return@post
                    }

                    val entries = payload.entries.distinctBy { it.vodId }
                    val metadata = try {
                        vodRepository.videosByIds(entries.map { it.vodId }).associateBy { it.id }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not resolve browser history VOD metadata", error)
                        call.respond(HttpStatusCode.BadGateway, "Couldn't load VOD details. TV history was left unchanged.")
                        return@post
                    }
                    val imported = entries.mapNotNull { entry ->
                        val video = metadata[entry.vodId] ?: return@mapNotNull null
                        AppSettingsStore.ViewedVod(
                            id = video.id,
                            channelLogin = video.userLogin,
                            title = video.title,
                            thumbnailUrl = video.thumbnailUrl,
                            durationSeconds = video.durationSeconds,
                            lastViewedEpochMs = 0L,
                            positionMs = clampPosition(entry.positionMs, video.durationSeconds),
                        )
                    }
                    if (imported.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, "Twitch did not return metadata for these VODs. TV history was left unchanged.")
                        return@post
                    }
                    settings.saveBrowserVodHistory(payload.viewerId, imported)
                    _status.value = _status.value.copy(
                        lastSyncAtMs = System.currentTimeMillis(),
                        receivedCount = _status.value.receivedCount + imported.size,
                        lastBrowserHistoryImportAtMs = System.currentTimeMillis(),
                        lastBrowserHistoryImportCount = imported.size,
                    )
                    cancelBrowserHistoryPairing(pairingCode)
                    call.respondText(
                        JSON.encodeToString(BrowserHistoryImportResult(imported.size)),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Accepted,
                    )
                }

                post(BROWSER_HISTORY_MANUAL_PATH) {
                    val code = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ").orEmpty()
                    if (!call.hasValidHistoryPairing(code)) {
                        call.respond(HttpStatusCode.Unauthorized, "TV pairing expired or invalid")
                        return@post
                    }
                    val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (length != null && length > MAX_MANUAL_BODY_BYTES) {
                        call.respond(HttpStatusCode.BadRequest, "Link payload too large")
                        return@post
                    }
                    val payload = runCatching { JSON.decodeFromString<ManualVodPayload>(call.receiveText()) }.getOrNull()
                    val videoId = payload?.url?.let(::twitchVodIdFromUrl)
                    val positionMs = payload?.url?.let(::twitchVodPositionMs)
                    if (videoId == null || positionMs == null) {
                        call.respond(HttpStatusCode.BadRequest, "Paste a Twitch VOD link with a valid timestamp")
                        return@post
                    }
                    val userId = settings.flow.first().userId.takeIf(String::isNotBlank)
                    if (userId == null || !userId.isValidTwitchId()) {
                        call.respond(HttpStatusCode.Unauthorized, "Sign in to the same Twitch account on the TV first")
                        return@post
                    }
                    val video = try {
                        vodRepository.videosByIds(listOf(videoId)).firstOrNull()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not resolve manually shared VOD metadata", error)
                        call.respond(HttpStatusCode.BadGateway, "Couldn't load VOD details. TV history was left unchanged.")
                        return@post
                    }
                    if (video == null) {
                        call.respond(HttpStatusCode.NotFound, "VOD metadata is unavailable")
                        return@post
                    }
                    val previous = settings.browserVodHistories.first()
                        .firstOrNull { it.viewerId == userId }
                        ?.vods.orEmpty()
                    val incoming = AppSettingsStore.ViewedVod(
                        id = video.id,
                        channelLogin = video.userLogin,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl,
                        durationSeconds = video.durationSeconds,
                        lastViewedEpochMs = 0L,
                        positionMs = clampPosition(positionMs, video.durationSeconds),
                    )
                    val existing = previous.firstOrNull { it.id == incoming.id }
                    val merged = incoming.copy(
                        positionMs = maxOf(incoming.positionMs, existing?.positionMs ?: 0L),
                    )
                    settings.saveBrowserVodHistory(
                        userId,
                        listOf(merged) + previous.filterNot { it.id == incoming.id },
                    )
                    _status.value = _status.value.copy(
                        lastSyncAtMs = System.currentTimeMillis(),
                        lastBrowserHistoryImportAtMs = System.currentTimeMillis(),
                        lastBrowserHistoryImportCount = 1,
                    )
                    cancelBrowserHistoryPairing(code)
                    call.respond(HttpStatusCode.Accepted, "VOD sent to TV")
                }

                get(HEALTH_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }
                    call.respondText("ready", status = HttpStatusCode.OK)
                }

                get(POSITION_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }
                    val config = settings.browserVodSync.first()
                    val link = settings.currentWebResumeLink()
                    if (!config.enabled || link == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                        return@get
                    }
                    if (!call.hasValidPairingCode(config.pairingCode)) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val vodId = call.request.queryParameters["vodId"].orEmpty()
                    if (vodId.length !in 1..32 || vodId.any { !it.isDigit() }) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid VOD id")
                        return@get
                    }
                    val position = settings.vodPlaybackPosition(vodId)
                    call.respondText(
                        text = "{\"vodId\":\"$vodId\",\"positionMs\":$position}",
                        contentType = ContentType.Application.Json,
                    )
                }

                post(POSITION_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }
                    val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (length != null && length > MAX_BODY_BYTES) {
                        call.respond(HttpStatusCode.BadRequest, "Payload too large")
                        return@post
                    }

                    val config = settings.browserVodSync.first()
                    if (!config.enabled || config.pairingCode.isBlank()) {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    if (!call.hasValidPairingCode(config.pairingCode)) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                    val payload = runCatching { JSON.decodeFromString<PositionPayload>(call.receiveText()) }.getOrNull()
                    if (payload == null || payload.vodId.length !in 1..32 ||
                        payload.vodId.any { !it.isDigit() } ||
                        payload.positionMs !in 0L..MAX_POSITION_MS
                    ) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid VOD position")
                        return@post
                    }

                    // A delayed browser update can never move the TV's checkpoint backward.
                    settings.advanceVodPlaybackPosition(payload.vodId, payload.positionMs)
                    _status.value = _status.value.copy(
                        lastSyncAtMs = System.currentTimeMillis(),
                        receivedCount = _status.value.receivedCount + 1,
                    )
                    call.respond(HttpStatusCode.Accepted)
                }

                get(WEB_SESSION_CHALLENGE_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }
                    val config = settings.browserVodSync.first()
                    val link = settings.currentWebResumeLink()
                    if (!config.enabled || link == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                        return@get
                    }
                    val now = System.currentTimeMillis()
                    webSessionChallenges.entries.removeIf { it.value.expiresAtMs < now }
                    if (webSessionChallenges.size >= MAX_CHALLENGES) {
                        call.respond(HttpStatusCode.TooManyRequests)
                        return@get
                    }
                    val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
                    val id = UUID.randomUUID().toString()
                    webSessionChallenges[id] = ChallengeState(
                        salt = salt,
                        linkCode = link.code,
                        expiresAtMs = minOf(now + CHALLENGE_TTL_MS, link.expiresAtMs),
                    )
                    call.respondText(
                        text = JSON.encodeToString(WebSessionChallenge(id, Base64.getEncoder().encodeToString(salt))),
                        contentType = ContentType.Application.Json,
                    )
                }

                post(WEB_SESSION_PATH) {
                    if (!call.allowExtensionCors()) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }
                    val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (length != null && length > MAX_WEB_SESSION_BODY_BYTES) {
                        call.respond(HttpStatusCode.BadRequest, "Payload too large")
                        return@post
                    }
                    val config = settings.browserVodSync.first()
                    if (!config.enabled) {
                        call.respond(HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    val payload = runCatching { JSON.decodeFromString<WebSessionPayload>(call.receiveText()) }.getOrNull()
                    if (payload == null || payload.challengeId.length !in 1..64) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid encrypted sign-in payload")
                        return@post
                    }
                    val challenge = webSessionChallenges.remove(payload.challengeId)
                    if (challenge == null || challenge.expiresAtMs < System.currentTimeMillis() ||
                        settings.currentWebResumeLink()?.code != challenge.linkCode
                    ) {
                        call.respond(HttpStatusCode.Gone, "Sign-in handoff expired; start it again from the extension")
                        return@post
                    }
                    val token = runCatching {
                        val iv = Base64.getDecoder().decode(payload.iv)
                        require(iv.size == GCM_IV_BYTES)
                        val ciphertext = Base64.getDecoder().decode(payload.ciphertext)
                        val keySpec = PBEKeySpec(
                            challenge.linkCode.toCharArray(),
                            challenge.salt,
                            KDF_ITERATIONS,
                            AES_KEY_BITS,
                        )
                        val keyBytes = try {
                            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
                        } finally {
                            keySpec.clearPassword()
                        }
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
                    }.getOrNull()
                    if (token == null || token.length !in 20..512 || token.any { it.isWhitespace() || it.isISOControl() }) {
                        call.respond(HttpStatusCode.Unauthorized, "Could not unlock the Twitch session; check the TV pairing code")
                        return@post
                    }
                    settings.saveWebResumeToken(token)
                    settings.completeWebResumeLink()
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }

        try {
            candidate.start(wait = false)
            engine = candidate
            _status.value = Status(running = true)
            Log.i(TAG, "Browser VOD position receiver listening on port $PORT")
        } catch (error: Throwable) {
            runCatching { candidate.stop(0, 250) }
            _status.value = Status(error = error.message ?: "Could not start the browser sync receiver")
            Log.w(TAG, "Could not start browser VOD position receiver", error)
        }
    }

    private suspend fun stopServer() = lifecycleMutex.withLock {
        val current = engine ?: run {
            if (_status.value.running) _status.value = _status.value.copy(running = false, error = null)
            return@withLock
        }
        engine = null
        runCatching { current.stop(gracePeriodMillis = 100, timeoutMillis = 500) }
        _status.value = _status.value.copy(running = false, error = null)
    }

    private fun ApplicationCall.hasValidPairingCode(expected: String): Boolean {
        val authorization = request.headers[HttpHeaders.Authorization] ?: return false
        if (!authorization.startsWith("Bearer ")) return false
        val value = authorization.removePrefix("Bearer ")
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            value.toByteArray(Charsets.UTF_8),
        )
    }

    private fun ApplicationCall.historyPairingBearer(): String? =
        request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")

    private fun ApplicationCall.hasValidHistoryPairing(code: String): Boolean {
        val pairing = _historyPairing.value ?: return false
        return pairing.expiresAtMs >= System.currentTimeMillis() && constantTimeEquals(pairing.code, code)
    }

    @Serializable
    private data class PositionPayload(val vodId: String, val positionMs: Long)

    @Serializable
    private data class BrowserHistoryPayload(val viewerId: String, val entries: List<BrowserHistoryEntry>)

    @Serializable
    private data class BrowserHistoryEntry(val vodId: String, val positionMs: Long)

    @Serializable
    private data class BrowserHistoryImportResult(val importedCount: Int)

    @Serializable
    private data class ManualVodPayload(val url: String)

    private data class ChallengeState(val salt: ByteArray, val linkCode: String, val expiresAtMs: Long)

    @Serializable
    private data class WebSessionChallenge(val challengeId: String, val salt: String)

    @Serializable
    private data class WebSessionPayload(val challengeId: String, val iv: String, val ciphertext: String)

    internal companion object {
        const val TAG = "BrowserVodSync"
        const val PORT = 47821
        const val POSITION_PATH = "/v1/vod-position"
        const val HEALTH_PATH = "/v1/health"
        const val WEB_SESSION_CHALLENGE_PATH = "/v1/web-session/challenge"
        const val WEB_SESSION_PATH = "/v1/web-session"
        const val BROWSER_HISTORY_SETUP_PATH = "/v1/browser-history/setup"
        const val BROWSER_HISTORY_PATH = "/v1/browser-vod-history"
        const val BROWSER_HISTORY_MANUAL_PATH = "/v1/browser-history/manual"
        const val MAX_BODY_BYTES = 4_096L
        const val MAX_WEB_SESSION_BODY_BYTES = 4_096L
        const val MAX_HISTORY_BODY_BYTES = 64 * 1_024L
        const val MAX_MANUAL_BODY_BYTES = 4 * 1_024L
        const val MAX_HISTORY_ENTRIES = 100
        const val MAX_POSITION_MS = 31_536_000_000L // one year
        const val MAX_CHALLENGES = 8
        const val CHALLENGE_TTL_MS = 3 * 60 * 1_000L
        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val KDF_ITERATIONS = 310_000
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val HISTORY_PAIRING_TTL_MS = 15 * 60 * 1_000L
        val JSON = Json { ignoreUnknownKeys = true }

        fun createHistoryPairingCode(): String {
            val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
            val random = SecureRandom()
            return buildString(10) { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        fun String.isValidTwitchId(): Boolean = length in 1..32 && all(Char::isDigit)

        fun clampPosition(positionMs: Long, durationSeconds: Long): Long {
            val nonNegative = positionMs.coerceAtLeast(0L)
            if (durationSeconds <= 0L) return nonNegative
            val durationMs = if (durationSeconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else durationSeconds * 1_000L
            return minOf(nonNegative, durationMs)
        }

        fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )

        fun twitchVodIdFromUrl(value: String): String? = runCatching {
            val uri = java.net.URI(value.trim())
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme !in setOf("http", "https") || host !in setOf("twitch.tv", "www.twitch.tv")) return null
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            val id = when {
                segments.size >= 2 && segments[0] == "videos" -> segments[1]
                segments.size >= 3 && segments[1] == "v" -> segments[2]
                else -> return null
            }
            id.takeIf { it.isValidTwitchId() }
        }.getOrNull()

        fun twitchVodPositionMs(value: String): Long? = runCatching {
            val uri = java.net.URI(value.trim())
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size != 2) null else java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8) to
                    java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
            }.toMap()
            val timestamp = query["t"] ?: return null
            val match = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(timestamp) ?: return null
            if (match.groupValues.drop(1).all(String::isEmpty)) return null
            val components = match.groupValues.drop(1)
            if (components.any { it.isNotEmpty() && it.toLongOrNull() == null }) return null
            val hours = components[0].toLongOrNull() ?: 0L
            val minutes = components[1].toLongOrNull() ?: 0L
            val seconds = components[2].toLongOrNull() ?: 0L
            val totalSeconds = Math.addExact(
                Math.addExact(Math.multiplyExact(hours, 3_600L), Math.multiplyExact(minutes, 60L)),
                seconds,
            )
            if (totalSeconds > MAX_POSITION_MS / 1_000L) return null
            totalSeconds * 1_000L
        }.getOrNull()
    }
}

private fun ApplicationCall.allowExtensionCors(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    if (!origin.startsWith("chrome-extension://") && !origin.startsWith("moz-extension://")) return false
    response.headers.append("Access-Control-Allow-Origin", origin)
    response.headers.append("Vary", "Origin")
    return true
}

private fun ApplicationCall.allowTwitchPageCors(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return false
    if (origin != "https://www.twitch.tv") return false
    response.headers.append("Access-Control-Allow-Origin", origin)
    response.headers.append("Vary", "Origin")
    response.headers.append("Cache-Control", "no-store")
    return true
}
