package com.puretv.twitch.tv.data

import android.util.Log
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

/** Receives VOD checkpoints from the user's paired Twitch browser extension over the LAN. */
class BrowserVodSyncServer(private val settings: AppSettingsStore) {
    data class Status(
        val running: Boolean = false,
        val error: String? = null,
        val lastSyncAtMs: Long? = null,
        val receivedCount: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private var configJob: kotlinx.coroutines.Job? = null
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val webSessionChallenges = ConcurrentHashMap<String, ChallengeState>()

    fun start() {
        if (configJob?.isActive == true) return
        configJob = scope.launch {
            settings.browserVodSync
                .map { it.enabled to it.pairingCode }
                .distinctUntilChanged()
                .collect { (enabled, code) ->
                    if (enabled && code.isNotBlank()) startServer() else stopServer()
                }
        }
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
            if (_status.value.running) _status.value = Status()
            return@withLock
        }
        engine = null
        runCatching { current.stop(gracePeriodMillis = 100, timeoutMillis = 500) }
        _status.value = Status()
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

    @Serializable
    private data class PositionPayload(val vodId: String, val positionMs: Long)

    private data class ChallengeState(val salt: ByteArray, val linkCode: String, val expiresAtMs: Long)

    @Serializable
    private data class WebSessionChallenge(val challengeId: String, val salt: String)

    @Serializable
    private data class WebSessionPayload(val challengeId: String, val iv: String, val ciphertext: String)

    private companion object {
        const val TAG = "BrowserVodSync"
        const val PORT = 47821
        const val POSITION_PATH = "/v1/vod-position"
        const val HEALTH_PATH = "/v1/health"
        const val WEB_SESSION_CHALLENGE_PATH = "/v1/web-session/challenge"
        const val WEB_SESSION_PATH = "/v1/web-session"
        const val MAX_BODY_BYTES = 4_096L
        const val MAX_WEB_SESSION_BODY_BYTES = 4_096L
        const val MAX_POSITION_MS = 31_536_000_000L // one year
        const val MAX_CHALLENGES = 8
        const val CHALLENGE_TTL_MS = 3 * 60 * 1_000L
        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val KDF_ITERATIONS = 310_000
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun ApplicationCall.allowExtensionCors(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    if (!origin.startsWith("chrome-extension://") && !origin.startsWith("moz-extension://")) return false
    response.headers.append("Access-Control-Allow-Origin", origin)
    response.headers.append("Vary", "Origin")
    return true
}
