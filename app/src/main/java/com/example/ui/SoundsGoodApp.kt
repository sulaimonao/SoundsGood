package com.example.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LocalSyncScreen
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.SmartPlaylistsScreen
import com.example.ui.viewmodel.SoundsGoodViewModel
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data object SmartPlaylistsRoute

@Serializable
data object LocalSyncRoute

@Serializable
data object NowPlayingRoute

private data class TopLevelDestination(
    val route: Any,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TopLevelDestinations = listOf(
    TopLevelDestination(
        route = LibraryRoute,
        labelRes = R.string.nav_library,
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
        testTag = "nav_library"
    ),
    TopLevelDestination(
        route = SmartPlaylistsRoute,
        labelRes = R.string.nav_playlists,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_playlists"
    ),
    TopLevelDestination(
        route = LocalSyncRoute,
        labelRes = R.string.nav_local_sync,
        selectedIcon = Icons.Filled.Sync,
        unselectedIcon = Icons.Outlined.Sync,
        testTag = "nav_local_sync"
    ),
    TopLevelDestination(
        route = NowPlayingRoute,
        labelRes = R.string.nav_now_playing,
        selectedIcon = Icons.Filled.GraphicEq,
        unselectedIcon = Icons.Outlined.GraphicEq,
        testTag = "nav_now_playing"
    )
)

@Composable
fun SoundsGoodApp(viewModel: SoundsGoodViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel, context.applicationContext) {
        viewModel.restoreMusicFolderSelection(context.applicationContext)
    }
    val view = LocalView.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val libraryState by viewModel.libraryUiState.collectAsStateWithLifecycle()
    val smartPlaylists by viewModel.smartPlaylists.collectAsStateWithLifecycle()
    val syncedPlaylists by viewModel.syncedPlaylists.collectAsStateWithLifecycle()
    val selectedPlaylistId by viewModel.selectedPlaylistId.collectAsStateWithLifecycle()
    val selectedSyncedPlaylistKey by viewModel.selectedSyncedPlaylistKey.collectAsStateWithLifecycle()
    val syncUiState by viewModel.syncUiState.collectAsStateWithLifecycle()
    val musicFolder by viewModel.musicFolderState.collectAsStateWithLifecycle()
    val navigateToSyncRequested by viewModel.navigateToSyncRequested.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()
    val activePlaylistLabel by viewModel.activePlaylistLabel.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val keepAwake = syncUiState.isTransferActive || musicFolder.busy
    DisposableEffect(keepAwake, view) {
        view.keepScreenOn = keepAwake
        onDispose {
            view.keepScreenOn = false
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.syncPermissionState(context, triggerScanIfGranted = true)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some document providers only grant temporary access; inspect/import still works for session
                }
            }
            viewModel.importDocumentUris(uris)
        }
    }

    // Request POST_NOTIFICATIONS politely on Android 13+ the first time active playback begins
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(playbackError) {
        playbackError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearPlaybackError()
        }
    }

    LaunchedEffect(libraryState.statusBanner) {
        libraryState.statusBanner?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusBanner()
        }
    }

    val navigateTopLevel: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(navigateToSyncRequested) {
        if (navigateToSyncRequested) {
            navigateTopLevel(LocalSyncRoute)
            viewModel.consumeNavigateToSyncRequest()
        }
    }

    val isOnNowPlaying = currentDestination?.hasRoute<NowPlayingRoute>() == true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    Column {
                        if (!isOnNowPlaying) {
                            MiniPlayerBar(
                                playbackState = playbackState,
                                activePlaylistLabel = activePlaylistLabel,
                                onOpenNowPlaying = { navigateTopLevel(NowPlayingRoute) },
                                onTogglePlayPause = { viewModel.playbackClient.togglePlayPause() },
                                onNext = { viewModel.playbackClient.seekToNext() }
                            )
                        }
                        NavigationBar {
                            TopLevelDestinations.forEach { dest ->
                                val selected = when (dest.route) {
                                    is LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                                    is SmartPlaylistsRoute -> currentDestination?.hasRoute<SmartPlaylistsRoute>() == true
                                    is LocalSyncRoute -> currentDestination?.hasRoute<LocalSyncRoute>() == true
                                    is NowPlayingRoute -> currentDestination?.hasRoute<NowPlayingRoute>() == true
                                    else -> false
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        TopLevelDestinations.forEach { dest ->
                            val selected = when (dest.route) {
                                is LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                                is SmartPlaylistsRoute -> currentDestination?.hasRoute<SmartPlaylistsRoute>() == true
                                is LocalSyncRoute -> currentDestination?.hasRoute<LocalSyncRoute>() == true
                                is NowPlayingRoute -> currentDestination?.hasRoute<NowPlayingRoute>() == true
                                else -> false
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = LibraryRoute
                        ) {
                            composable<LibraryRoute> {
                                LibraryScreen(
                                    state = libraryState,
                                    currentTrackKey = playbackState.currentTrackKey,
                                    onSearchQueryChange = viewModel::updateSearchQuery,
                                    onSortOptionSelect = viewModel::setSortOption,
                                    onCategoryTabSelect = viewModel::setCategoryTab,
                                    onSelectArtist = viewModel::selectArtist,
                                    onSelectAlbum = viewModel::selectAlbum,
                                    onRescan = viewModel::rescanLibrary,
                                    onRequestPermission = {
                                        audioPermissionLauncher.launch(SoundsGoodViewModel.requiredAudioPermission())
                                    },
                                    onOpenAppSettings = {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        )
                                        context.startActivity(intent)
                                    },
                                    onPickLocalFiles = {
                                        filePickerLauncher.launch(
                                            arrayOf("audio/*", "application/ogg", "application/flac")
                                        )
                                    },
                                    onPlayTracks = viewModel::playTracks,
                                    onPlayNext = viewModel::playTrackNext,
                                    onAddToQueue = viewModel::addTrackToQueue,
                                    onSetMoodTag = viewModel::setMoodTag
                                )
                            }

                            composable<SmartPlaylistsRoute> {
                                SmartPlaylistsScreen(
                                    playlists = smartPlaylists,
                                    syncedPlaylists = syncedPlaylists,
                                    selectedPlaylistId = selectedPlaylistId,
                                    selectedSyncedPlaylistKey = selectedSyncedPlaylistKey,
                                    currentTrackKey = playbackState.currentTrackKey,
                                    onSelectPlaylist = viewModel::selectSmartPlaylist,
                                    onSelectSyncedPlaylist = viewModel::selectSyncedPlaylist,
                                    onSaveRule = { rule -> viewModel.saveSmartPlaylistRule(rule) },
                                    onDeleteRule = viewModel::deleteSmartPlaylistRule,
                                    onPlayTracks = viewModel::playTracks,
                                    onPlayNext = viewModel::playTrackNext,
                                    onAddToQueue = viewModel::addTrackToQueue,
                                    onSetMoodTag = viewModel::setMoodTag
                                )
                            }

                            composable<LocalSyncRoute> {
                                LocalSyncScreen(
                                    state = syncUiState,
                                    musicFolder = musicFolder,
                                    onChooseMusicFolder = { viewModel.chooseMusicFolder(context, it) },
                                    onCopyExistingDownloads = viewModel::copyExistingDownloads,
                                    onRecheckMusicFolder = viewModel::recheckMusicFolder,
                                    onCancelMusicFolder = viewModel::cancelMusicFolderWork,
                                    onPairAndConnect = viewModel::pairWithMacLink,
                                    onRefreshManifest = viewModel::refreshSyncManifest,
                                    onSetSyncAllMusic = viewModel::setSyncAllMusic,
                                    onTogglePlaylist = viewModel::toggleSyncPlaylist,
                                    onStartSync = viewModel::startLocalSync,
                                    onOpenTransferFolder = viewModel::openTransferFolder,
                                    onExportReport = viewModel::exportSyncReport,
                                    onCancelSync = viewModel::cancelLocalSync,
                                    onDisconnect = viewModel::disconnectMacSession,
                                    onOpenPlaylists = { navigateTopLevel(SmartPlaylistsRoute) },
                                    onOpenLibrary = { navigateTopLevel(LibraryRoute) }
                                )
                            }

                            composable<NowPlayingRoute> {
                                NowPlayingScreen(
                                    playbackState = playbackState,
                                    activePlaylistLabel = activePlaylistLabel,
                                    onNavigateToLibrary = { navigateTopLevel(LibraryRoute) },
                                    onTogglePlayPause = { viewModel.playbackClient.togglePlayPause() },
                                    onSeekTo = { viewModel.playbackClient.seekTo(it) },
                                    onNext = { viewModel.playbackClient.seekToNext() },
                                    onPrevious = { viewModel.playbackClient.seekToPrevious() },
                                    onToggleShuffle = { viewModel.playbackClient.toggleShuffle() },
                                    onCycleRepeatMode = { viewModel.playbackClient.cycleRepeatMode() },
                                    onJumpToQueueIndex = { viewModel.playbackClient.jumpToQueueIndex(it) },
                                    onRemoveFromQueue = { viewModel.playbackClient.removeFromQueue(it) },
                                    onSetMoodTag = viewModel::setMoodTag
                                )
                            }
                        }
                    }

                    if (useNavigationRail && !isOnNowPlaying) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            activePlaylistLabel = activePlaylistLabel,
                            onOpenNowPlaying = { navigateTopLevel(NowPlayingRoute) },
                            onTogglePlayPause = { viewModel.playbackClient.togglePlayPause() },
                            onNext = { viewModel.playbackClient.seekToNext() }
                        )
                    }
                }
            }
        }
    }
}
