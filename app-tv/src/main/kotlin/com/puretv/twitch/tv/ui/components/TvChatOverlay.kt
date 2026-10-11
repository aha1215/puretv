package com.puretv.twitch.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.Badge
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import com.puretv.twitch.core.stream.decodeHtmlEntities
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.R
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** One process-wide Coil pipeline shares decoded and disk-cached emote assets across chat surfaces. */
private object TvEmoteImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: android.content.Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components {
                // On Android 9+, delegate GIF/WebP frame playback to ImageDecoder.
                // Coil's Movie-backed GifDecoder decodes frames in software and can
                // consume substantial CPU when a TV chat rail has many animated emotes.
                if (android.os.Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                add(GifDecoder.Factory())
            }
            .build()
            .also { instance = it }
    }
}

enum class TvChatEditDirection { Left, Right, Up, Down }
enum class TvChatEditMode { Reposition, Resize, FontSize }

fun adjustTvChatAppearance(
    appearance: AppSettingsStore.ChatAppearance,
    direction: TvChatEditDirection,
    resizeMode: Boolean,
    availableWidthDp: Float,
    availableHeightDp: Float,
): AppSettingsStore.ChatAppearance {
    val width = appearance.widthDp.coerceAtMost(availableWidthDp)
    val height = availableHeightDp * appearance.heightFraction.coerceIn(0.2f, 0.98f)
    val x = appearance.positionXFraction ?: if (appearance.side == "left") 0f else 1f
    val y = appearance.positionYFraction ?: 0.5f

    if (resizeMode) {
        return when (direction) {
            TvChatEditDirection.Left -> appearance.copy(widthDp = (width - 24f).coerceAtLeast(200f))
            TvChatEditDirection.Right -> appearance.copy(widthDp = (width + 24f).coerceAtMost(minOf(480f, availableWidthDp)))
            TvChatEditDirection.Up -> appearance.copy(heightFraction = (appearance.heightFraction + 24f / availableHeightDp).coerceAtMost(0.98f))
            TvChatEditDirection.Down -> appearance.copy(heightFraction = (appearance.heightFraction - 24f / availableHeightDp).coerceAtLeast(0.2f))
        }
    }

    val maxX = (availableWidthDp - width).coerceAtLeast(0f)
    val maxY = (availableHeightDp - height).coerceAtLeast(0f)
    return when (direction) {
        TvChatEditDirection.Left -> appearance.copy(positionXFraction = (x - if (maxX > 0f) 24f / maxX else 0f).coerceAtLeast(0f))
        TvChatEditDirection.Right -> appearance.copy(positionXFraction = (x + if (maxX > 0f) 24f / maxX else 0f).coerceAtMost(1f))
        TvChatEditDirection.Up -> appearance.copy(positionYFraction = (y - if (maxY > 0f) 24f / maxY else 0f).coerceAtLeast(0f))
        TvChatEditDirection.Down -> appearance.copy(positionYFraction = (y + if (maxY > 0f) 24f / maxY else 0f).coerceAtMost(1f))
    }
}

/** Existing right-side chat rail, configurable for the ten-foot player. */
@Composable
fun TvChatOverlay(
    visible: Boolean,
    messages: List<ChatMessage>,
    appSettings: AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    instantInitialScroll: Boolean = false,
    editing: Boolean = false,
    editMode: TvChatEditMode = TvChatEditMode.Reposition,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val context = LocalContext.current
    val imageLoader = remember(context) { TvEmoteImageLoader.get(context) }
    val listState = rememberLazyListState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val visibleMessages = remember(messages, appearance.hideGiftSubs) {
        if (appearance.hideGiftSubs) messages.filterNot(::isGiftSubMessage) else messages
    }

    LaunchedEffect(appearance.fadeSeconds) {
        while (appearance.fadeSeconds > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(visibleMessages.lastOrNull()?.id, instantInitialScroll) {
        if (visibleMessages.isNotEmpty()) {
            if (instantInitialScroll) listState.scrollToItem(visibleMessages.lastIndex)
            else listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    AnimatedVisibility(
        visible = visible && appSettings.chatEnabled,
        enter = slideInHorizontally(initialOffsetX = { if (appearance.side == "left") -it else it }),
        exit = slideOutHorizontally(targetOffsetX = { if (appearance.side == "left") -it else it }),
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val panelWidth = appearance.widthDp.dp.coerceAtMost(maxWidth)
            val panelHeight = (maxHeight * appearance.heightFraction.coerceIn(0.2f, 0.98f)).coerceAtMost(maxHeight)
            val maxLeft = (maxWidth - panelWidth).coerceAtLeast(0.dp)
            val maxTop = (maxHeight - panelHeight).coerceAtLeast(0.dp)
            val xFraction = appearance.positionXFraction ?: if (appearance.side == "left") 0f else 1f
            val yFraction = appearance.positionYFraction ?: 0.5f
            val left = maxLeft * xFraction.coerceIn(0f, 1f)
            val top = maxTop * yFraction.coerceIn(0f, 1f)
            val panelShape = androidx.compose.foundation.shape.RoundedCornerShape(appearance.cornerRadiusDp.dp)
            val editHint = when (editMode) {
                TvChatEditMode.Reposition -> "MOVE  ·  Arrows reposition  ·  Center next mode  ·  Back save"
                TvChatEditMode.Resize -> "PANEL SIZE  ·  ← → width  ·  ↑ ↓ height  ·  Center next mode  ·  Back save"
                TvChatEditMode.FontSize -> "TEXT + EMOTE SIZE ${appSettings.chatFontSize.toInt()} sp  ·  ↑ larger  ·  ↓ smaller  ·  Center next mode  ·  Back save"
            }

            Column(
                modifier = Modifier
                    .offset(x = left, y = top)
                    .width(panelWidth)
                    .height(panelHeight)
                    .clip(panelShape)
                    .background(c.surfaceContainer.copy(alpha = appearance.opacity))
                    .then(if (editing) Modifier.border(2.dp, c.primary, panelShape) else Modifier)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (editing) {
                    Text(
                        text = editHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .background(c.surfaceContainer.copy(alpha = 0.96f), shapes.mdShape)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(visibleMessages, key = { it.id }) { message ->
                        val ageSeconds = ((now - message.timestamp).coerceAtLeast(0L) / 1_000L).toInt()
                        val fade = appearance.fadeSeconds
                        val alpha = if (fade == 0) 1f else (1f - ageSeconds.toFloat() / fade).coerceIn(0f, 1f)
                        TvChatMessageRow(
                            message = message,
                            appSettings = appSettings,
                            appearance = appearance,
                            alpha = alpha,
                            imageLoader = imageLoader,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvChatMessageRow(
    message: ChatMessage,
    appSettings: AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    alpha: Float,
    imageLoader: ImageLoader,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val twitchNameColor = remember(message.color, c.primary) {
        runCatching { Color(android.graphics.Color.parseColor(message.color)) }.getOrDefault(c.primary)
    }
    val messageColor = configuredChatTextColor(appearance.textColor)
    val nameColor = if (appearance.useTwitchNameColors) twitchNameColor else messageColor
    val emoteSizeSp = with(LocalDensity.current) { appearance.emoteSizeDp.dp.toSp().value }
    val usernameFontSizeSp = appearance.usernameFontSizeSp ?: appSettings.chatFontSize
    val timestamp = remember(message.timestamp) {
        runCatching {
            Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault()).format(ClockFormatter)
        }.getOrDefault("")
    }
    val (body, inlineContent) = remember(
        message.id,
        message.parsedParts,
        message.translatedMessage,
        message.displayName,
        message.badges,
        appSettings.showBadges,
        appSettings.chatTimestamps,
        appSettings.showBttvEmotes,
        appSettings.showFfzEmotes,
        appSettings.show7tvEmotes,
        appSettings.chatFontSize,
        appearance.emoteSizeDp,
        appearance.usernameFontSizeSp,
        nameColor,
        c.onSurfaceVariant,
        timestamp,
        imageLoader,
    ) {
        buildTvChatBody(
            message = message,
            appSettings = appSettings,
            nameColor = nameColor,
            timestampColor = c.onSurfaceVariant,
            timestamp = timestamp,
            imageLoader = imageLoader,
            emoteSizeSp = emoteSizeSp,
            usernameFontSizeSp = usernameFontSizeSp,
        )
    }

    OutlinedChatAnnotatedText(
        text = body,
        inlineContent = inlineContent,
        style = compactChatTextStyle(
            appSettings.chatFontSize,
            appearance.fontFamily,
            hasEmotes = message.parsedParts.any {
                it is MessagePart.TwitchEmote || it is MessagePart.ThirdPartyEmote
            },
            emoteSizeSp = emoteSizeSp,
        ),
        color = messageColor,
        appearance = appearance,
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clip(shapes.mdShape)
            .background(if (message.mentionsSelf) c.primaryContainer else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private val GiftSubMessagePattern = Regex(
    """\bgifted\s+(?:a|\d+)\s+(?:tier\s*[1-3]\s+)?subs?\s+to\b""",
    RegexOption.IGNORE_CASE,
)

private fun isGiftSubMessage(message: ChatMessage): Boolean =
    GiftSubMessagePattern.containsMatchIn(message.message)

private fun buildTvChatBody(
    message: ChatMessage,
    appSettings: AppSettings,
    nameColor: Color,
    timestampColor: Color,
    timestamp: String,
    imageLoader: ImageLoader,
    emoteSizeSp: Float,
    usernameFontSizeSp: Float,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = LinkedHashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        if (!message.isSystem) {
            if (appSettings.chatTimestamps && timestamp.isNotBlank()) {
                withStyle(SpanStyle(color = timestampColor, fontSize = 10.sp)) {
                    append(timestamp)
                    append("  ")
                }
            }
            if (appSettings.showBadges) {
                message.badges.forEachIndexed { index, badge ->
                    if (appendTvBadge(index, badge.setId, inline)) append(' ')
                }
            }
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold, fontSize = usernameFontSizeSp.coerceIn(8f, 24f).sp)) {
                append(message.displayName)
            }
            append(": ")
        }

        val translatedMessage = message.translatedMessage
        if (translatedMessage != null) {
            val decoded = decodeHtmlEntities(translatedMessage)
            val emotesByName = message.parsedParts.mapNotNull { part ->
                when (part) {
                    is MessagePart.TwitchEmote -> part.name to part
                    is MessagePart.ThirdPartyEmote -> part.name to part
                    is MessagePart.Text -> null
                }
            }.toMap()
            if (emotesByName.isEmpty()) append(decoded)
            else {
                Regex("\\s+|\\S+").findAll(decoded).forEachIndexed { index, match ->
                    val token = match.value
                    val leading = token.takeWhile { !it.isLetterOrDigit() && it != '_' }
                    val trailing = token.drop(leading.length).takeLastWhile { !it.isLetterOrDigit() && it != '_' }
                    val name = token.drop(leading.length).let { core ->
                        if (trailing.isEmpty()) core else core.dropLast(trailing.length)
                    }
                    val emote = emotesByName[name]
                    if (emote == null || name.isBlank()) append(token)
                    else {
                        append(leading)
                        when (emote) {
                            is MessagePart.TwitchEmote -> appendTvEmote(
                                key = "translated_tw_${emote.id}_$index",
                                name = emote.name,
                                url = "https://static-cdn.jtvnw.net/emoticons/v2/${emote.id}/default/dark/2.0",
                                overlays = emptyList(),
                                inline = inline,
                                imageLoader = imageLoader,
                                emoteSizeSp = emoteSizeSp,
                            )
                            is MessagePart.ThirdPartyEmote -> {
                                val enabled = when (emote.provider) {
                                    com.puretv.twitch.core.model.EmoteProvider.BTTV -> appSettings.showBttvEmotes
                                    com.puretv.twitch.core.model.EmoteProvider.FFZ -> appSettings.showFfzEmotes
                                    com.puretv.twitch.core.model.EmoteProvider.SEVENTV -> appSettings.show7tvEmotes
                                    com.puretv.twitch.core.model.EmoteProvider.TWITCH -> true
                                }
                                if (enabled) appendTvEmote(
                                    key = "translated_tp_${emote.name}_$index",
                                    name = emote.name,
                                    url = emote.url,
                                    overlays = emote.overlays,
                                    inline = inline,
                                    imageLoader = imageLoader,
                                    emoteSizeSp = emoteSizeSp,
                                ) else append(emote.name)
                            }
                            is MessagePart.Text -> append(token)
                        }
                        append(trailing)
                    }
                }
            }
        } else if (message.parsedParts.isEmpty()) {
            append(message.message)
        } else {
            message.parsedParts.forEachIndexed { index, part ->
                when (part) {
                    is MessagePart.Text -> append(part.content)
                    is MessagePart.TwitchEmote -> appendTvEmote(
                        key = "tw_${part.id}_$index",
                        name = part.name,
                        url = "https://static-cdn.jtvnw.net/emoticons/v2/${part.id}/default/dark/2.0",
                        overlays = emptyList(),
                        inline = inline,
                        imageLoader = imageLoader,
                        emoteSizeSp = emoteSizeSp,
                    )
                    is MessagePart.ThirdPartyEmote -> {
                        val enabled = when (part.provider) {
                            com.puretv.twitch.core.model.EmoteProvider.BTTV -> appSettings.showBttvEmotes
                            com.puretv.twitch.core.model.EmoteProvider.FFZ -> appSettings.showFfzEmotes
                            com.puretv.twitch.core.model.EmoteProvider.SEVENTV -> appSettings.show7tvEmotes
                            com.puretv.twitch.core.model.EmoteProvider.TWITCH -> true
                        }
                        if (enabled) appendTvEmote(
                            key = "tp_${part.name}_$index",
                            name = part.name,
                            url = part.url,
                            overlays = part.overlays,
                            inline = inline,
                            imageLoader = imageLoader,
                            emoteSizeSp = emoteSizeSp,
                        ) else append(part.name)
                    }
                }
            }
        }
    }
    return text to inline
}

private fun AnnotatedString.Builder.appendTvBadge(
    index: Int,
    setId: String,
    inline: MutableMap<String, InlineTextContent>,
): Boolean {
    val (mark, color) = chatBadgeStyle(setId) ?: return false
    val key = "badge_$index"
    appendInlineContent(key, mark.ifBlank { " " })
    inline[key] = InlineTextContent(Placeholder(0.9.em, 0.9.em, PlaceholderVerticalAlign.Center)) {
        Text(mark, style = PureTvTvType.badge.copy(fontSize = 9.sp, lineHeight = 10.sp), color = color)
    }
    return true
}

private fun AnnotatedString.Builder.appendTvEmote(
    key: String,
    name: String,
    url: String,
    overlays: List<com.puretv.twitch.core.model.EmoteLayer>,
    inline: MutableMap<String, InlineTextContent>,
    imageLoader: ImageLoader,
    emoteSizeSp: Float,
) {
    appendInlineContent(key, name.ifBlank { " " })
    inline[key] = InlineTextContent(
        placeholder = Placeholder(emoteSizeSp.sp, emoteSizeSp.sp, PlaceholderVerticalAlign.Center),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = url,
                contentDescription = name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            overlays.forEach { overlay ->
                AsyncImage(
                    model = overlay.url,
                    contentDescription = overlay.name,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun OutlinedChatAnnotatedText(
    text: AnnotatedString,
    inlineContent: Map<String, InlineTextContent>,
    style: TextStyle,
    color: Color,
    appearance: AppSettingsStore.ChatAppearance,
    maxLines: Int,
    overflow: TextOverflow,
    modifier: Modifier = Modifier,
) {
    val strokeWidth = with(LocalDensity.current) { appearance.outlineWidthDp.dp.toPx() }
    val outlineColor = configuredOutlineColor(appearance.outlineColor)
    val outlineText = remember(text, outlineColor) {
        AnnotatedString.Builder(text).apply {
            addStyle(SpanStyle(color = outlineColor), 0, text.length)
        }.toAnnotatedString()
    }
    Box(modifier) {
        if (strokeWidth > 0f) {
            Text(
                // Override colored username spans during the outline pass. Strokes in
                // bright Twitch colors close up the small bold glyphs and obscure names.
                text = outlineText,
                inlineContent = inlineContent,
                style = style.copy(color = outlineColor, drawStyle = Stroke(width = strokeWidth)),
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = text,
            inlineContent = inlineContent,
            style = style.copy(color = color, drawStyle = Fill),
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/** Small, focus-free preview that uses the same row renderer and style as playback. */
@Composable
fun TvChatStylePreview(
    settings: AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    modifier: Modifier = Modifier,
    messages: List<ChatMessage> = emptyList(),
    title: String = "Live style preview",
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val context = LocalContext.current
    val imageLoader = remember(context) { TvEmoteImageLoader.get(context) }
    val previewChat = remember(messages) { messages.ifEmpty { previewMessages() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(appearance.fadeSeconds) {
        while (appearance.fadeSeconds > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val sceneHeight = minOf(230.dp, maxHeight, maxWidth / (16f / 9f))
            val sceneWidth = sceneHeight * (16f / 9f)
            Box(Modifier.width(sceneWidth).height(sceneHeight).clip(shapes.paneShape)) {
                Image(
                    painter = painterResource(R.drawable.chat_preview_video),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    "EXAMPLE VOD  ·  01:24:36",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                        .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 8.dp, vertical = 5.dp),
                )
                val previewWidth = minOf(appearance.widthDp.dp, sceneWidth * .55f)
                val previewHeight = sceneHeight * appearance.heightFraction.coerceIn(.2f, .98f)
                val xFraction = appearance.positionXFraction ?: if (appearance.side == "left") 0f else 1f
                val yFraction = appearance.positionYFraction ?: .5f
                val previewLeft = (sceneWidth - previewWidth).coerceAtLeast(0.dp) * xFraction.coerceIn(0f, 1f)
                val previewTop = (sceneHeight - previewHeight).coerceAtLeast(0.dp) * yFraction.coerceIn(0f, 1f)
                val chatPanelShape = androidx.compose.foundation.shape.RoundedCornerShape(appearance.cornerRadiusDp.dp)
                Column(
                    modifier = Modifier.offset(x = previewLeft, y = previewTop)
                        .width(previewWidth)
                        .height(previewHeight)
                        .clip(chatPanelShape)
                        .background(c.surfaceContainer.copy(alpha = appearance.opacity))
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    previewChat.forEach { message ->
                        val ageSeconds = ((now - message.timestamp).coerceAtLeast(0L) / 1_000L).toInt()
                        val rowAlpha = if (appearance.fadeSeconds == 0) 1f
                        else (1f - ageSeconds.toFloat() / appearance.fadeSeconds).coerceIn(0f, 1f)
                        TvChatMessageRow(
                            message = message,
                            appSettings = settings,
                            appearance = appearance,
                            alpha = rowAlpha,
                            imageLoader = imageLoader,
                        )
                    }
                }
                Text(
                    "VIDEO  ·  CHAT STYLE",
                    color = Color.White.copy(alpha = .9f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
            }
        }
        Text("Adjust a setting to see the change here immediately.", style = PureTvTvType.dataSmall, color = c.onSurfaceVariant)
    }
}

private fun chatBadgeStyle(setId: String): Pair<String, Color>? {
    val color = when (setId.lowercase()) {
        "broadcaster" -> Color(0xFFFF4F64)
        "moderator" -> Color(0xFF48C774)
        "vip" -> Color(0xFFFFB84D)
        "subscriber" -> Color(0xFF9B7BFF)
        else -> return null
    }
    val mark = when (setId.lowercase()) {
        "broadcaster" -> "★"
        "moderator" -> "◆"
        "vip" -> "♛"
        "subscriber" -> "✦"
        else -> return null
    }
    return mark to color
}

@Composable
private fun compactChatTextStyle(fontSize: Float, family: String, hasEmotes: Boolean = false, emoteSizeSp: Float): TextStyle {
    val size = fontSize.coerceIn(8f, 24f)
    return MaterialTheme.typography.bodySmall.copy(
        fontSize = size.sp,
        lineHeight = (if (hasEmotes) maxOf(size + 1f, emoteSizeSp) else size + 1f).sp,
        fontFamily = chatFontFamily(family),
    )
}

private fun chatFontFamily(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

private fun configuredChatTextColor(key: String): Color = when (key) {
    "warm" -> Color(0xFFFFF1DF)
    "yellow" -> Color(0xFFFFDB66)
    else -> Color.White
}

private fun configuredOutlineColor(key: String): Color = when (key) {
    "navy" -> Color(0xFF07172E)
    "white" -> Color.White
    else -> Color.Black
}

private fun previewMessages(): List<ChatMessage> {
    val now = System.currentTimeMillis()
    return listOf(
        previewMessage("preview-1", "SmugEnjoyer", "#4196FF", "Crash", now - 800L, "subscriber"),
        previewMessage("preview-2", "DraygoDraygon", "#2E9DFF", "FIRED from QA", now - 1_600L),
        previewMessage("preview-3", "squealpg", "#E977A9", "try again", now - 2_400L, "vip"),
        previewMessage("preview-4", "sangdream", "#53B6AA", "the best part of debugging is how it never ends", now - 3_200L),
        previewMessage("preview-5", "Doomed2Die", "#35BF65", "the QA never ends", now - 4_000L, "moderator"),
    )
}

private fun previewMessage(
    id: String,
    name: String,
    color: String,
    text: String,
    timestamp: Long,
    badge: String? = null,
) = ChatMessage(
    id = id,
    channel = "sample",
    username = name,
    displayName = name,
    color = color,
    message = if (id == "preview-5") "$text Kappa" else text,
    parsedParts = if (id == "preview-5") {
        listOf(MessagePart.Text("$text "), MessagePart.TwitchEmote(id = "25", name = "Kappa"))
    } else listOf(MessagePart.Text(text)),
    badges = badge?.let { listOf(Badge(it, "1")) }.orEmpty(),
    timestamp = timestamp,
    isSubscriber = badge == "subscriber",
    isModerator = badge == "moderator",
    isBroadcaster = badge == "broadcaster",
)
