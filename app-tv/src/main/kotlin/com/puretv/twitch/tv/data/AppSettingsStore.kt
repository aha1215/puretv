package com.puretv.twitch.tv.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.puretv.twitch.core.di.TokenHolder
import com.puretv.twitch.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A half-written preferences file (a process killed mid-write, and the in-app
// updater kills this one by definition) otherwise makes DataStore throw on
// every read for the rest of the install's life. That read happens during
// startup, so the app stops launching and stays that way, since reinstalling
// over the top leaves app data alone. Falling back to defaults is safe here:
// none of this is sensitive, and the viewer just gets factory settings back.
// Mirrors the phone app's delegate.
private val Context.dataStore by preferencesDataStore(
    name = "puretv_tv_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * SECTION 09.2 — TV counterpart of the phone app's `AppSettingsStore`.
 * Same split — ordinary prefs in DataStore, OAuth triad mirrored from
 * [SecureTokenStore] via [tokenState] — kept as its own class (rather than
 * shared) because `Context.dataStore` is a top-level per-package delegate and
 * Section 12.2 specifies app-tv shares no UI/platform-store code with
 * app-android, only `core`.
 */
class AppSettingsStore(
    private val context: Context,
    private val secureTokenStore: SecureTokenStore,
    private val tokenHolder: TokenHolder,
) {
    data class ChatAppearance(
        val opacity: Float = 0f,
        val widthDp: Float = 360f,
        val side: String = "right",
        val fadeSeconds: Int = 0,
        val fontFamily: String = "sans",
        val textColor: String = "white",
        val outlineColor: String = "black",
        val outlineWidthDp: Float = 1.5f,
        val useTwitchNameColors: Boolean = true,
    )

    data class ChatTranslation(
        val enabled: Boolean = true,
        val targetLanguage: String = "en",
    )

    data class BrowserVodSync(
        val enabled: Boolean = false,
        val pairingCode: String = "",
    )

    enum class VodSeekProfile(val label: String, val stepsMinutes: List<Int>) {
        Precise("Precise · 1 / 2 / 5 / 10 min", listOf(1, 2, 5, 10)),
        Standard("Standard · 1 / 5 / 10 / 15 min", listOf(1, 5, 10, 15)),
        Fast("Fast · 1 / 5 / 15 / 30 min", listOf(1, 5, 15, 30));

        companion object {
            fun fromKey(key: String?): VodSeekProfile =
                entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Standard
        }
    }

    data class ViewedVod(
        val id: String,
        val channelLogin: String,
        val title: String,
        val thumbnailUrl: String,
        val durationSeconds: Long,
        val lastViewedEpochMs: Long,
        val positionMs: Long,
    )

    private object Keys {
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val LOW_LATENCY = booleanPreferencesKey("low_latency_mode")
        val AD_BLOCK_ENABLED = booleanPreferencesKey("ad_block_enabled")
        val AD_BLOCK_STRATEGY = stringPreferencesKey("ad_block_strategy")
        val CUSTOM_PROXY_URL = stringPreferencesKey("custom_proxy_url")
        val CHAT_ENABLED = booleanPreferencesKey("chat_enabled")
        val CHAT_FONT_SIZE = floatPreferencesKey("chat_font_size")
        val SHOW_BADGES = booleanPreferencesKey("show_badges")
        val SHOW_BTTV = booleanPreferencesKey("show_bttv_emotes")
        val SHOW_7TV = booleanPreferencesKey("show_7tv_emotes")
        val SHOW_FFZ = booleanPreferencesKey("show_ffz_emotes")
        val CHAT_TIMESTAMPS = booleanPreferencesKey("chat_timestamps")
        val THEME = stringPreferencesKey("theme")
        val COMPACT_MODE = booleanPreferencesKey("compact_mode")
        val TV_CHAT_OPACITY = floatPreferencesKey("tv_chat_opacity")
        val TV_CHAT_WIDTH = floatPreferencesKey("tv_chat_width")
        val TV_CHAT_SIDE = stringPreferencesKey("tv_chat_side")
        val TV_CHAT_FADE_SECONDS = intPreferencesKey("tv_chat_fade_seconds")
        val TV_CHAT_FONT_FAMILY = stringPreferencesKey("tv_chat_font_family")
        val TV_CHAT_TEXT_COLOR = stringPreferencesKey("tv_chat_text_color")
        val TV_CHAT_OUTLINE_COLOR = stringPreferencesKey("tv_chat_outline_color")
        val TV_CHAT_OUTLINE_WIDTH = floatPreferencesKey("tv_chat_outline_width")
        val TV_CHAT_TWITCH_NAME_COLORS = booleanPreferencesKey("tv_chat_twitch_name_colors")
        val TV_CHAT_TRANSLATION_ENABLED = booleanPreferencesKey("tv_chat_translation_enabled")
        val TV_CHAT_TRANSLATION_TARGET = stringPreferencesKey("tv_chat_translation_target")
        val TV_VOD_SEEK_PROFILE = stringPreferencesKey("tv_vod_seek_profile")
        val TV_BROWSER_VOD_SYNC_ENABLED = booleanPreferencesKey("tv_browser_vod_sync_enabled")
        val TV_BROWSER_VOD_SYNC_CODE = stringPreferencesKey("tv_browser_vod_sync_code")
        val VIEWED_VOD_IDS = stringPreferencesKey("tv_viewed_vod_ids")
    }

    private data class TokenSnapshot(val accessToken: String, val username: String, val userId: String)

    private val tokenState = MutableStateFlow(loadTokenSnapshot())

    init {
        tokenHolder.update(secureTokenStore.accessToken())
    }

    private fun loadTokenSnapshot() = TokenSnapshot(
        accessToken = secureTokenStore.accessToken().orEmpty(),
        username = secureTokenStore.username().orEmpty(),
        userId = secureTokenStore.userId().orEmpty(),
    )

    val flow: Flow<AppSettings> = combine(context.dataStore.data, tokenState) { prefs, token ->
        AppSettings(
            preferredQuality = prefs[Keys.PREFERRED_QUALITY] ?: "auto",
            lowLatencyMode = prefs[Keys.LOW_LATENCY] ?: true,
            adBlockEnabled = prefs[Keys.AD_BLOCK_ENABLED] ?: true,
            adBlockStrategy = prefs[Keys.AD_BLOCK_STRATEGY] ?: "proxy",
            customProxyUrl = prefs[Keys.CUSTOM_PROXY_URL] ?: "",
            chatEnabled = prefs[Keys.CHAT_ENABLED] ?: true,
            chatFontSize = (prefs[Keys.CHAT_FONT_SIZE] ?: 9f).coerceIn(8f, 14f),
            showBadges = prefs[Keys.SHOW_BADGES] ?: true,
            showBttvEmotes = prefs[Keys.SHOW_BTTV] ?: true,
            show7tvEmotes = prefs[Keys.SHOW_7TV] ?: true,
            showFfzEmotes = prefs[Keys.SHOW_FFZ] ?: true,
            chatTimestamps = prefs[Keys.CHAT_TIMESTAMPS] ?: false,
            theme = prefs[Keys.THEME] ?: "dark",
            compactMode = prefs[Keys.COMPACT_MODE] ?: false,
            accessToken = token.accessToken,
            username = token.username,
            userId = token.userId,
        )
    }
        // The corruption handler above covers a file DataStore can parse but not
        // trust. It does not cover the file being unreadable outright, and this
        // flow is collected from composition, where a thrown read takes the
        // Activity with it. Settings are not worth that: fall back to factory
        // values, keeping the session, which lives in memory and is still good.
        .catch { e ->
            Log.w(TAG, "Settings unreadable, falling back to defaults", e)
            val token = tokenState.value
            emit(
                AppSettings(
                    accessToken = token.accessToken,
                    username = token.username,
                    userId = token.userId,
                ),
            )
        }

    val chatAppearance: Flow<ChatAppearance> = context.dataStore.data.map { prefs ->
        val storedOpacity = prefs[Keys.TV_CHAT_OPACITY]
        ChatAppearance(
            // Older releases defaulted to a 62% panel. Make the default fully
            // transparent for outlined text over video, while preserving choices.
            opacity = when {
                storedOpacity == null -> 0f
                kotlin.math.abs(storedOpacity - 0.62f) < 0.005f -> 0f
                else -> storedOpacity.coerceIn(0f, 1f)
            },
            widthDp = (prefs[Keys.TV_CHAT_WIDTH] ?: 360f).coerceIn(280f, 480f),
            side = if (prefs[Keys.TV_CHAT_SIDE] == "left") "left" else "right",
            fadeSeconds = (prefs[Keys.TV_CHAT_FADE_SECONDS] ?: 0).coerceIn(0, 120),
            fontFamily = prefs[Keys.TV_CHAT_FONT_FAMILY].orEmpty().takeIf { it in setOf("sans", "serif", "mono") } ?: "sans",
            textColor = prefs[Keys.TV_CHAT_TEXT_COLOR].orEmpty().takeIf { it in setOf("white", "warm", "yellow") } ?: "white",
            outlineColor = prefs[Keys.TV_CHAT_OUTLINE_COLOR].orEmpty().takeIf { it in setOf("black", "navy", "white") } ?: "black",
            outlineWidthDp = (prefs[Keys.TV_CHAT_OUTLINE_WIDTH] ?: 1.5f).coerceIn(0f, 4f),
            useTwitchNameColors = prefs[Keys.TV_CHAT_TWITCH_NAME_COLORS] ?: true,
        )
    }

    suspend fun updateChatAppearance(transform: (ChatAppearance) -> ChatAppearance) {
        val current = chatAppearance.first()
        val next = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.TV_CHAT_OPACITY] = next.opacity.coerceIn(0f, 1f)
            prefs[Keys.TV_CHAT_WIDTH] = next.widthDp.coerceIn(280f, 480f)
            prefs[Keys.TV_CHAT_SIDE] = if (next.side == "left") "left" else "right"
            prefs[Keys.TV_CHAT_FADE_SECONDS] = next.fadeSeconds.coerceIn(0, 120)
            prefs[Keys.TV_CHAT_FONT_FAMILY] = next.fontFamily.takeIf { it in setOf("sans", "serif", "mono") } ?: "sans"
            prefs[Keys.TV_CHAT_TEXT_COLOR] = next.textColor.takeIf { it in setOf("white", "warm", "yellow") } ?: "white"
            prefs[Keys.TV_CHAT_OUTLINE_COLOR] = next.outlineColor.takeIf { it in setOf("black", "navy", "white") } ?: "black"
            prefs[Keys.TV_CHAT_OUTLINE_WIDTH] = next.outlineWidthDp.coerceIn(0f, 4f)
            prefs[Keys.TV_CHAT_TWITCH_NAME_COLORS] = next.useTwitchNameColors
        }
    }

    val chatTranslation: Flow<ChatTranslation> = context.dataStore.data.map { prefs ->
        ChatTranslation(
            enabled = prefs[Keys.TV_CHAT_TRANSLATION_ENABLED] ?: true,
            targetLanguage = prefs[Keys.TV_CHAT_TRANSLATION_TARGET]
                ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")) }
                ?: "en",
        )
    }

    suspend fun updateChatTranslation(transform: (ChatTranslation) -> ChatTranslation) {
        val next = transform(chatTranslation.first())
        context.dataStore.edit { prefs ->
            prefs[Keys.TV_CHAT_TRANSLATION_ENABLED] = next.enabled
            prefs[Keys.TV_CHAT_TRANSLATION_TARGET] = next.targetLanguage
                .takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")) }
                ?: "en"
        }
    }

    val vodSeekProfile: Flow<VodSeekProfile> = context.dataStore.data.map { prefs ->
        VodSeekProfile.fromKey(prefs[Keys.TV_VOD_SEEK_PROFILE])
    }

    suspend fun saveVodSeekProfile(profile: VodSeekProfile) {
        context.dataStore.edit { prefs -> prefs[Keys.TV_VOD_SEEK_PROFILE] = profile.name }
    }

    val browserVodSync: Flow<BrowserVodSync> = context.dataStore.data.map { prefs ->
        BrowserVodSync(
            enabled = prefs[Keys.TV_BROWSER_VOD_SYNC_ENABLED] ?: false,
            pairingCode = prefs[Keys.TV_BROWSER_VOD_SYNC_CODE].orEmpty(),
        )
    }

    suspend fun setBrowserVodSyncEnabled(enabled: Boolean, rotateCode: Boolean = false) {
        val newCode = if (enabled && rotateCode) createBrowserVodSyncCode() else null
        context.dataStore.edit { prefs ->
            val currentCode = prefs[Keys.TV_BROWSER_VOD_SYNC_CODE].orEmpty()
            if (enabled && (currentCode.isBlank() || rotateCode)) {
                prefs[Keys.TV_BROWSER_VOD_SYNC_CODE] = newCode ?: createBrowserVodSyncCode()
            }
            prefs[Keys.TV_BROWSER_VOD_SYNC_ENABLED] = enabled
        }
    }

    suspend fun vodPlaybackPosition(vodId: String): Long =
        context.dataStore.data.first()[longPreferencesKey("tv_vod_position_$vodId")] ?: 0L

    suspend fun saveVodPlaybackPosition(vodId: String, positionMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[longPreferencesKey("tv_vod_position_$vodId")] = positionMs.coerceAtLeast(0L)
        }
    }

    /** Raise a local checkpoint when a synced source is farther ahead, without racing a newer save. */
    suspend fun advanceVodPlaybackPosition(vodId: String, positionMs: Long) {
        if (vodId.isBlank()) return
        context.dataStore.edit { prefs ->
            val key = longPreferencesKey("tv_vod_position_$vodId")
            val nextPosition = positionMs.coerceAtLeast(0L)
            if (nextPosition > (prefs[key] ?: 0L)) prefs[key] = nextPosition
        }
    }

    val viewedVods: Flow<List<ViewedVod>> = context.dataStore.data.map { prefs ->
        prefs[Keys.VIEWED_VOD_IDS].orEmpty().split(',').filter(String::isNotBlank).mapNotNull { id ->
            val title = prefs[stringPreferencesKey("tv_viewed_vod_title_$id")] ?: return@mapNotNull null
            ViewedVod(
                id = id,
                channelLogin = prefs[stringPreferencesKey("tv_viewed_vod_channel_$id")].orEmpty(),
                title = title,
                thumbnailUrl = prefs[stringPreferencesKey("tv_viewed_vod_thumbnail_$id")].orEmpty(),
                durationSeconds = prefs[longPreferencesKey("tv_viewed_vod_duration_$id")] ?: 0L,
                lastViewedEpochMs = prefs[longPreferencesKey("tv_viewed_vod_time_$id")] ?: 0L,
                positionMs = prefs[longPreferencesKey("tv_vod_position_$id")] ?: 0L,
            )
        }
    }

    suspend fun recordViewedVod(vod: ViewedVod) {
        if (vod.id.isBlank()) return
        context.dataStore.edit { prefs ->
            val ids = prefs[Keys.VIEWED_VOD_IDS].orEmpty().split(',').filter(String::isNotBlank)
            prefs[Keys.VIEWED_VOD_IDS] = (listOf(vod.id) + ids.filterNot { it == vod.id }).take(30).joinToString(",")
            prefs[stringPreferencesKey("tv_viewed_vod_channel_${vod.id}")] = vod.channelLogin
            prefs[stringPreferencesKey("tv_viewed_vod_title_${vod.id}")] = vod.title
            prefs[stringPreferencesKey("tv_viewed_vod_thumbnail_${vod.id}")] = vod.thumbnailUrl
            prefs[longPreferencesKey("tv_viewed_vod_duration_${vod.id}")] = vod.durationSeconds.coerceAtLeast(0L)
            prefs[longPreferencesKey("tv_viewed_vod_time_${vod.id}")] = System.currentTimeMillis()
        }
    }

    suspend fun vodSubtitleUri(vodId: String): String? =
        context.dataStore.data.first()[stringPreferencesKey("tv_vod_subtitle_$vodId")]

    suspend fun saveVodSubtitleUri(vodId: String, uri: String?) {
        context.dataStore.edit { prefs ->
            val key = stringPreferencesKey("tv_vod_subtitle_$vodId")
            if (uri == null) prefs.remove(key) else prefs[key] = uri
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = flow.first()
        val next = transform(current)

        context.dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_QUALITY] = next.preferredQuality
            prefs[Keys.LOW_LATENCY] = next.lowLatencyMode
            prefs[Keys.AD_BLOCK_ENABLED] = next.adBlockEnabled
            prefs[Keys.AD_BLOCK_STRATEGY] = next.adBlockStrategy
            prefs[Keys.CUSTOM_PROXY_URL] = next.customProxyUrl
            prefs[Keys.CHAT_ENABLED] = next.chatEnabled
            prefs[Keys.CHAT_FONT_SIZE] = next.chatFontSize.coerceIn(8f, 14f)
            prefs[Keys.SHOW_BADGES] = next.showBadges
            prefs[Keys.SHOW_BTTV] = next.showBttvEmotes
            prefs[Keys.SHOW_7TV] = next.show7tvEmotes
            prefs[Keys.SHOW_FFZ] = next.showFfzEmotes
            prefs[Keys.CHAT_TIMESTAMPS] = next.chatTimestamps
            prefs[Keys.THEME] = next.theme
            prefs[Keys.COMPACT_MODE] = next.compactMode
        }
    }

    fun setSession(accessToken: String, refreshToken: String?, username: String = "", userId: String = "") {
        secureTokenStore.save(accessToken, refreshToken, username, userId)
        tokenHolder.update(accessToken)
        tokenState.value = TokenSnapshot(accessToken, username, userId)
    }

    fun clearSession() {
        secureTokenStore.clear()
        tokenHolder.update(null)
        tokenState.value = TokenSnapshot("", "", "")
    }

    fun currentRefreshToken(): String? = secureTokenStore.refreshToken()

    fun webResumeToken(): String? = secureTokenStore.webResumeToken()

    val webResumeSessionLinked: StateFlow<Boolean> = secureTokenStore.webResumeSessionLinked
    val webResumeSessionRevision: StateFlow<Long> = secureTokenStore.webResumeSessionRevision

    fun saveWebResumeToken(token: String) = secureTokenStore.saveWebResumeToken(token)

    fun clearWebResumeToken() = secureTokenStore.clearWebResumeToken()

    val pendingWebResumeLink: StateFlow<SecureTokenStore.WebResumeLink?> = secureTokenStore.pendingWebResumeLink

    fun currentWebResumeLink(): SecureTokenStore.WebResumeLink? = secureTokenStore.pendingWebResumeLink()

    fun createWebResumeLinkCode(): SecureTokenStore.WebResumeLink {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = java.security.SecureRandom()
        val code = buildString(10) { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
        return SecureTokenStore.WebResumeLink(code, System.currentTimeMillis() + WEB_RESUME_LINK_TTL_MS).also {
            secureTokenStore.savePendingWebResumeLink(it.code, it.expiresAtMs)
        }
    }

    fun completeWebResumeLink() = secureTokenStore.clearPendingWebResumeLink()

    private companion object {
        private const val BROWSER_SYNC_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        fun createBrowserVodSyncCode(): String {
            val secureRandom = java.security.SecureRandom()
            return buildString(8) {
                repeat(8) { append(BROWSER_SYNC_CODE_CHARS[secureRandom.nextInt(BROWSER_SYNC_CODE_CHARS.length)]) }
            }
        }

        const val TAG = "AppSettingsStore"
        const val WEB_RESUME_LINK_TTL_MS = 15 * 60 * 1_000L
    }
}
