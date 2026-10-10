package com.puretv.twitch.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.puretv.twitch.core.adblock.AdBlockStrategy
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.data.BrowserVodSyncServer
import com.puretv.twitch.tv.data.SmbShareSettings
import com.puretv.twitch.tv.data.SmbVodDownloads
import com.puretv.twitch.tv.ui.SettingsViewModel
import com.puretv.twitch.tv.ui.components.ExpressiveIcons
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvDivider
import com.puretv.twitch.tv.ui.components.TvChatStylePreview
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.components.TvExpressiveIconButton
import com.puretv.twitch.tv.ui.components.TvFilterChip
import com.puretv.twitch.tv.ui.components.TvPageTitle
import com.puretv.twitch.tv.ui.components.TvPanel
import com.puretv.twitch.tv.ui.components.tvFocusClickable
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType
import com.puretv.twitch.tv.ui.theme.ThemeVariant
import com.puretv.twitch.tv.ui.theme.themeColors
import com.puretv.twitch.tv.update.TvUpdateManager
import com.puretv.twitch.tv.update.TvUpdateState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * SECTION 07.2 / 10.4: focusable settings rows mirroring the phone app's
 * `SettingsScreen` content (colour, preferred quality, ad-block
 * toggle/strategy, account/sign-out, software update) organized into short,
 * remote-selectable categories so focus and scroll state remain local.
 *
 * There is no shape-intensity control here: TV's theme exposes no shape
 * dial, and dialing corner roundness with a remote is not a meaningful
 * interaction, so that desktop-only panel has no TV counterpart.
 */
@Composable
fun TvSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val updateManager = koinInject<TvUpdateManager>()
    val updateState by updateManager.state.collectAsState()
    val c = PureTvTvTheme.colors
    var selectedCategory by rememberSaveable { mutableStateOf(TvSettingsCategory.APPEARANCE) }
    val categoryStateHolder = rememberSaveableStateHolder()

    // The colour picker writes straight to the store: it's a pure presentation
    // setting with no ViewModel-side validation, same class of write as
    // SettingsViewModel's own setters underneath, just without a dedicated
    // method (mirrors desktop's SettingsContent.kt ColourPanel).
    val settingsStore = koinInject<AppSettingsStore>()
    val vodRepository = koinInject<VodRepository>()
    val browserVodSyncServer = koinInject<BrowserVodSyncServer>()
    val smbVodDownloads = koinInject<SmbVodDownloads>()
    val scope = rememberCoroutineScope()
    var showVodResumeProof by remember { mutableStateOf(false) }
    var showBrowserHistorySync by remember { mutableStateOf(false) }
    val chatAppearance by settingsStore.chatAppearance.collectAsState(initial = AppSettingsStore.ChatAppearance())
    val chatTranslation by settingsStore.chatTranslation.collectAsState(initial = AppSettingsStore.ChatTranslation())
    val vodSeekProfile by settingsStore.vodSeekProfile.collectAsState(initial = AppSettingsStore.VodSeekProfile.Standard)
    val smbSettings by settingsStore.smbShareSettings.collectAsState(initial = SmbShareSettings())
    var smbHost by remember(smbSettings.host) { mutableStateOf(smbSettings.host) }
    var smbShare by remember(smbSettings.share) { mutableStateOf(smbSettings.share) }
    var smbFolder by remember(smbSettings.folder) { mutableStateOf(smbSettings.folder) }
    var smbUsername by remember(smbSettings.username) { mutableStateOf(smbSettings.username) }
    var smbPassword by remember { mutableStateOf("") }
    var smbMessage by remember { mutableStateOf<String?>(null) }
    var smbBusy by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize().background(c.surface)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(216.dp)
                .background(c.surfaceLow)
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                TvExpressiveIconButton(
                    icon = ExpressiveIcons.Back,
                    contentDescription = "Back",
                    onClick = onBack,
                    boxSize = 48.dp,
                    iconSize = 22.dp,
                )
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
            }
            TvSettingsCategory.entries.forEach { category ->
                SettingsCategoryButton(
                    category = category,
                    selected = category == selectedCategory,
                    onClick = { selectedCategory = category },
                )
            }
        }

        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxHeight().width(1.dp).background(c.outlineVariant.copy(alpha = 0.55f)),
        )

        categoryStateHolder.SaveableStateProvider(selectedCategory.name) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, top = 24.dp, end = 32.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TvPageTitle(selectedCategory.title)
                    Text(selectedCategory.description, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }

                when (selectedCategory) {
                    TvSettingsCategory.APPEARANCE -> TvPanel(padding = 24.dp) {
                        ColourSection(
                            currentVariant = ThemeVariant.fromKey(state.settings.theme),
                            onSelect = { variant -> scope.launch { settingsStore.update { it.copy(theme = variant.key) } } },
                        )
                    }
                    TvSettingsCategory.PLAYBACK -> {
                        TvPanel(padding = 24.dp) {
                            StreamQualitySection(
                                selected = state.settings.preferredQuality,
                                onSelect = viewModel::setPreferredQuality,
                            )
                        }
                        TvPanel(padding = 24.dp) {
                            VodSeekSection(
                                selected = vodSeekProfile,
                                onSelect = { profile -> scope.launch { settingsStore.saveVodSeekProfile(profile) } },
                            )
                        }
                    }
                    TvSettingsCategory.CHAT -> TvPanel(padding = 20.dp) {
                        ChatAppearanceSection(
                            settings = state.settings,
                            appearance = chatAppearance,
                            translation = chatTranslation,
                            onFontSize = { size -> scope.launch { settingsStore.update { it.copy(chatFontSize = size) } } },
                            onProviderChange = { update -> scope.launch { settingsStore.update(update) } },
                            onAppearanceChange = { update -> scope.launch { settingsStore.updateChatAppearance(update) } },
                            onTranslationChange = { update -> scope.launch { settingsStore.updateChatTranslation(update) } },
                        )
                    }
                    TvSettingsCategory.NETWORK -> {
                        TvPanel(padding = 24.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("SMB VOD downloads", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                                Text("Share video bundles at \\\\${smbHost}\\${smbShare}\\${smbFolder}. Leave username blank for guest access. Password is stored encrypted; leave it blank to keep the saved password.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SmbSettingField("Server", smbHost, { smbHost = it }, Modifier.weight(1f))
                                    SmbSettingField("Share", smbShare, { smbShare = it }, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SmbSettingField("Folder", smbFolder, { smbFolder = it }, Modifier.weight(1f))
                                    SmbSettingField("Username (optional)", smbUsername, { smbUsername = it }, Modifier.weight(1f))
                                }
                                SmbSettingField("Password (optional)", smbPassword, { smbPassword = it }, Modifier.fillMaxWidth(), password = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TvExpressiveButton(
                                        text = if (smbBusy) "Connecting…" else "Save and test share",
                                        enabled = !smbBusy,
                                        onClick = {
                                            smbBusy = true
                                            scope.launch {
                                                runCatching {
                                                    settingsStore.saveSmbShareSettings(SmbShareSettings(smbHost, smbShare, smbFolder, smbUsername))
                                                    if (smbPassword.isNotBlank()) settingsStore.saveSmbPassword(smbPassword)
                                                    smbVodDownloads.testConnection()
                                                }.onSuccess { smbMessage = it; smbPassword = "" }
                                                    .onFailure { smbMessage = it.message ?: "Could not connect to SMB share" }
                                                smbBusy = false
                                            }
                                        },
                                    )
                                }
                                smbMessage?.let { Text(it, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        TvPanel(padding = 24.dp) {
                            AdBlockSection(
                                enabled = state.settings.adBlockEnabled,
                                onEnabledChange = viewModel::setAdBlockEnabled,
                                strategyKey = state.settings.adBlockStrategy,
                                onStrategySelect = viewModel::setAdBlockStrategy,
                            )
                        }
                        TvPanel(padding = 24.dp) {
                            TwitchVodResumeSection(
                                sessionLinked = !settingsStore.webResumeToken().isNullOrBlank(),
                                onTest = { showVodResumeProof = true },
                                onForget = {
                                    settingsStore.clearWebResumeToken()
                                    clearTwitchWebsiteCookies()
                                },
                            )
                        }
                        TvPanel(padding = 24.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Twitch browser history", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                                Text(
                                    "Import recent VOD checkpoints from Twitch in your phone's browser. " +
                                        "This uses a one-time bookmarklet; a timestamped VOD link is available as a fallback.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = c.onSurfaceVariant,
                                )
                                TvExpressiveButton(
                                    text = "Sync from Twitch browser",
                                    onClick = { showBrowserHistorySync = true },
                                )
                            }
                        }
                    }
                    TvSettingsCategory.ACCOUNT -> {
                        TvPanel(padding = 24.dp) {
                            AccountSection(
                                isLoggedIn = state.isLoggedIn,
                                username = state.loginUsername,
                                onLogOut = viewModel::logOut,
                            )
                        }
                        TvPanel(padding = 24.dp) {
                            SoftwareUpdateSection(
                                currentVersion = updateManager.currentVersionName,
                                state = updateState,
                                onCheck = { updateManager.checkForUpdates(force = true) },
                                onInstall = { info -> updateManager.downloadAndInstall(info) },
                                onGrantConsent = { updateManager.openInstallSettings() },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showVodResumeProof) {
        TwitchVodResumeProofDialog(
            settingsStore = settingsStore,
            vodRepository = vodRepository,
            onDismiss = { showVodResumeProof = false },
        )
    }
    if (showBrowserHistorySync) {
        BrowserVodHistorySyncDialog(
            server = browserVodSyncServer,
            onDismiss = { showBrowserHistorySync = false },
        )
    }
}

@Composable
private fun SmbSettingField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    val c = PureTvTvTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private enum class TvSettingsCategory(
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    APPEARANCE("Appearance", "Choose the app palette.", ExpressiveIcons.Settings),
    PLAYBACK("Playback", "Stream quality and VOD seeking.", ExpressiveIcons.Play),
    CHAT("Chat", "Overlay, translation, and message style.", ExpressiveIcons.Chat),
    NETWORK("Network & Ads", "Ad blocking and Twitch VOD resume.", ExpressiveIcons.Shield),
    ACCOUNT("Account & Updates", "Twitch account and app version.", ExpressiveIcons.Account),
}

@Composable
private fun SettingsCategoryButton(
    category: TvSettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = PureTvTvTheme.colors
    val interaction = remember(category) { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .tvFocusClickable(
                interaction = interaction,
                onClick = onClick,
                restRadius = 14.dp,
                focusRadius = 16.dp,
                color = if (selected) c.surfaceHigh else Color.Transparent,
                focusColor = c.primary,
                scale = 1f,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            category.icon,
            contentDescription = null,
            tint = when {
                focused -> c.onPrimary
                selected -> c.primary
                else -> c.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Text(
            category.title,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> c.onPrimary
                selected -> c.onSurface
                else -> c.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun ChatAppearanceSection(
    settings: com.puretv.twitch.core.model.AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    translation: AppSettingsStore.ChatTranslation,
    onFontSize: (Float) -> Unit,
    onProviderChange: ((com.puretv.twitch.core.model.AppSettings) -> com.puretv.twitch.core.model.AppSettings) -> Unit,
    onAppearanceChange: ((AppSettingsStore.ChatAppearance) -> AppSettingsStore.ChatAppearance) -> Unit,
    onTranslationChange: ((AppSettingsStore.ChatTranslation) -> AppSettingsStore.ChatTranslation) -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Chat overlay", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TvChatStylePreview(
                settings = settings,
                appearance = appearance,
                modifier = Modifier.weight(1.05f).height(280.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChatOptionGroup(
                            "Font size",
                            settings.chatFontSize.toString(),
                            listOf("8.0" to "8 sp", "9.0" to "9 sp", "10.0" to "10 sp", "12.0" to "12 sp", "14.0" to "14 sp"),
                        ) { onFontSize(it.toFloat()) }
                        ChatOptionGroup(
                            "Font family",
                            appearance.fontFamily,
                            listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono"),
                        ) { value -> onAppearanceChange { it.copy(fontFamily = value) } }
                        ChatOptionGroup(
                            "Message text",
                            appearance.textColor,
                            listOf("white" to "White", "warm" to "Warm", "yellow" to "Yellow"),
                        ) { value -> onAppearanceChange { it.copy(textColor = value) } }
                        ChatOptionGroup(
                            "Text outline",
                            appearance.outlineWidthDp.toString(),
                            listOf(
                                "0.0" to "Off", "0.75" to "Light", "1.0" to "Thin",
                                "1.5" to "Medium", "2.5" to "Strong", "4.0" to "Heavier", "6.0" to "Maximum",
                            ),
                        ) { value -> onAppearanceChange { it.copy(outlineWidthDp = value.toFloat()) } }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChatOptionGroup(
                            "Chat background",
                            appearance.opacity.toString(),
                            listOf("0.0" to "Clear", "0.1" to "10%", "0.2" to "20%", "0.35" to "35%", "0.5" to "50%", "1.0" to "Solid"),
                        ) { value -> onAppearanceChange { it.copy(opacity = value.toFloat()) } }
                        ChatOptionGroup(
                            "Outline color",
                            appearance.outlineColor,
                            listOf("black" to "Black", "navy" to "Navy", "white" to "White"),
                        ) { value -> onAppearanceChange { it.copy(outlineColor = value) } }
                        ChatOptionGroup(
                            "Username colors",
                            if (appearance.useTwitchNameColors) "twitch" else "text",
                            listOf("twitch" to "Twitch", "text" to "Match text"),
                        ) { value -> onAppearanceChange { it.copy(useTwitchNameColors = value == "twitch") } }
                        ChatOptionGroup(
                            "Chat placement",
                            appearance.side,
                            listOf("right" to "Right", "left" to "Left"),
                        ) { value -> onAppearanceChange { it.copy(side = value) } }
                        ChatOptionGroup(
                            "Panel corners",
                            appearance.cornerRadiusDp.toString(),
                            listOf(
                                "0.0" to "Square", "8.0" to "Subtle", "16.0" to "Soft",
                                "24.0" to "Rounded", "32.0" to "Current", "48.0" to "Extra round",
                            ),
                        ) { value -> onAppearanceChange { it.copy(cornerRadiusDp = value.toFloat()) } }
                        ChatOptionGroup(
                            "Message fade",
                            appearance.fadeSeconds.toString(),
                            listOf("0" to "Off", "10" to "10 sec", "30" to "30 sec", "60" to "60 sec"),
                        ) { value -> onAppearanceChange { it.copy(fadeSeconds = value.toInt()) } }
                        ChatOptionGroup(
                            "Gift subs",
                            if (appearance.hideGiftSubs) "hide" else "show",
                            listOf("show" to "Show", "hide" to "Hide"),
                        ) { value -> onAppearanceChange { it.copy(hideGiftSubs = value == "hide") } }
                        ChatOptionGroup(
                            "Chat width",
                            appearance.widthDp.toString(),
                            listOf(
                                "200.0" to "Narrowest", "240.0" to "Narrow", "280.0" to "Compact",
                                "360.0" to "Medium", "420.0" to "Wide", "480.0" to "Widest",
                            ),
                        ) { value -> onAppearanceChange { it.copy(widthDp = value.toFloat()) } }
                        ChatOptionGroup(
                            "Translate chat",
                            if (translation.enabled) "on" else "off",
                            listOf("on" to "On", "off" to "Off"),
                        ) { value -> onTranslationChange { it.copy(enabled = value == "on") } }
                        ChatOptionGroup(
                            "Translate to",
                            translation.targetLanguage,
                            listOf(
                                "en" to "English", "es" to "Español", "fr" to "Français",
                                "de" to "Deutsch", "ja" to "日本語", "ko" to "한국어",
                                "pt" to "Português", "ru" to "Русский", "zh-CN" to "中文",
                                "ar" to "العربية", "hi" to "हिन्दी",
                            ),
                        ) { value -> onTranslationChange { it.copy(targetLanguage = value) } }
                        Text(
                            "Foreign chat is detected automatically. Message text is sent to Google Translate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Emote providers", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(
                                    "7TV" to settings.show7tvEmotes,
                                    "BTTV" to settings.showBttvEmotes,
                                    "FFZ" to settings.showFfzEmotes,
                                ).forEach { (label, selected) ->
                                    TvFilterChip(label, selected, onClick = {
                                        onProviderChange { current ->
                                            when (label) {
                                                "7TV" -> current.copy(show7tvEmotes = !current.show7tvEmotes)
                                                "BTTV" -> current.copy(showBttvEmotes = !current.showBttvEmotes)
                                                else -> current.copy(showFfzEmotes = !current.showFfzEmotes)
                                            }
                                        }
                                    })
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text("Also show", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvFilterChip("Badges", settings.showBadges, onClick = {
                                    onProviderChange { it.copy(showBadges = !it.showBadges) }
                                })
                                TvFilterChip("Timestamps", settings.chatTimestamps, onClick = {
                                    onProviderChange { it.copy(chatTimestamps = !it.chatTimestamps) }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatOptionGroup(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            choices.forEach { (value, title) ->
                TvFilterChip(title, selected == value, onClick = { onSelect(value) })
            }
        }
    }
}

// ── Colour ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourSection(currentVariant: ThemeVariant, onSelect: (ThemeVariant) -> Unit) {
    val c = PureTvTvTheme.colors
    Column {
        Text("Colour", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "The whole scheme comes from one source colour, the same way Material's dynamic colour " +
                "does. Pick a palette and every surface, container and accent retones together.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ThemeVariant.entries.forEach { variant ->
                ColourSwatch(variant = variant, selected = variant == currentVariant, onClick = { onSelect(variant) })
            }
        }
    }
}

/**
 * One palette tile. Always previews its OWN colours, read from [themeColors],
 * never the currently active theme, so every swatch shows what picking it
 * would actually look like.
 */
@Composable
private fun ColourSwatch(variant: ThemeVariant, selected: Boolean, onClick: () -> Unit) {
    val shapes = PureTvTvTheme.shapes
    val vc = themeColors[variant]!!
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(240.dp)
            .tvFocusClickable(
                interaction = interaction,
                onClick = onClick,
                restRadius = shapes.sm,
                focusRadius = shapes.cardFocus,
                color = if (selected) vc.surfaceHigh else Color.Transparent,
                focusColor = vc.primary,
                scale = 1.04f,
                idleBorderColor = if (selected) vc.outline else Color.Transparent,
            )
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(shapes.sm))
                .background(vc.surfaceLow)
                .padding(20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(2f).height(60.dp).clip(RoundedCornerShape(shapes.sm)).background(vc.primary),
                )
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(shapes.sm)).background(vc.secondaryContainer),
                )
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(shapes.sm)).background(vc.tertiaryContainer),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selected) {
                    Icon(ExpressiveIcons.Check, contentDescription = null, tint = vc.primary, modifier = Modifier.size(22.dp))
                }
                Text(variant.displayName, style = MaterialTheme.typography.titleMedium, color = vc.onSurface)
            }
            Spacer(Modifier.height(4.dp))
            Text(variant.seed, style = PureTvTvType.data, color = vc.onSurfaceVariant)
        }
    }
}

// ── Stream quality ──────────────────────────────────────────────────────

@Composable
private fun StreamQualitySection(selected: String, onSelect: (StreamQuality) -> Unit) {
    val c = PureTvTvTheme.colors
    Column {
        Text("Stream quality", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StreamQuality.entries.forEach { quality ->
                TvFilterChip(
                    label = quality.label,
                    selected = selected.equals(quality.name, ignoreCase = true),
                    onClick = { onSelect(quality) },
                )
            }
        }
    }
}

@Composable
private fun VodSeekSection(
    selected: AppSettingsStore.VodSeekProfile,
    onSelect: (AppSettingsStore.VodSeekProfile) -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("VOD seeking", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Text(
            "Press UP during playback to show the position. Hold LEFT or RIGHT to increase the jump size.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppSettingsStore.VodSeekProfile.entries.forEach { profile ->
                TvFilterChip(profile.label, selected == profile, onClick = { onSelect(profile) })
            }
        }
    }
}

@Composable
private fun TwitchVodResumeSection(
    sessionLinked: Boolean,
    onTest: () -> Unit,
    onForget: () -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Twitch VOD resume", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Text(
            "Sign into Twitch on this TV so it can read your recent watched VODs and resume checkpoints. " +
                "The app uses Twitch's private website history query; Twitch can change this interface without notice. " +
                "The farther local or Twitch checkpoint wins.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
        )
        if (sessionLinked) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Twitch website session saved securely on this TV.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.primary,
                )
                TvExpressiveButton(text = "Forget TV session", onClick = onForget, style = TvButtonStyle.Outlined)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvExpressiveButton(text = "Sign in and test on this TV", onClick = onTest)
        }
    }
}

// ── Ad blocking ─────────────────────────────────────────────────────────

@Composable
private fun AdBlockSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    strategyKey: String,
    onStrategySelect: (AdBlockStrategy) -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column {
        Text("Ad blocking", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Enabled", style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(20.dp))
        TvDivider()
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AdBlockStrategy.entries.forEach { strategy ->
                TvFilterChip(
                    label = strategyLabel(strategy),
                    selected = strategyKey.equals(strategy.name, ignoreCase = true),
                    onClick = { onStrategySelect(strategy) },
                )
            }
        }
    }
}

private fun strategyLabel(strategy: AdBlockStrategy): String = when (strategy) {
    AdBlockStrategy.PROXY_PRIMARY -> "Proxy"
    AdBlockStrategy.MANIFEST_REWRITE_ONLY -> "Manifest rewrite"
    AdBlockStrategy.DISABLED -> "Disabled"
}

// ── Account ─────────────────────────────────────────────────────────────

@Composable
private fun AccountSection(isLoggedIn: Boolean, username: String?, onLogOut: () -> Unit) {
    val c = PureTvTvTheme.colors
    Column {
        Text("Account", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Spacer(Modifier.height(20.dp))
        if (isLoggedIn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Signed in as ${username ?: "your Twitch account"}", style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
                TvExpressiveButton(text = "Sign out", onClick = onLogOut, style = TvButtonStyle.Outlined, icon = ExpressiveIcons.SignOut)
            }
        } else {
            Text(
                "Not signed in. Sign in from the nav rail to follow channels and chat with your account.",
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurfaceVariant,
            )
        }
    }
}

// ── Software update ─────────────────────────────────────────────────────

@Composable
private fun SoftwareUpdateSection(
    currentVersion: String,
    state: TvUpdateState,
    onCheck: () -> Unit,
    onInstall: (com.puretv.twitch.tv.update.TvUpdateInfo) -> Unit,
    onGrantConsent: () -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column {
        Text("Software update", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        Spacer(Modifier.height(20.dp))
        Text("Installed version $currentVersion", style = PureTvTvType.data, color = c.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        when (state) {
            is TvUpdateState.Available -> {
                Text("Update available: ${state.info.versionName}", style = PureTvTvType.data, color = c.primary)
                if (state.info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.info.notes.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                TvExpressiveButton(text = "Download and install", onClick = { onInstall(state.info) }, icon = ExpressiveIcons.Download)
            }
            is TvUpdateState.Downloading -> {
                Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
            }
            TvUpdateState.Installing ->
                Text("Starting installer…", style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
            TvUpdateState.Checking ->
                Text("Checking for updates…", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            TvUpdateState.UpToDate -> {
                Text("You're on the latest version.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                TvExpressiveButton(text = "Check again", onClick = onCheck, style = TvButtonStyle.Outlined, icon = ExpressiveIcons.Refresh)
            }
            is TvUpdateState.Error -> {
                Text(state.message, style = MaterialTheme.typography.bodyLarge, color = c.error)
                Spacer(Modifier.height(16.dp))
                TvExpressiveButton(text = "Try again", onClick = onCheck, style = TvButtonStyle.Outlined, icon = ExpressiveIcons.Refresh)
            }
            is TvUpdateState.NeedsInstallConsent -> {
                Text(
                    "Update ${state.info.versionName} is ready to install.",
                    style = PureTvTvType.data,
                    color = c.primary,
                )
                Spacer(Modifier.height(8.dp))
                if (state.settingsResolvable) {
                    // Nothing has been downloaded yet, so leaving for Settings
                    // here costs nothing even if this app is stopped on the way.
                    // Coming back re-checks consent and continues on its own.
                    Text(
                        "Android needs your permission before PureTV can install it. " +
                            "Choose Allow on the next screen, then come back and the update will continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TvExpressiveButton(text = "Allow and continue", onClick = onGrantConsent, icon = ExpressiveIcons.Download)
                } else {
                    // Fire OS publishes no per-app consent screen, so a button
                    // would go nowhere. Directions are the only honest option.
                    Text(
                        "This device has no in-app permission screen. Open Settings, " +
                            "then My Fire TV, then Developer options, and turn on " +
                            "Install unknown apps for PureTV. Then come back and press Check for updates.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TvExpressiveButton(text = "Check for updates", onClick = onCheck, style = TvButtonStyle.Outlined, icon = ExpressiveIcons.Refresh)
                }
            }
            TvUpdateState.Idle ->
                TvExpressiveButton(text = "Check for updates", onClick = onCheck, style = TvButtonStyle.Outlined, icon = ExpressiveIcons.Refresh)
        }
    }
}
