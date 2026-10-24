/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.menu

import com.convx.music.ui.component.sf
import com.convx.music.ui.component.SymbolIcon
import com.convx.music.ui.component.menuBottomInset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import com.convx.music.ui.ios.compat.CircularProgressIndicator
import com.convx.music.ui.ios.compat.Icon
import com.convx.music.ui.ios.compat.AppTheme
import com.convx.music.ui.ios.compat.Text
import com.convx.music.ui.ios.compat.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.convx.music.LocalDatabase
import com.convx.music.LocalDownloadUtil
import com.convx.music.LocalPlayerConnection
import com.convx.music.LocalSyncUtils
import com.convx.music.R
import com.convx.music.extensions.toMediaItem
import com.convx.music.models.toMediaMetadata
import com.convx.music.playback.queues.ListQueue
import com.convx.music.ui.component.DefaultDialog
import com.convx.music.ui.component.IosMenuGroup
import com.convx.music.ui.component.IosMenuItemData
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import com.convx.music.playback.DownloadTarget
import com.convx.music.playback.combinedDownloadState
import com.convx.music.playback.cancelDownloads
import com.convx.music.playback.downloadSongs
import com.convx.music.playback.removeDownloads
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun YouTubeSelectionSongMenu(
    songSelection: List<SongItem>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current.get()
    val downloadUtil = LocalDownloadUtil.current.get()
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current.get()

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val listenTogetherManager = com.convx.music.LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && listenTogetherManager.isHost == false

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    val allLiked by remember(songSelection) {
        mutableStateOf(
            songSelection.isNotEmpty() && songSelection.all { song ->
                val metadata = song.toMediaMetadata()
                metadata.liked
            }
        )
    }

    val allInLibrary by remember(songSelection) {
        mutableStateOf(
            songSelection.all { song ->
                val metadata = song.toMediaMetadata()
                metadata.inLibrary != null
            }
        )
    }

    val downloads by downloadUtil.downloads.collectAsStateWithLifecycle()
    val downloadState = remember(songSelection, downloads) {
        combinedDownloadState(songSelection, downloads) { it.id }
    }

    AddToPlaylistDialogOnline(
        isVisible = showChoosePlaylistDialog,
        songs = remember { 
            songSelection.map { song ->
                val metadata = song.toMediaMetadata()
                com.convx.music.db.entities.Song(
                    song = com.convx.music.db.entities.SongEntity(
                        id = metadata.id,
                        title = metadata.title,
                        duration = metadata.duration,
                        thumbnailUrl = metadata.thumbnailUrl,
                        albumId = metadata.album?.id,
                        albumName = metadata.album?.title,
                        liked = metadata.liked,
                        totalPlayTime = 0,
                        inLibrary = metadata.inLibrary,
                        isLocal = false,
                        libraryAddToken = metadata.libraryAddToken,
                        libraryRemoveToken = metadata.libraryRemoveToken
                    ),
                    artists = metadata.artists.map { artist ->
                        com.convx.music.db.entities.ArtistEntity(
                            id = artist.id ?: "",
                            name = artist.name
                        )
                    },
                    album = metadata.album?.let { album ->
                        com.convx.music.db.entities.AlbumEntity(
                            id = album.id,
                            title = album.title,
                            thumbnailUrl = metadata.thumbnailUrl, // Use song's thumbnail as album thumbnail
                            songCount = 0,
                            duration = 0
                        )
                    }
                )
            }.toMutableStateList()
        },
        onProgressStart = { },
        onPercentageChange = { },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
    )

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, "selection"),
                    style = AppTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        removeDownloads(context, songSelection.map { it.id })
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + menuBottomInset(),
        ),
    ) {
        item {
            IosMenuGroup(
                listOfNotNull(
                    if (!isGuest) IosMenuItemData(
                        icon = { SymbolIcon(symbol = sf("play.fill"), contentDescription = null) },
                        title = { Text(stringResource(R.string.play)) },
                        onClick = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.queue_all_songs),
                                    items = songSelection.map { it.toMediaItem() },
                                ),
                            )
                            clearAction()
                            onDismiss()
                        },
                    ) else null,
                    if (!isGuest) IosMenuItemData(
                        icon = { SymbolIcon(symbol = sf("shuffle"), contentDescription = null) },
                        title = { Text(stringResource(R.string.shuffle)) },
                        onClick = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.queue_all_songs),
                                    items = songSelection.shuffled().map { it.toMediaItem() },
                                ),
                            )
                            clearAction()
                            onDismiss()
                        },
                    ) else null,
                    if (!isGuest) IosMenuItemData(
                        icon = { SymbolIcon(symbol = sf("music.note.list"), contentDescription = null) },
                        title = { Text(stringResource(R.string.add_to_queue)) },
                        onClick = {
                            playerConnection.addToQueue(songSelection.map { it.toMediaItem() })
                            clearAction()
                            onDismiss()
                        },
                    ) else null,
                    IosMenuItemData(
                        icon = { SymbolIcon(symbol = sf("text.badge.plus"), contentDescription = null) },
                        title = { Text(stringResource(R.string.add_to_playlist)) },
                        onClick = {
                            showChoosePlaylistDialog = true
                        },
                    ),
                    IosMenuItemData(
                        title = {
                            Text(
                                text = stringResource(
                                    if (allInLibrary) R.string.remove_from_library else R.string.add_to_library
                                )
                            )
                        },
                        icon = {
                            SymbolIcon(symbol = 
                                    if (allInLibrary) sf("text.badge.checkmark") else sf("text.badge.plus")
                                , contentDescription = null)
                        },
                        onClick = {
                            if (allInLibrary) {
                                database.query {
                                    songSelection.forEach { song ->
                                        inLibrary(song.id, null)
                                    }
                                }
                                coroutineScope.launch {
                                    songSelection.forEach { song ->
                                        YouTube.toggleSongLibrary(song.id, false)
                                    }
                                }
                            } else {
                                database.transaction {
                                    songSelection.forEach { song ->
                                        insert(song.toMediaMetadata())
                                        inLibrary(song.id, LocalDateTime.now())
                                    }
                                }
                                coroutineScope.launch {
                                    songSelection.filter { song ->
                                        song.toMediaMetadata().inLibrary == null
                                    }.forEach { song ->
                                        YouTube.toggleSongLibrary(song.id, true)
                                    }
                                }
                            }
                            clearAction()
                            onDismiss()
                        }
                    ),
                    when (downloadState) {
                        Download.STATE_COMPLETED -> {
                            IosMenuItemData(
                                title = {
                                    Text(
                                        text = stringResource(R.string.remove_download)
                                    )
                                },
                                icon = {
                                    SymbolIcon(symbol = sf("arrow.down.circle"), contentDescription = null)
                                },
                                onClick = {
                                    showRemoveDownloadDialog = true
                                }
                            )
                        }
                        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                            IosMenuItemData(
                                title = { Text(text = stringResource(R.string.downloading)) },
                                icon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                onClick = {
                                    cancelDownloads(
                                        context,
                                        songSelection.map { it.id },
                                        downloads,
                                    )
                                }
                            )
                        }
                        else -> {
                            IosMenuItemData(
                                title = { Text(text = stringResource(R.string.action_download)) },
                                icon = {
                                    SymbolIcon(symbol = sf("arrow.down.circle"), contentDescription = null)
                                },
                                onClick = {
                                    downloadSongs(
                                        context,
                                        songSelection.map { DownloadTarget(it.id, it.title) },
                                        downloads,
                                    )
                                    clearAction()
                                    onDismiss()
                                }
                            )
                        }
                    },
                    IosMenuItemData(
                        title = {
                            Text(
                                text = stringResource(
                                    if (allLiked) R.string.dislike_all else R.string.like_all
                                )
                            )
                        },
                        icon = {
                            SymbolIcon(symbol = 
                                    if (allLiked) sf("heart.fill") else sf("heart")
                                , contentDescription = null)
                        },
                        onClick = {
                            database.transaction {
                                songSelection.forEach { song ->
                                    val metadata = song.toMediaMetadata()
                                    if ((!allLiked && !metadata.liked) || allLiked) {
                                        insert(metadata)
                                        val songEntity = com.convx.music.db.entities.SongEntity(
                                            id = metadata.id,
                                            title = metadata.title,
                                            duration = metadata.duration,
                                            thumbnailUrl = metadata.thumbnailUrl,
                                            albumId = metadata.album?.id,
                                            albumName = metadata.album?.title,
                                            liked = !metadata.liked,
                                            totalPlayTime = 0,
                                            inLibrary = metadata.inLibrary,
                                            isLocal = false,
                                            libraryAddToken = metadata.libraryAddToken,
                                            libraryRemoveToken = metadata.libraryRemoveToken
                                        )
                                        update(songEntity)
                                        syncUtils.likeSong(songEntity)
                                    }
                                }
                            }
                            clearAction()
                            onDismiss()
                        }
                    )
                ),
            )
        }
    }
}
