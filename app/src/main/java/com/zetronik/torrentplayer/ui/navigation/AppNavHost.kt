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
import com.zetronik.torrentplayer.ui.files.FolderScreen
import com.zetronik.torrentplayer.ui.files.PickerScreen
import com.zetronik.torrentplayer.ui.home.HomeScreen
import com.zetronik.torrentplayer.ui.player.PlayerScreen
import com.zetronik.torrentplayer.ui.remote.RemoteScreen
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
            if (request is NavRequest.PlayTorrent) {
                // Over the torrent's own file list the player is simply pushed: replacing that screen would
                // close the torrent the new player is about to stream.
                val onItsFileList = request.torrentActive && navController.currentDestination?.hasRoute<TorrentRoute>() == true
                if (!onItsFileList) {
                    navController.navigate(TorrentRoute(request.source)) { popUpTo<HomeRoute>() }
                }
                navController.navigate(request.player)
                return@collect
            }
            if (request is NavRequest.PlayStream) {
                navController.navigate(request.player) { popUpTo<HomeRoute>() }
                return@collect
            }
            val route: Any = when (request) {
                is NavRequest.PlayTorrent, is NavRequest.PlayStream -> return@collect
                is NavRequest.OpenTorrent -> TorrentRoute(request.source)
                is NavRequest.PlayVideo -> PlayerRoute(
                    uri = request.uri,
                    title = request.title,
                    external = true,
                    playlistUris = request.playlist.map { it.uri },
                    playlistTitles = request.playlist.map { it.title },
                )
            }
            navController.navigate(route) {
                popUpTo<HomeRoute>()
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
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
        composable<HomeRoute> {
            HomeScreen(
                onOpenTorrent = { source -> navController.navigate(TorrentRoute(source)) },
                onOpenFolder = { navController.navigate(it) },
                onPick = { navController.navigate(it) },
                onPlay = { navController.navigate(it) },
            )
        }
        composable<PickerRoute> {
            PickerScreen(onClose = { navController.popBackStack() })
        }
        composable<FolderRoute> {
            FolderScreen(
                onOpenFolder = { navController.navigate(it) },
                onPlay = { navController.navigate(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<TorrentRoute> {
            TorrentScreen(
                onPlay = { meta, file, startPositionMs ->
                    navController.navigate(
                        PlayerRoute(
                            uri = TorrentUris.build(meta.infoHash, file.index).toString(),
                            title = file.name,
                            infoHash = meta.infoHash,
                            fileIndex = file.index,
                            startPositionMs = startPositionMs,
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
                onCast = { remote ->
                    navController.navigate(remote) { popUpTo<PlayerRoute> { inclusive = true } }
                },
            )
        }
        composable<RemoteRoute> {
            RemoteScreen(
                onBack = { navController.popBackStack() },
                onContinueHere = { player ->
                    navController.navigate(player) { popUpTo<RemoteRoute> { inclusive = true } }
                },
            )
        }
    }
}
