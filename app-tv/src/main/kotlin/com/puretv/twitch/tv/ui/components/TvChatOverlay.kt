package com.puretv.twitch.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.gif.GifDecoder
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.Badge
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.R
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Existing right-side chat rail, configurable for the ten-foot player. */
@Composable
fun TvChatOverlay(
    visible: Boolean,
    messages: List<ChatMessage>,
    appSettings: AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).components { add(GifDecoder.Factory()) }.build()
    }
    val listState = rememberLazyListState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(appearance.fadeSeconds) {
        while (appearance.fadeSeconds > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    AnimatedVisibility(
        visible = visible && appSettings.chatEnabled,
        enter = slideInHorizontally(initialOffsetX = { if (appearance.side == "left") -it else it }),
        exit = slideOutHorizontally(targetOffsetX = { if (appearance.side == "left") -it else it }),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = if (appearance.side == "left") Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.98f)
                    .width(appearance.widthDp.dp)
                    .widthIn(max = 480.dp)
                    .clip(shapes.paneShape)
                    .background(c.surfaceContainer.copy(alpha = appearance.opacity))
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    "CHAT  ·  MENU to hide",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = c.onSurfaceVariant,
                )
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(messages, key = { it.id }) { message ->
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

@OptIn(ExperimentalLayoutApi::class)
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
    val timestamp = remember(message.timestamp) {
        runCatching {
            Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault()).format(ClockFormatter)
        }.getOrDefault("")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clip(shapes.mdShape)
            .background(if (message.mentionsSelf) c.primaryContainer else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        FlowRow(
            modifier = Modifier.heightIn(max = 32.dp).clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (!message.isSystem) {
                OutlinedChatText(
                    text = message.displayName,
                    style = chatNameTextStyle(appSettings.chatFontSize, appearance.fontFamily),
                    color = nameColor,
                    appearance = appearance,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (appSettings.chatTimestamps) {
                    Text(text = timestamp, style = PureTvTvType.dataSmall.copy(fontSize = 10.sp), color = c.onSurfaceVariant)
                }
                if (appSettings.showBadges) {
                    message.badges.forEach { badge ->
                        ChatBadge(badge.setId)
                    }
                }
            }
            val translatedMessage = message.translatedMessage
            if (translatedMessage != null) {
                OutlinedChatText(
                    translatedMessage,
                    style = compactChatTextStyle(appSettings.chatFontSize, appearance.fontFamily),
                    color = messageColor,
                    appearance = appearance,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (message.parsedParts.isEmpty()) {
                OutlinedChatText(
                    message.message,
                    style = compactChatTextStyle(appSettings.chatFontSize, appearance.fontFamily),
                    color = messageColor,
                    appearance = appearance,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                message.parsedParts.forEach { part ->
                    when (part) {
                        is MessagePart.Text -> OutlinedChatText(
                            part.content,
                            style = compactChatTextStyle(appSettings.chatFontSize, appearance.fontFamily),
                            color = messageColor,
                            appearance = appearance,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        is MessagePart.TwitchEmote -> {
                            EmoteImage("https://static-cdn.jtvnw.net/emoticons/v2/${part.id}/default/dark/2.0", part.name, imageLoader)
                        }
                        is MessagePart.ThirdPartyEmote -> {
                            val enabled = when (part.provider) {
                                com.puretv.twitch.core.model.EmoteProvider.BTTV -> appSettings.showBttvEmotes
                                com.puretv.twitch.core.model.EmoteProvider.FFZ -> appSettings.showFfzEmotes
                                com.puretv.twitch.core.model.EmoteProvider.SEVENTV -> appSettings.show7tvEmotes
                                com.puretv.twitch.core.model.EmoteProvider.TWITCH -> true
                            }
                            if (enabled) EmoteImage(part.url, part.name, imageLoader)
                            else OutlinedChatText(
                                part.name,
                                style = compactChatTextStyle(appSettings.chatFontSize, appearance.fontFamily),
                                color = messageColor,
                                appearance = appearance,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small, focus-free preview that uses the same row renderer and style as playback. */
@Composable
fun TvChatStylePreview(
    settings: AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).components { add(GifDecoder.Factory()) }.build()
    }
    val messages = remember { previewMessages() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(appearance.fadeSeconds) {
        while (appearance.fadeSeconds > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live style preview", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
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
                val previewWidth = minOf(appearance.widthDp.dp, sceneWidth * .49f)
                Column(
                    modifier = Modifier.align(if (appearance.side == "left") Alignment.CenterStart else Alignment.CenterEnd)
                        .fillMaxHeight(.98f)
                        .width(previewWidth)
                        .clip(shapes.mdShape)
                        .background(c.surfaceContainer.copy(alpha = appearance.opacity))
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text("CHAT", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = c.onSurfaceVariant)
                    messages.forEach { message ->
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

@Composable
private fun OutlinedChatText(
    text: String,
    style: TextStyle,
    color: Color,
    appearance: AppSettingsStore.ChatAppearance,
    maxLines: Int,
    overflow: TextOverflow,
    fontWeight: FontWeight? = null,
) {
    val strokeWidth = with(LocalDensity.current) { appearance.outlineWidthDp.dp.toPx() }
    Box {
        if (strokeWidth > 0f) {
            Text(
                text = text,
                style = style.copy(color = configuredOutlineColor(appearance.outlineColor), drawStyle = Stroke(width = strokeWidth)),
                maxLines = maxLines,
                overflow = overflow,
                fontWeight = fontWeight,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = text,
            style = style.copy(color = color, drawStyle = Fill),
            maxLines = maxLines,
            overflow = overflow,
            fontWeight = fontWeight,
        )
    }
}

@Composable
private fun ChatBadge(setId: String) {
    val color = when (setId.lowercase()) {
        "broadcaster" -> Color(0xFFFF4F64)
        "moderator" -> Color(0xFF48C774)
        "vip" -> Color(0xFFFFB84D)
        "subscriber" -> Color(0xFF9B7BFF)
        else -> PureTvTvTheme.colors.tertiary
    }
    val mark = when (setId.lowercase()) {
        "broadcaster" -> "★"
        "moderator" -> "◆"
        "vip" -> "♛"
        "subscriber" -> "✦"
        else -> setId.take(1).uppercase()
    }
    Text(mark, style = PureTvTvType.dataSmall.copy(fontSize = 10.sp), color = color)
}

@Composable
private fun chatNameTextStyle(fontSize: Float, family: String): TextStyle {
    val size = fontSize.coerceIn(8f, 14f)
    return MaterialTheme.typography.labelMedium.copy(
        fontSize = size.sp,
        lineHeight = (size + 1f).sp,
        fontFamily = chatFontFamily(family),
    )
}

@Composable
private fun compactChatTextStyle(fontSize: Float, family: String): TextStyle {
    val size = fontSize.coerceIn(8f, 14f)
    return MaterialTheme.typography.bodySmall.copy(
        fontSize = size.sp,
        lineHeight = (size + 1f).sp,
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

@Composable
private fun EmoteImage(url: String, name: String, imageLoader: ImageLoader) {
    AsyncImage(model = url, contentDescription = name, imageLoader = imageLoader, modifier = Modifier.size(16.dp))
}
