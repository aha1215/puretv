package com.puretv.twitch.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme

enum class TvNavDestination(val label: String, val icon: ImageVector, val outlineIcon: ImageVector) {
    HOME("Home", ExpressiveIcons.Home, ExpressiveIcons.HomeOutlined),
    FOLLOWING("Following", ExpressiveIcons.Following, ExpressiveIcons.FollowingOutlined),
    BROWSE("Categories", ExpressiveIcons.Browse, ExpressiveIcons.BrowseOutlined),
    SEARCH("Search", ExpressiveIcons.Search, ExpressiveIcons.SearchOutlined),
    SETTINGS("Settings", ExpressiveIcons.Settings, ExpressiveIcons.SettingsOutlined),
    ACCOUNT("Account", ExpressiveIcons.Account, ExpressiveIcons.AccountOutlined),
}

/**
 * SECTION 07.2 / 07.3 [CRITICAL], collapsible left navigation rail.
 *
 * Per Section 7.3's D-pad rules: pressing LEFT while focus is on the leftmost
 * column of the content area moves focus into this rail (handled by the screen
 * via `focusRequester`, see [TvHomeScreen]) and expands it; the rail collapses
 * back to icon-only once focus leaves it. `animateContentSize` gives the
 * icon↔icon+label transition a smooth feel appropriate for the 10-foot UI.
 *
 * Each row is built on [Modifier.tvFocusClickable] so a focused row morphs,
 * scales and takes a primary ring, exactly like every other focusable object
 * in the app. Selection is a separate, persistent signal: the selected
 * destination keeps its `secondaryContainer` fill and filled glyph whether or
 * not it currently holds focus, since on a ten-foot UI the two are frequently
 * on different rows at once.
 */
@Composable
fun TvNavDrawer(
    selected: TvNavDestination,
    isLoggedIn: Boolean,
    onSelect: (TvNavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(128.dp)
            .clip(shapes.paneShape)
            .background(c.surfaceContainer)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val destinations = TvNavDestination.entries.filter { it != TvNavDestination.ACCOUNT || !isLoggedIn }

        destinations.forEach { dest ->
            val isSelected = dest == selected
            val label = if (dest == TvNavDestination.ACCOUNT && !isLoggedIn) "Sign in" else dest.label
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .tvFocusClickable(
                        interaction = interaction,
                        onClick = { onSelect(dest) },
                        restRadius = 28.dp,
                        focusRadius = shapes.pillFocus,
                        color = if (isSelected) c.surfaceHigh else Color.Transparent,
                        focusColor = c.primary,
                    )
                    .padding(horizontal = 10.dp),
            ) {
                Icon(
                    imageVector = if (isSelected) dest.icon else dest.outlineIcon,
                    contentDescription = label,
                    tint = when {
                        focused -> c.onPrimary
                        isSelected -> c.primary
                        else -> c.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        focused -> c.onPrimary
                        isSelected -> c.onSurface
                        else -> c.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
