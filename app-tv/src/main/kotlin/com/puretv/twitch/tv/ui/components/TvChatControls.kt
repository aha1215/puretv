package com.puretv.twitch.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import kotlin.math.roundToInt

@Composable
fun TvChatValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    val c = PureTvTvTheme.colors
    val steps = ((valueRange.endInclusive - valueRange.start) / step).roundToInt().coerceAtLeast(1) - 1
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.tv.material3.Text(label, color = c.onSurfaceVariant, style = androidx.tv.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.tv.material3.Text(valueText, color = c.onSurface, style = androidx.tv.material3.MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = { raw -> onValueChange(snap(raw, valueRange, step)) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionLeft, Key.DirectionDown -> -step
                        Key.DirectionRight, Key.DirectionUp -> step
                        else -> return@onPreviewKeyEvent false
                    }
                    onValueChange(snap(value + delta, valueRange, step))
                    true
                },
        )
    }
}

@Composable
fun TvChatEditorDialog(
    settings: AppSettings,
    appearance: AppSettingsStore.ChatAppearance,
    chatMessages: List<ChatMessage>,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onAppearanceChange: (AppSettingsStore.ChatAppearance) -> Unit,
    onSave: () -> Unit,
) {
    val c = PureTvTvTheme.colors
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Dialog(onDismissRequest = onSave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(.94f)
                .fillMaxHeight(.96f)
                .widthIn(max = 1_180.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(c.surfaceContainer)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    androidx.tv.material3.Text("Edit chat", color = c.onSurface, style = androidx.tv.material3.MaterialTheme.typography.headlineSmall)
                    androidx.tv.material3.Text("Changes preview live. Back saves and closes.", color = c.onSurfaceVariant, style = androidx.tv.material3.MaterialTheme.typography.bodyMedium)
                }
                TvExpressiveButton("Save and close", onSave, style = TvButtonStyle.Filled, size = TvButtonSize.Large)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                TvChatStylePreview(
                    settings = settings.copy(chatFontSize = fontSize),
                    appearance = appearance,
                    messages = chatMessages,
                    modifier = Modifier.weight(1.05f).heightIn(min = 280.dp, max = 440.dp).align(Alignment.CenterVertically),
                    title = "Current chat preview",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TvChatValueSlider("Font size", fontSize, 8f..14f, 1f, "${fontSize.toInt()} sp", modifier = Modifier.focusRequester(focusRequester), onValueChange = onFontSizeChange)
                    TvChatValueSlider("Emote size", appearance.emoteSizeDp, 16f..40f, 2f, "${appearance.emoteSizeDp.roundToInt()} dp") { onAppearanceChange(appearance.copy(emoteSizeDp = it)) }
                    TvChatValueSlider("Outline width", appearance.outlineWidthDp, 0f..6f, 1f, "${appearance.outlineWidthDp} dp") { onAppearanceChange(appearance.copy(outlineWidthDp = it)) }
                    TvChatValueSlider("Background", appearance.opacity, 0f..1f, .05f, "${(appearance.opacity * 100).roundToInt()}%") { onAppearanceChange(appearance.copy(opacity = it)) }
                    TvChatValueSlider("Chat width", appearance.widthDp, 200f..480f, 2f, "${appearance.widthDp.roundToInt()} dp") { onAppearanceChange(appearance.copy(widthDp = it)) }
                    TvChatValueSlider("Panel corners", appearance.cornerRadiusDp, 0f..48f, 2f, "${appearance.cornerRadiusDp.roundToInt()} dp") { onAppearanceChange(appearance.copy(cornerRadiusDp = it)) }
                    TvChatValueSlider("Message fade", appearance.fadeSeconds.toFloat(), 0f..120f, 1f, if (appearance.fadeSeconds == 0) "Off" else "${appearance.fadeSeconds} sec") { onAppearanceChange(appearance.copy(fadeSeconds = it.roundToInt())) }
                    TvChatValueSlider("Horizontal position", (appearance.positionXFraction ?: if (appearance.side == "left") 0f else 1f) * 100f, 0f..100f, 2f, "${((appearance.positionXFraction ?: if (appearance.side == "left") 0f else 1f) * 100).roundToInt()}%") { onAppearanceChange(appearance.copy(positionXFraction = it / 100f)) }
                    TvChatValueSlider("Vertical position", (appearance.positionYFraction ?: .5f) * 100f, 0f..100f, 2f, "${((appearance.positionYFraction ?: .5f) * 100).roundToInt()}%") { onAppearanceChange(appearance.copy(positionYFraction = it / 100f)) }
                    TvChatValueSlider("Panel height", appearance.heightFraction * 100f, 20f..98f, 2f, "${(appearance.heightFraction * 100).roundToInt()}%") { onAppearanceChange(appearance.copy(heightFraction = it / 100f)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvFilterChip("Left", appearance.side == "left", onClick = { onAppearanceChange(appearance.copy(side = "left", positionXFraction = 0f)) })
                        TvFilterChip("Right", appearance.side != "left", onClick = { onAppearanceChange(appearance.copy(side = "right", positionXFraction = 1f)) })
                    }
                }
            }
        }
    }
}

@Composable
fun TvSubtitleOverlay(cues: List<Cue>, appearance: AppSettingsStore.SubtitleAppearance, modifier: Modifier = Modifier) {
    if (cues.isEmpty()) return
    AndroidView(
        modifier = modifier,
        factory = { context -> SubtitleView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        } },
        update = { view ->
            view.setCues(cues)
            view.setApplyEmbeddedStyles(false)
            view.setApplyEmbeddedFontSizes(false)
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, appearance.sizeSp)
            val foreground = when (appearance.textColor) {
                "yellow" -> android.graphics.Color.YELLOW
                "cyan" -> android.graphics.Color.CYAN
                else -> android.graphics.Color.WHITE
            }
            view.setStyle(CaptionStyleCompat(
                foreground,
                android.graphics.Color.argb((appearance.backgroundOpacity * 255).toInt(), 0, 0, 0),
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                android.graphics.Typeface.DEFAULT_BOLD,
            ))
            view.setBottomPaddingFraction(appearance.bottomPaddingFraction)
        },
    )
}

private fun snap(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    val clamped = value.coerceIn(range)
    return (range.start + ((clamped - range.start) / step).roundToInt() * step).coerceIn(range)
}
