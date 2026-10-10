package com.puretv.twitch.core.stream

import com.puretv.twitch.core.api.TwitchConfig
import com.puretv.twitch.core.model.StreamToken
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * SECTION 03.4 [CRITICAL] — Helix does not expose a playable stream URL.
 * The only way to obtain one is the undocumented GraphQL `PlaybackAccessToken`
 * operation, which returns a signed token that authorizes a single
 * usher.ttvnw.net master-playlist fetch.
 *
 * GOTCHA #1 (Section 14): we send the FULL inline query
 * ([PLAYBACK_ACCESS_TOKEN_QUERY]) rather than a persisted-query `sha256Hash`.
 * Twitch rotates persisted-query hashes without notice — a stale hash returns
 * `PersistedQueryNotFound`, which surfaced as "streams won't load / ad-block
 * stuck on Checking…". The inline query carries the operation itself, so there
 * is no hash for Twitch to invalidate.
 */
class TwitchGqlClient(
    private val httpClient: HttpClient,
    // encodeDefaults = true is LOAD-BEARING: Twitch's GraphQL schema requires
    // isLive/vodID/isVod/playerType as non-null types. With the kotlinx-serialization
    // default of false, our PlaybackAccessTokenVariables data class's default
    // values are stripped from the request body — Twitch then sees them as
    // explicit nulls and rejects the query with "Expected type X!, found null".
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    // `currentUser.viewedVideos` is a private Twitch web-client field. When a
    // third-party OAuth token gets a successful response with currentUser=null,
    // retries with that same token cannot make the field available. Remember
    // that response for this process so periodic home refreshes and player
    // checkpoint writes do not keep hitting the same unsupported path. A newly
    // refreshed token gets one fresh attempt.
    @Volatile private var resumeHistoryUnavailableToken: String? = null

    /**
     * @param playerType the GQL `playerType` Twitch fingerprints for ad targeting.
     *   `"site"` (the web player) gets the most ads; `"popout"`/`"embed"` get far
     *   fewer or none. The ad-block backup-swap (see `BackupStreamResolver`) calls
     *   this repeatedly with different player types to find an ad-free manifest —
     *   this is the single most effective lever for actually removing Twitch ads.
     */
    suspend fun fetchStreamToken(
        channelLogin: String,
        oauthToken: String?,
        playerType: String = "site",
    ): StreamToken {
        val response = postPlaybackAccessToken(
            variables = PlaybackAccessTokenVariables(login = channelLogin, playerType = playerType),
            oauthToken = oauthToken,
        ).bodyAsText()

        val payload = json.decodeFromString<GqlEnvelope<PlaybackAccessTokenData>>(response)
        val token = payload.data?.streamPlaybackAccessToken
            ?: run {
                // Surface the raw GQL body so the real cause (schema change,
                // integrity check, banned channel, etc.) is visible instead of
                // a generic blame.
                val truncated = if (response.length > 600) response.substring(0, 600) + "…" else response
                throw GqlPlaybackTokenException("PlaybackAccessToken returned no token. GQL response: $truncated")
            }

        return StreamToken(value = token.value, signature = token.signature)
    }

    /** Fetch a playback token for a VOD instead of a live channel. */
    suspend fun fetchVodToken(vodId: String, oauthToken: String?): StreamToken {
        var response = postPlaybackAccessToken(
            variables = PlaybackAccessTokenVariables(login = "", isLive = false, isVod = true, vodID = vodId),
            oauthToken = oauthToken,
        )
        if (oauthToken != null && response.status == HttpStatusCode.Unauthorized) {
            // Expired TV OAuth sessions currently become a {status,message} 401
            // body. The old helper discarded the status, decoded that body as a
            // GraphQL envelope, and misleadingly reported "no token"—leaving a
            // black player. Public VODs do not need OAuth to mint a playback
            // token, so retry anonymously; account-only VODs still fail closed.
            response.bodyAsText()
            response = postPlaybackAccessToken(
                variables = PlaybackAccessTokenVariables(login = "", isLive = false, isVod = true, vodID = vodId),
                oauthToken = null,
            )
        }
        val responseText = response.bodyAsText()
        val payload = json.decodeFromString<GqlEnvelope<PlaybackAccessTokenData>>(responseText)
        val token = payload.data?.videoPlaybackAccessToken
            ?: throw GqlPlaybackTokenException(
                "VOD PlaybackAccessToken returned no token (HTTP ${response.status.value})",
            )
        return StreamToken(value = token.value, signature = token.signature)
    }

    /**
     * Read the signed-in viewer's Twitch-saved resume point for a VOD.
     *
     * Twitch exposes this through its private `currentUser.viewedVideos` GQL
     * field, not the public Helix API. Keep this best-effort: missing auth,
     * schema changes, and request failures all return null so callers can use
     * their local playback position.
     */
    suspend fun fetchVodPlaybackPosition(vodId: String, oauthToken: String?): Long? {
        return fetchVodPlaybackPositions(oauthToken).getOrNull()?.get(vodId)
    }

    /** Best-effort account-synced positions for the viewer's recent VODs, in milliseconds. */
    suspend fun fetchVodPlaybackPositions(oauthToken: String?): Result<Map<String, Long>> =
        fetchVodPlaybackHistory(oauthToken).map { history ->
            history.entries.associate { it.vodId to it.positionMs }
        }

    /** Recent Twitch VOD history in Twitch order, including the authenticated viewer ID. */
    suspend fun fetchVodPlaybackHistory(oauthToken: String?): Result<VodPlaybackHistory> =
        lookupVodPlaybackHistory(oauthToken)

    /** Best-effort write of the authenticated viewer's VOD resume point, in seconds. */
    suspend fun updateVodPlaybackPosition(
        vodId: String,
        userId: String,
        positionSeconds: Long,
        oauthToken: String?,
    ): Boolean {
        if (vodId.isBlank() || vodId.any { !it.isDigit() } ||
            (userId.isNotBlank() && userId.any { !it.isDigit() }) || oauthToken.isNullOrBlank()
        ) return false

        // Keep Twitch's account resume point monotonic from this client's point
        // of view. A backward seek or stale local checkpoint must not replace a
        // newer Twitch position. Fail closed if the private lookup is unavailable.
        val currentHistory = lookupVodPlaybackHistory(oauthToken).getOrNull() ?: return false
        val nextPositionSeconds = positionSeconds.coerceIn(0L, Int.MAX_VALUE.toLong())
        val savedPositionSeconds = currentHistory.entries
            .firstOrNull { it.vodId == vodId }
            ?.positionMs
            ?.div(1_000L)
        if (savedPositionSeconds != null && nextPositionSeconds < savedPositionSeconds) return false
        val viewerId = currentHistory.userId?.takeIf { id -> id.isNotBlank() && id.all(Char::isDigit) }
            ?: userId.takeIf { id -> id.isNotBlank() && id.all(Char::isDigit) }
            ?: return false

        val requestBody = json.encodeToString(
            UpdateViewedVideoRequest(
                operationName = "UpdateUserViewedVideo",
                query = UPDATE_USER_VIEWED_VIDEO_MUTATION,
                variables = UpdateViewedVideoVariables(
                    input = UpdateViewedVideoInput(
                        userID = viewerId,
                        position = nextPositionSeconds.toInt(),
                        videoID = vodId,
                        videoType = "VOD",
                    ),
                ),
            ),
        )
        return try {
            val response = httpClient.post(TwitchConfig.GQL_ENDPOINT) {
                // Twitch's private GraphQL endpoint rejects this app's client ID.
                header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
                header("Authorization", "OAuth $oauthToken")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body<String>()
            json.decodeFromString<GqlEnvelope<UpdateViewedVideoData>>(response)
                .data?.updateUserViewedVideo != null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun lookupVodPlaybackHistory(oauthToken: String?): Result<VodPlaybackHistory> {
        if (oauthToken.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("An OAuth token is required"))
        }
        if (resumeHistoryUnavailableToken == oauthToken) {
            return Result.failure(TwitchResumeHistoryUnavailableException())
        }

        val requestBody = json.encodeToString(
            ResumeWatchingQueryRequest(
                operationName = "ResumeWatchingVideosQuery",
                query = RESUME_WATCHING_VIDEOS_QUERY,
                variables = ResumeWatchingQueryVariables(limit = RESUME_WATCHING_VIDEO_LIMIT),
            ),
        )
        return try {
            val response = httpClient.post(TwitchConfig.GQL_ENDPOINT) {
                header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
                header("Authorization", "OAuth $oauthToken")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body<String>()
            val payload = json.decodeFromString<ResumeWatchingEnvelope>(response)
            if (payload.errors.isNotEmpty()) {
                val errorSummary = payload.errors.take(3).joinToString()
                return Result.failure(IllegalStateException("Twitch rejected the resume-position lookup: $errorSummary"))
            }
            val currentUser = payload.data?.currentUser
                ?: return Result.failure(
                    TwitchResumeHistoryUnavailableException().also {
                        resumeHistoryUnavailableToken = oauthToken
                    },
                )
            val viewedVideos = currentUser.viewedVideos
                ?: return Result.failure(IllegalStateException("Twitch did not return viewed VOD history"))
            val entries = viewedVideos.edges.mapNotNull { edge ->
                val id = edge.node?.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val positionMs = edge.history?.position?.coerceAtLeast(0L)?.times(1_000L)
                    ?: return@mapNotNull null
                VodPlaybackHistoryEntry(id, positionMs)
            }.distinctBy { it.vodId }
            Result.success(VodPlaybackHistory(currentUser.id, entries))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Fetch a VOD's storyboard JSON URL (seekPreviewsURL), or null if it has none. */
    suspend fun fetchSeekPreviewsUrl(vodId: String): String? {
        // Twitch video IDs are numeric; validate before string-interpolating into
        // the GQL literal so a stray quote/brace can't break out of the query
        // (audit F8 — mirrors the sanitization in fetchFollowerCount).
        val safeId = vodId.filter { it.isDigit() }
        if (safeId.isEmpty()) return null
        val query = """{"query":"query{video(id:\"$safeId\"){seekPreviewsURL}}"}"""
        val response: String = httpClient.post(TwitchConfig.GQL_ENDPOINT) {
            header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
            contentType(ContentType.Application.Json)
            setBody(query)
        }.body()
        return json.decodeFromString<GqlEnvelope<VideoSeekPreviewsData>>(response)
            .data?.video?.seekPreviewsURL?.takeIf { it.isNotBlank() }
    }

    /** Fetch the VOD's chat comments starting near [offsetSeconds] (offset-based paging). */
    suspend fun fetchVideoComments(vodId: String, offsetSeconds: Int): CommentBatch {
        val safeId = vodId.filter { it.isDigit() }
        if (safeId.isEmpty()) return CommentBatch(emptyList(), hasNextPage = false)
        val safeOffset = offsetSeconds.coerceAtLeast(0)
        val query = """{"query":"query{video(id:\"$safeId\"){comments(contentOffsetSeconds:$safeOffset){edges{node{id contentOffsetSeconds commenter{displayName} message{userColor userBadges{setID version} fragments{text emote{emoteID}}}}}pageInfo{hasNextPage}}}}"}"""
        val response: String = httpClient.post(TwitchConfig.GQL_ENDPOINT) {
            header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
            contentType(ContentType.Application.Json)
            setBody(query)
        }.body()
        val conn = json.decodeFromString<GqlEnvelope<VideoCommentsData>>(response).data?.video?.comments
            ?: return CommentBatch(emptyList(), hasNextPage = false)
        return CommentBatch(CommentMapper.toReplayComments(conn), conn.pageInfo.hasNextPage)
    }

    /**
     * Fetch a channel's total follower count via the private GQL API (no token).
     * Returns null if unavailable (banned/renamed channel, network error, etc.).
     */
    suspend fun fetchFollowerCount(login: String): Long? {
        val safe = login.filter { it.isLetterOrDigit() || it == '_' }
        if (safe.isEmpty()) return null
        val query = """{"query":"query{user(login:\"$safe\"){followers{totalCount}}}"}"""
        val response: String = runCatching {
            httpClient.post(TwitchConfig.GQL_ENDPOINT) {
                header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
                contentType(ContentType.Application.Json)
                setBody(query)
            }.body<String>()
        }.getOrNull() ?: return null
        return parseFollowerCount(response)
    }

    private suspend fun postPlaybackAccessToken(
        variables: PlaybackAccessTokenVariables,
        oauthToken: String?,
    ): HttpResponse = httpClient.post(TwitchConfig.GQL_ENDPOINT) {
        header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
        oauthToken?.let { header("Authorization", "OAuth $it") }
        contentType(ContentType.Application.Json)
        setBody(buildPlaybackAccessTokenBody(variables, json))
    }
}

class GqlPlaybackTokenException(message: String) : Exception(message)

/** Twitch accepted the private GQL request but did not expose account history. */
class TwitchResumeHistoryUnavailableException :
    Exception("Twitch's private resume-history field isn't available to this client.")

/**
 * The FULL inline PlaybackAccessToken GraphQL operation (the same one Twitch's
 * own web client runs). We send this verbatim instead of a persisted-query
 * `sha256Hash` so there is nothing for Twitch to rotate out from under us —
 * the cause of GOTCHA #1 (a stale hash → `PersistedQueryNotFound` → no token →
 * no stream). The `@include(if:)` directives let the single operation serve
 * both live (`isLive`) and VOD (`isVod`) requests.
 */
internal const val PLAYBACK_ACCESS_TOKEN_QUERY =
    "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) { " +
        "streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) { value signature __typename } " +
        "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) { value signature __typename } }"

private const val RESUME_WATCHING_VIDEO_LIMIT = 100

const val RESUME_WATCHING_VIDEOS_QUERY =
    "query ResumeWatchingVideosQuery(\$limit: Int!) { " +
        "currentUser { id viewedVideos(first: \$limit) { " +
        "edges { history { position } node { id } } } } }"

private const val UPDATE_USER_VIEWED_VIDEO_MUTATION =
    "mutation UpdateUserViewedVideo(\$input: UpdateUserViewedVideoInput!) { " +
        "updateUserViewedVideo(input: \$input) { __typename } }"

@Serializable
private data class UpdateViewedVideoRequest(
    val operationName: String,
    val query: String,
    val variables: UpdateViewedVideoVariables,
)

@Serializable
private data class UpdateViewedVideoVariables(val input: UpdateViewedVideoInput)

@Serializable
private data class UpdateViewedVideoInput(
    val userID: String,
    val position: Int,
    val videoID: String,
    val videoType: String,
)

@Serializable
private data class UpdateViewedVideoData(
    val updateUserViewedVideo: UpdateViewedVideoPayload? = null,
)

@Serializable
private data class UpdateViewedVideoPayload(
    @SerialName("__typename") val typeName: String? = null,
)

@Serializable
private data class ResumeWatchingQueryRequest(
    val operationName: String,
    val query: String,
    val variables: ResumeWatchingQueryVariables,
)

@Serializable
private data class ResumeWatchingEnvelope(
    val data: ResumeWatchingData? = null,
    val errors: List<JsonElement> = emptyList(),
)

@Serializable
private data class ResumeWatchingQueryVariables(val limit: Int)

@Serializable
private data class ResumeWatchingData(val currentUser: ResumeWatchingUser? = null)

@Serializable
private data class ResumeWatchingUser(val id: String? = null, val viewedVideos: ViewedVideosConnection? = null)

data class VodPlaybackHistory(val userId: String?, val entries: List<VodPlaybackHistoryEntry>)

data class VodPlaybackHistoryEntry(val vodId: String, val positionMs: Long)

@Serializable
private data class ViewedVideosConnection(val edges: List<ViewedVideosEdge> = emptyList())

@Serializable
private data class ViewedVideosEdge(
    val history: VideoViewingHistory? = null,
    val node: ViewedVideo? = null,
)

@Serializable
private data class VideoViewingHistory(val position: Long? = null)

@Serializable
private data class ViewedVideo(val id: String? = null)

@Serializable
internal data class GqlInlineQueryRequest(
    val operationName: String,
    val query: String,
    val variables: PlaybackAccessTokenVariables,
)

/**
 * Serializes the PlaybackAccessToken request body. Pure (no network) so the
 * inline-query contract is unit-testable.
 *
 * `encodeDefaults = true` is LOAD-BEARING: Twitch's schema types
 * isLive/isVod/vodID/playerType as non-null, so the data class defaults must be
 * serialized — otherwise Twitch sees explicit nulls and rejects the query.
 */
internal fun buildPlaybackAccessTokenBody(
    variables: PlaybackAccessTokenVariables,
    json: Json = Json { encodeDefaults = true },
): String = json.encodeToString(
    GqlInlineQueryRequest(
        operationName = "PlaybackAccessToken_Template",
        query = PLAYBACK_ACCESS_TOKEN_QUERY,
        variables = variables,
    ),
)

// ---- Wire format for the PlaybackAccessToken POST body ----

@Serializable
data class PlaybackAccessTokenVariables(
    val isLive: Boolean = true,
    val login: String,
    val isVod: Boolean = false,
    val vodID: String = "",
    val playerType: String = "site",
)

// ---- Response envelope ----

@Serializable
data class GqlEnvelope<T>(val data: T? = null)

@Serializable
data class PlaybackAccessTokenData(
    @SerialName("streamPlaybackAccessToken") val streamPlaybackAccessToken: PlaybackAccessTokenValue? = null,
    @SerialName("videoPlaybackAccessToken") val videoPlaybackAccessToken: PlaybackAccessTokenValue? = null,
)

@Serializable
data class PlaybackAccessTokenValue(
    val value: String,
    val signature: String,
)

@Serializable
data class VideoSeekPreviewsData(val video: VideoSeekPreviews? = null)

@Serializable
data class VideoSeekPreviews(
    @SerialName("seekPreviewsURL") val seekPreviewsURL: String? = null,
)

// ---- Follower count ----

@Serializable
data class FollowerCountData(val user: FollowerCountUser? = null)

@Serializable
data class FollowerCountUser(val followers: FollowerConnection? = null)

@Serializable
data class FollowerConnection(val totalCount: Long? = null)

private val followerCountJson = Json { ignoreUnknownKeys = true }

/**
 * Parses a total follower count out of a GQL `user{followers{totalCount}}`
 * response. Returns null for error bodies, missing users, or malformed JSON —
 * the follower COUNT is still public even though the follower LIST is gated.
 */
internal fun parseFollowerCount(response: String): Long? =
    runCatching {
        followerCountJson.decodeFromString<GqlEnvelope<FollowerCountData>>(response)
            .data?.user?.followers?.totalCount
    }.getOrNull()
