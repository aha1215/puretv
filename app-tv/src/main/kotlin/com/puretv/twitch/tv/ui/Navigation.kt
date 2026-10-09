package com.puretv.twitch.tv.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.puretv.twitch.tv.ui.screens.TvBrowseScreen
import com.puretv.twitch.tv.ui.screens.TvCategoryScreen
import com.puretv.twitch.tv.ui.screens.TvChannelScreen
import com.puretv.twitch.tv.ui.screens.TvFollowingScreen
import com.puretv.twitch.tv.ui.screens.TvHomeScreen
import com.puretv.twitch.tv.ui.screens.TvLoginScreen
import com.puretv.twitch.tv.ui.screens.TvSearchScreen
import com.puretv.twitch.tv.ui.screens.TvSettingsScreen
import com.puretv.twitch.tv.ui.screens.TvStreamScreen
import com.puretv.twitch.tv.ui.screens.TvVodListScreen
import com.puretv.twitch.tv.ui.screens.TvVodPlayerScreen

/**
 * SECTION 07.2 — TV route table. Mirrors the phone app's [Routes] one-for-one
 * (so `core` repositories/ViewModID parametrization patterns line up) but
 * hosts purpose-built TV composables — see Section 12.2: app-tv shares no UI
 * with app-android.
 *
 *   TvHomeScreen     → Nav rail (Live/Following/Categories/Search/Settings) + content rows
 *   TvFollowingScreen → Following's own destination: live follows in a card
 *                        grid, everyone else in a focusable row list, its own
 *                        copy of the nav rail (same LEFT-to-rail behaviour as Home)
 *   TvBrowseScreen   → Category/game grid, D-pad navigable
 *   TvSearchScreen   → On-screen-keyboard-driven search
 *   TvStreamScreen   → Immersive fullscreen player + auto-hide controls + chat overlay
 *   TvChannelScreen  → Channel profile + watch CTA
 *   TvSettingsScreen → Quality / ad-block / proxy / account, focusable rows
 *   TvLoginScreen    → OAuth entry point (same PKCE flow + redirect scheme as phone)
 */
object Routes {
    const val HOME = "home"
    const val FOLLOWING = "following"
    const val BROWSE = "browse"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val STREAM = "stream/{channelLogin}"
    const val CHANNEL = "channel/{channelLogin}"
    const val CATEGORY = "category/{gameId}"
    const val VODS = "vods/{channelLogin}"
    const val VOD_PLAYER = "vod/{channelLogin}/{vodId}?title={title}&thumbnailUrl={thumbnailUrl}&durationSeconds={durationSeconds}"

    fun stream(channelLogin: String) = "stream/$channelLogin"
    fun channel(channelLogin: String) = "channel/$channelLogin"
    fun category(gameId: String) = "category/$gameId"
    fun vods(channelLogin: String) = "vods/$channelLogin"
    fun vod(channelLogin: String, vodId: String, title: String, thumbnailUrl: String, durationSeconds: Long) =
        "vod/$channelLogin/$vodId?title=${Uri.encode(title)}&thumbnailUrl=${Uri.encode(thumbnailUrl)}&durationSeconds=$durationSeconds"
}

@Composable
fun PureTvTvNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            TvHomeScreen(
                onOpenStream = { navController.navigate(Routes.stream(it)) },
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onOpenVod = { vod -> navController.navigate(Routes.vod(vod.channelLogin, vod.id, vod.title, vod.thumbnailUrl, vod.durationSeconds)) },
                onOpenLatestVod = { vod ->
                    navController.navigate(Routes.vod(vod.userLogin, vod.id, vod.title, vod.thumbnailUrl, vod.durationSeconds))
                },
                onOpenFollowing = { navController.navigate(Routes.FOLLOWING) },
                onOpenBrowse = { navController.navigate(Routes.BROWSE) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.FOLLOWING) {
            TvFollowingScreen(
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onOpenHome = navController::popBackStack,
                onOpenBrowse = { navController.navigate(Routes.BROWSE) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.BROWSE) {
            TvBrowseScreen(onOpenCategory = { navController.navigate(Routes.category(it)) }, onBack = navController::popBackStack)
        }
        composable(Routes.CATEGORY) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId").orEmpty()
            TvCategoryScreen(
                gameId = gameId,
                onOpenStream = { navController.navigate(Routes.stream(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.SEARCH) {
            TvSearchScreen(
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.SETTINGS) {
            TvSettingsScreen(onBack = navController::popBackStack)
        }
        composable(Routes.LOGIN) {
            TvLoginScreen(onLoggedIn = { navController.popBackStack() }, onBack = navController::popBackStack)
        }
        composable(Routes.STREAM) { backStackEntry ->
            val channelLogin = backStackEntry.arguments?.getString("channelLogin").orEmpty()
            TvStreamScreen(
                channelLogin = channelLogin,
                onBack = navController::popBackStack,
                onOpenChannel = { navController.navigate(Routes.channel(it)) },
            )
        }
        composable(Routes.CHANNEL) { backStackEntry ->
            val channelLogin = backStackEntry.arguments?.getString("channelLogin").orEmpty()
            TvChannelScreen(
                channelLogin = channelLogin,
                onWatch = { navController.navigate(Routes.stream(channelLogin)) },
                onOpenVods = { navController.navigate(Routes.vods(channelLogin)) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.VODS) { entry ->
            val channelLogin = entry.arguments?.getString("channelLogin").orEmpty()
            TvVodListScreen(
                channelLogin = channelLogin,
                onOpenVod = { video -> navController.navigate(Routes.vod(channelLogin, video.id, video.title, video.thumbnailUrl, video.durationSeconds)) },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.VOD_PLAYER) { entry ->
            TvVodPlayerScreen(
                channelLogin = entry.arguments?.getString("channelLogin").orEmpty(),
                vodId = entry.arguments?.getString("vodId").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                thumbnailUrl = entry.arguments?.getString("thumbnailUrl").orEmpty(),
                durationSeconds = entry.arguments?.getString("durationSeconds")?.toLongOrNull() ?: 0L,
                onBack = navController::popBackStack,
            )
        }
    }
}
