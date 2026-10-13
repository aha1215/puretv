package com.puretv.twitch.core.api

import com.puretv.twitch.core.model.GameInfo
import com.puretv.twitch.core.model.StreamInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Anonymous public reads over `gql.twitch.tv` — the private endpoint Twitch's own
 * web client uses, which still answers **without OAuth** after Helix began
 * rejecting anonymous `/streams`, `/games/top` and `/search/channels` with
 * `401 {"message":"OAuth token is missing"}`.
 *
 * Used only as a FALLBACK from [TwitchApiClient]: Helix is still preferred, and
 * this is reached only when a Helix read answers 401. That keeps the signed-in
 * path (and therefore app-android/app-windows) byte-for-byte unchanged, and it
 * means the phone/desktop apps gain working anonymous browse too, rather than
 * losing their existing behaviour.
 *
 * Responses are parsed from [JsonObject] rather than `@Serializable` DTOs
 * deliberately: the GQL schema is private and drifts, so a missing field must
 * degrade a single row, never fail the whole call. The `parse*` functions are
 * top-level and pure so they are unit-testable without a network.
 */
internal class TwitchGqlPublicReads(private val httpClient: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun topStreams(first: Int): List<StreamInfo> =
        parseGqlStreams(fetchData(topStreamsQuery(first)))

    suspend fun streamsForGame(gameId: String, first: Int): List<StreamInfo> =
        parseGqlGameStreams(fetchData(gameStreamsQuery(gameId, first)), gameId)

    suspend fun streamsForLogins(logins: List<String>): List<StreamInfo> =
        if (logins.isEmpty()) emptyList() else parseGqlUserStreams(fetchData(userStreamsQuery(logins)))

    suspend fun topGames(first: Int): List<GameInfo> =
        parseGqlGames(fetchData(topGamesQuery(first)))

    suspend fun searchChannels(query: String): List<ChannelSearchResult> =
        parseGqlSearchChannels(fetchData(searchQuery(query)))

    /** POST a single-operation GQL document; any failure becomes `null` (empty result). */
    private suspend fun fetchData(doc: String): JsonObject? = runCatching {
        val body = buildJsonObject { put("query", doc) }.toString()
        val text: String = httpClient.post(TwitchConfig.GQL_ENDPOINT) {
            header("Client-ID", TwitchConfig.GQL_CLIENT_ID)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
    }.getOrNull()

    // ---- Queries -------------------------------------------------------------

    private fun topStreamsQuery(first: Int): String =
        "{ streams(first: $first) { edges { node { $STREAM_FIELDS } } } }"

    private fun gameStreamsQuery(gameId: String, first: Int): String =
        "{ game(id: ${gqlString(gameId)}) { streams(first: $first) { edges { node { $STREAM_FIELDS } } } } }"

    private fun userStreamsQuery(logins: List<String>): String {
        val list = logins.joinToString(",") { gqlString(it) }
        return "{ users(logins: [$list]) { id login displayName stream { $STREAM_FIELDS } } }"
    }

    private fun topGamesQuery(first: Int): String =
        "{ games(first: $first) { edges { node { id name boxArtURL(width: 100, height: 140) } } } }"

    private fun searchQuery(query: String): String =
        "{ searchFor(userQuery: ${gqlString(query)}, platform: \"web\", target: { index: CHANNEL }) " +
            "{ channels { edges { item { ... on User { id login displayName profileImageURL(width: 70) " +
            "stream { id title viewersCount game { name } } } } } } } }"

    private fun gqlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val STREAM_FIELDS =
            "id title viewersCount game { id name } " +
                "broadcaster { id login displayName } previewImageURL(width: 440, height: 248)"
    }
}

// ---- Pure parsers (unit-tested) ---------------------------------------------

/** `data.streams.edges[].node` → [StreamInfo]. */
internal fun parseGqlStreams(data: JsonObject?): List<StreamInfo> =
    data?.child("streams")?.edgeObjects("node").orEmpty().map { node -> node.toStreamInfo() }

/** `data.game.streams.edges[].node` → [StreamInfo]; [gameId] fills a null `node.game`. */
internal fun parseGqlGameStreams(data: JsonObject?, gameId: String): List<StreamInfo> =
    data?.child("game")?.child("streams")?.edgeObjects("node").orEmpty()
        .map { node -> node.toStreamInfo(gameIdOverride = gameId) }

/** `data.users[]` (only those currently live) → [StreamInfo]. */
internal fun parseGqlUserStreams(data: JsonObject?): List<StreamInfo> {
    val users = data?.get("users") as? JsonArray ?: return emptyList()
    return users.mapNotNull { it as? JsonObject }.mapNotNull { user ->
        val stream = user.child("stream") ?: return@mapNotNull null
        StreamInfo(
            id = stream.text("id"),
            userId = user.text("id"),
            userLogin = user.text("login"),
            userName = user.text("displayName"),
            gameId = stream.child("game").text("id"),
            gameName = stream.child("game").text("name"),
            title = stream.text("title"),
            viewerCount = stream.number("viewersCount"),
            thumbnailUrl = stream.text("previewImageURL"),
        )
    }
}

/** `data.games.edges[].node` → [GameInfo]. */
internal fun parseGqlGames(data: JsonObject?): List<GameInfo> =
    data?.child("games")?.edgeObjects("node").orEmpty().map { node ->
        GameInfo(id = node.text("id"), name = node.text("name"), boxArtUrl = node.text("boxArtURL"))
    }

/** `data.searchFor.channels.edges[].item` (a `User`) → [ChannelSearchResult]. */
internal fun parseGqlSearchChannels(data: JsonObject?): List<ChannelSearchResult> =
    data?.child("searchFor")?.child("channels")?.edgeObjects("item").orEmpty().map { item ->
        val stream = item.child("stream")
        ChannelSearchResult(
            id = item.text("id"),
            broadcaster_login = item.text("login"),
            display_name = item.text("displayName"),
            is_live = stream != null,
            game_name = stream?.child("game").text("name").orEmpty(),
            thumbnail_url = item.text("profileImageURL"),
            title = stream?.text("title").orEmpty(),
        )
    }

// ---- Small JsonElement helpers ----------------------------------------------

private fun JsonObject.toStreamInfo(gameIdOverride: String = ""): StreamInfo {
    val broadcaster = child("broadcaster")
    val game = child("game")
    return StreamInfo(
        id = text("id"),
        userId = broadcaster.text("id"),
        userLogin = broadcaster.text("login"),
        userName = broadcaster.text("displayName"),
        gameId = game.text("id").ifBlank { gameIdOverride },
        gameName = game.text("name"),
        title = text("title"),
        viewerCount = number("viewersCount"),
        thumbnailUrl = text("previewImageURL"),
    )
}

private fun JsonObject?.text(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject?.number(key: String): Int =
    (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject?.child(key: String): JsonObject? =
    this?.get(key) as? JsonObject

private fun JsonObject.edgeObjects(key: String): List<JsonObject> {
    val edges = get("edges") as? JsonArray ?: return emptyList()
    return edges.mapNotNull { (it as? JsonObject)?.child(key) }
}
