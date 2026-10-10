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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

internal fun scopedWebResumeToken(token: String?, websiteUserId: String?, activeUserId: String?): String? {
    val safeToken = token?.takeIf(String::isNotBlank) ?: return null
    val savedAccount = websiteUserId?.takeIf(String::isNotBlank)
    val activeAccount = activeUserId?.takeIf(String::isNotBlank)
    return when {
        savedAccount != null && savedAccount == activeAccount -> safeToken
        savedAccount == null && activeAccount == null -> safeToken
        else -> null
    }
}

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
    private val smbCredentialStore: SmbCredentialStore,
) {
    data class ChatAppearance(
        val opacity: Float = 0f,
        val widthDp: Float = 360f,
        val cornerRadiusDp: Float = 32f,
        val side: String = "right",
        /** Fractions of the available travel range; null keeps the legacy side-centered placement. */
        val positionXFraction: Float? = null,
        val positionYFraction: Float? = null,
        val heightFraction: Float = 0.98f,
        val fadeSeconds: Int = 0,
        val fontFamily: String = "sans",
        val textColor: String = "white",
        val outlineColor: String = "black",
        val outlineWidthDp: Float = 0.75f,
        val useTwitchNameColors: Boolean = true,
        val hideGiftSubs: Boolean = false,
    )

    data class ChatTranslation(
        val enabled: Boolean = true,
        val targetLanguage: String = "en",
    )

    data class SubtitleAppearance(
        val sizeSp: Float = 28f,
        val textColor: String = "white",
        val backgroundOpacity: Float = 0.75f,
        val bottomPaddingFraction: Float = 0.1f,
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

    @Serializable
    data class ViewedVod(
        val id: String,
        val channelLogin: String,
        val title: String,
        val thumbnailUrl: String,
        val durationSeconds: Long,
        val lastViewedEpochMs: Long,
        val positionMs: Long,
    )

    @Serializable
    data class BrowserVodHistory(
        val viewerId: String,
        val importedAtMs: Long,
        val vods: List<ViewedVod>,
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
        val TV_CHAT_CORNER_RADIUS = floatPreferencesKey("tv_chat_corner_radius")
        val TV_CHAT_SIDE = stringPreferencesKey("tv_chat_side")
        val TV_CHAT_X = floatPreferencesKey("tv_chat_x_fraction")
        val TV_CHAT_Y = floatPreferencesKey("tv_chat_y_fraction")
        val TV_CHAT_HEIGHT = floatPreferencesKey("tv_chat_height_fraction")
        val TV_CHAT_FADE_SECONDS = intPreferencesKey("tv_chat_fade_seconds")
        val TV_CHAT_FONT_FAMILY = stringPreferencesKey("tv_chat_font_family")
        val TV_CHAT_TEXT_COLOR = stringPreferencesKey("tv_chat_text_color")
        val TV_CHAT_OUTLINE_COLOR = stringPreferencesKey("tv_chat_outline_color")
        val TV_CHAT_OUTLINE_WIDTH = floatPreferencesKey("tv_chat_outline_width")
        val TV_CHAT_TWITCH_NAME_COLORS = booleanPreferencesKey("tv_chat_twitch_name_colors")
        val TV_CHAT_HIDE_GIFT_SUBS = booleanPreferencesKey("tv_chat_hide_gift_subs")
        val TV_SUBTITLE_SIZE = floatPreferencesKey("tv_subtitle_size_sp")
        val TV_SUBTITLE_TEXT_COLOR = stringPreferencesKey("tv_subtitle_text_color")
        val TV_SUBTITLE_BACKGROUND_OPACITY = floatPreferencesKey("tv_subtitle_background_opacity")
        val TV_SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("tv_subtitle_bottom_padding")
        val TV_CHAT_TRANSLATION_ENABLED = booleanPreferencesKey("tv_chat_translation_enabled")
        val TV_CHAT_TRANSLATION_TARGET = stringPreferencesKey("tv_chat_translation_target")
        val TV_VOD_SEEK_PROFILE = stringPreferencesKey("tv_vod_seek_profile")
        val TV_BROWSER_VOD_SYNC_ENABLED = booleanPreferencesKey("tv_browser_vod_sync_enabled")
        val TV_BROWSER_VOD_SYNC_CODE = stringPreferencesKey("tv_browser_vod_sync_code")
        val VIEWED_VOD_IDS = stringPreferencesKey("tv_viewed_vod_ids")
        const val BROWSER_HISTORY_PREFIX = "tv_browser_vod_history_"
        val SMB_HOST = stringPreferencesKey("tv_smb_host")
        val SMB_SHARE = stringPreferencesKey("tv_smb_share")
        val SMB_FOLDER = stringPreferencesKey("tv_smb_folder")
        val SMB_USERNAME = stringPreferencesKey("tv_smb_username")
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
            widthDp = (prefs[Keys.TV_CHAT_WIDTH] ?: 360f).coerceIn(200f, 480f),
            cornerRadiusDp = (prefs[Keys.TV_CHAT_CORNER_RADIUS] ?: 32f).coerceIn(0f, 48f),
            side = if (prefs[Keys.TV_CHAT_SIDE] == "left") "left" else "right",
            positionXFraction = prefs[Keys.TV_CHAT_X]?.coerceIn(0f, 1f),
            positionYFraction = prefs[Keys.TV_CHAT_Y]?.coerceIn(0f, 1f),
            heightFraction = (prefs[Keys.TV_CHAT_HEIGHT] ?: 0.98f).coerceIn(0.2f, 0.98f),
            fadeSeconds = (prefs[Keys.TV_CHAT_FADE_SECONDS] ?: 0).coerceIn(0, 120),
            fontFamily = prefs[Keys.TV_CHAT_FONT_FAMILY].orEmpty().takeIf { it in setOf("sans", "serif", "mono") } ?: "sans",
            textColor = prefs[Keys.TV_CHAT_TEXT_COLOR].orEmpty().takeIf { it in setOf("white", "warm", "yellow") } ?: "white",
            outlineColor = prefs[Keys.TV_CHAT_OUTLINE_COLOR].orEmpty().takeIf { it in setOf("black", "navy", "white") } ?: "black",
            outlineWidthDp = (prefs[Keys.TV_CHAT_OUTLINE_WIDTH] ?: 0.75f).coerceIn(0f, 6f),
            useTwitchNameColors = prefs[Keys.TV_CHAT_TWITCH_NAME_COLORS] ?: true,
            hideGiftSubs = prefs[Keys.TV_CHAT_HIDE_GIFT_SUBS] ?: false,
        )
    }

    suspend fun updateChatAppearance(transform: (ChatAppearance) -> ChatAppearance) {
        val current = chatAppearance.first()
        val next = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.TV_CHAT_OPACITY] = next.opacity.coerceIn(0f, 1f)
            prefs[Keys.TV_CHAT_WIDTH] = next.widthDp.coerceIn(200f, 480f)
            prefs[Keys.TV_CHAT_CORNER_RADIUS] = next.cornerRadiusDp.coerceIn(0f, 48f)
            prefs[Keys.TV_CHAT_SIDE] = if (next.side == "left") "left" else "right"
            next.positionXFraction?.let { prefs[Keys.TV_CHAT_X] = it.coerceIn(0f, 1f) }
                ?: prefs.remove(Keys.TV_CHAT_X)
            next.positionYFraction?.let { prefs[Keys.TV_CHAT_Y] = it.coerceIn(0f, 1f) }
                ?: prefs.remove(Keys.TV_CHAT_Y)
            prefs[Keys.TV_CHAT_HEIGHT] = next.heightFraction.coerceIn(0.2f, 0.98f)
            prefs[Keys.TV_CHAT_FADE_SECONDS] = next.fadeSeconds.coerceIn(0, 120)
            prefs[Keys.TV_CHAT_FONT_FAMILY] = next.fontFamily.takeIf { it in setOf("sans", "serif", "mono") } ?: "sans"
            prefs[Keys.TV_CHAT_TEXT_COLOR] = next.textColor.takeIf { it in setOf("white", "warm", "yellow") } ?: "white"
            prefs[Keys.TV_CHAT_OUTLINE_COLOR] = next.outlineColor.takeIf { it in setOf("black", "navy", "white") } ?: "black"
            prefs[Keys.TV_CHAT_OUTLINE_WIDTH] = next.outlineWidthDp.coerceIn(0f, 6f)
            prefs[Keys.TV_CHAT_TWITCH_NAME_COLORS] = next.useTwitchNameColors
            prefs[Keys.TV_CHAT_HIDE_GIFT_SUBS] = next.hideGiftSubs
        }
    }

    val subtitleAppearance: Flow<SubtitleAppearance> = context.dataStore.data.map { prefs ->
        SubtitleAppearance(
            sizeSp = (prefs[Keys.TV_SUBTITLE_SIZE] ?: 28f).coerceIn(20f, 40f),
            textColor = prefs[Keys.TV_SUBTITLE_TEXT_COLOR].orEmpty().takeIf { it in setOf("white", "yellow", "cyan") } ?: "white",
            backgroundOpacity = (prefs[Keys.TV_SUBTITLE_BACKGROUND_OPACITY] ?: 0.75f).coerceIn(0f, 1f),
            bottomPaddingFraction = (prefs[Keys.TV_SUBTITLE_BOTTOM_PADDING] ?: 0.1f).coerceIn(0.05f, 0.25f),
        )
    }

    suspend fun updateSubtitleAppearance(transform: (SubtitleAppearance) -> SubtitleAppearance) {
        val next = transform(subtitleAppearance.first())
        context.dataStore.edit { prefs ->
            prefs[Keys.TV_SUBTITLE_SIZE] = next.sizeSp.coerceIn(20f, 40f)
            prefs[Keys.TV_SUBTITLE_TEXT_COLOR] = next.textColor.takeIf { it in setOf("white", "yellow", "cyan") } ?: "white"
            prefs[Keys.TV_SUBTITLE_BACKGROUND_OPACITY] = next.backgroundOpacity.coerceIn(0f, 1f)
            prefs[Keys.TV_SUBTITLE_BOTTOM_PADDING] = next.bottomPaddingFraction.coerceIn(0.05f, 0.25f)
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

    val smbShareSettings: Flow<SmbShareSettings> = context.dataStore.data.map { prefs ->
        SmbShareSettings(
            host = prefs[Keys.SMB_HOST] ?: "192.168.0.102",
            share = prefs[Keys.SMB_SHARE] ?: "media",
            folder = prefs[Keys.SMB_FOLDER] ?: "shared",
            username = prefs[Keys.SMB_USERNAME].orEmpty(),
        )
    }

    suspend fun saveSmbShareSettings(value: SmbShareSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SMB_HOST] = value.host.trim()
            prefs[Keys.SMB_SHARE] = value.share.trim().trim('/')
            prefs[Keys.SMB_FOLDER] = value.folder.trim().trim('/')
            prefs[Keys.SMB_USERNAME] = value.username.trim()
        }
    }

    fun smbPassword(): String = smbCredentialStore.password()
    fun saveSmbPassword(password: String) = smbCredentialStore.savePassword(password)

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

    /** Imported Twitch-browser history remains isolated by Twitch viewer ID. */
    val browserVodHistories: Flow<List<BrowserVodHistory>> = context.dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val viewerId = key.name.takeIf { it.startsWith(Keys.BROWSER_HISTORY_PREFIX) }
                ?.removePrefix(Keys.BROWSER_HISTORY_PREFIX)
                ?.takeIf { id -> id.isNotBlank() && id.all(Char::isDigit) }
                ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            runCatching { JSON.decodeFromString<BrowserVodHistory>(encoded) }
                .getOrNull()
                ?.takeIf { it.viewerId == viewerId }
        }
    }

    suspend fun saveBrowserVodHistory(viewerId: String, vods: List<ViewedVod>) {
        require(viewerId.isNotBlank() && viewerId.all(Char::isDigit))
        val safeVods = vods.asSequence()
            .filter { it.id.isNotBlank() && it.id.all(Char::isDigit) }
            .distinctBy { it.id }
            .take(MAX_BROWSER_HISTORY_VODS)
            .toList()
        val history = BrowserVodHistory(viewerId, System.currentTimeMillis(), safeVods)
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(Keys.BROWSER_HISTORY_PREFIX + viewerId)] = JSON.encodeToString(history)
        }
    }

    suspend fun browserVodPosition(viewerId: String?, vodId: String): Long {
        val safeViewerId = viewerId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return 0L
        return browserVodHistories.first()
            .firstOrNull { it.viewerId == safeViewerId }
            ?.vods
            ?.firstOrNull { it.id == vodId }
            ?.positionMs
            ?: 0L
    }

    suspend fun vodSubtitleUri(vodId: String): String? =
        context.dataStore.data.first()[stringPreferencesKey("tv_vod_subtitle_$vodId")]

    suspend fun saveVodSubtitleUri(vodId: String, uri: String?) {
        context.dataStore.edit { prefs ->
            val key = stringPreferencesKey("tv_vod_subtitle_$vodId")
            if (uri == null) prefs.remove(key) else prefs[key] = uri
        }
    }

    suspend fun vodSubtitleTrack(vodId: String): String? =
        context.dataStore.data.first()[stringPreferencesKey("tv_vod_subtitle_track_$vodId")]

    suspend fun saveVodSubtitleTrack(vodId: String, trackKey: String) {
        context.dataStore.edit { prefs -> prefs[stringPreferencesKey("tv_vod_subtitle_track_$vodId")] = trackKey }
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

    fun currentUserId(): String? = secureTokenStore.userId()

    fun webResumeToken(): String? = secureTokenStore.webResumeToken()

    fun webResumeUserId(): String? = secureTokenStore.webResumeUserId()

    /** Return the website credential only when it belongs to the active app account. */
    fun webResumeTokenForUser(userId: String?): String? =
        scopedWebResumeToken(webResumeToken(), webResumeUserId(), userId)

    val webResumeSessionLinked: StateFlow<Boolean> = secureTokenStore.webResumeSessionLinked
    val webResumeSessionRevision: StateFlow<Long> = secureTokenStore.webResumeSessionRevision

    fun saveWebResumeToken(token: String, userId: String? = null) = secureTokenStore.saveWebResumeToken(token, userId)

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
        const val MAX_BROWSER_HISTORY_VODS = 100
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
