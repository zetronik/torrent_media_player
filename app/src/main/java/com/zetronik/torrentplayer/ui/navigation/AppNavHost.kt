package com.zetronik.torrentplayer.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.MaterialTheme
import com.zetronik.torrentplayer.torrent.TorrentUris
import com.zetronik.torrentplayer.ui.player.PlayerScreen
import com.zetronik.torrentplayer.ui.recent.RecentScreen
import com.zetronik.torrentplayer.ui.torrent.TorrentScreen
import kotlinx.coroutines.flow.Flow

/**
 * Screen transitions are a short cross-fade: sliding full-screen layers drops frames on low-end TV GPUs.
 * The player appears and disappears without animation, so the video surface is never animated.
 */
private const val FADE_MS = 150

@Composable
fun AppNavHost(navRequests: Flow<NavRequest>, onExitApp: () -> Unit) {
    val navController = rememberNavController()

    LaunchedEffect(navController) {
        navRequests.collect { request ->
            val route: Any = when (request) {
                is NavRequest.OpenTorrent -> TorrentRoute(request.source)
                is NavRequest.PlayVideo -> PlayerRoute(uri = request.uri, title = request.title, external = true)
            }
            navController.navigate(route) {
                popUpTo<RecentRoute>()
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = RecentRoute,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            if (targetState.destination.hasRoute<PlayerRoute>()) EnterTransition.None else fadeIn(tween(FADE_MS))
        },
        exitTransition = {
            if (targetState.destination.hasRoute<PlayerRoute>()) ExitTransition.None else fadeOut(tween(FADE_MS))
        },
        popEnterTransition = {
            if (initialState.destination.hasRoute<PlayerRoute>()) EnterTransition.None else fadeIn(tween(FADE_MS))
        },
        popExitTransition = {
            if (initialState.destination.hasRoute<PlayerRoute>()) ExitTransition.None else fadeOut(tween(FADE_MS))
        },
    ) {
        composable<RecentRoute> {
            RecentScreen(onOpenTorrent = { source -> navController.navigate(TorrentRoute(source)) })
        }
        composable<TorrentRoute> {
            TorrentScreen(
                onPlay = { meta, file ->
                    navController.navigate(
                        PlayerRoute(
                            uri = TorrentUris.build(meta.infoHash, file.index).toString(),
                            title = file.name,
                            infoHash = meta.infoHash,
                            fileIndex = file.index,
                        )
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<PlayerRoute> { entry ->
            val route = entry.toRoute<PlayerRoute>()
            PlayerScreen(
                onExit = {
                    if (route.external || !navController.popBackStack()) onExitApp()
                },
            )
        }
    }
}
