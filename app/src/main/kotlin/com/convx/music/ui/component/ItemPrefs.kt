/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.convx.music.constants.CropAlbumArtKey
import com.convx.music.constants.GridCardHeightOverrideKey
import com.convx.music.constants.GridItemSize
import com.convx.music.constants.GridItemsSizeKey
import com.convx.music.constants.SwipeToRemoveSongKey
import com.convx.music.constants.SwipeToSongKey
import com.convx.music.utils.rememberEnumPreference
import com.convx.music.utils.rememberPreference

/** Preferences shared by item rows; read once to avoid blocking DataStore reads per lazy item. */
@Immutable
data class ItemPrefs(
    val cropAlbumArt: Boolean = false,
    val swipeToSong: Boolean = false,
    val swipeToRemoveSong: Boolean = false,
    val gridCardHeightOverrideDp: Int = 0,
    val gridItemSize: GridItemSize = GridItemSize.BIG,
)

val LocalItemPrefs = staticCompositionLocalOf { ItemPrefs() }

/** Reads the item preferences from DataStore. Call once, at the app root. */
@Composable
fun rememberItemPrefs(): ItemPrefs {
    val (cropAlbumArt) = rememberPreference(CropAlbumArtKey, false)
    val (swipeToSong) = rememberPreference(SwipeToSongKey, false)
    val (swipeToRemoveSong) = rememberPreference(SwipeToRemoveSongKey, false)
    val (gridCardHeightOverride) = rememberPreference(GridCardHeightOverrideKey, 0)
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)
    return remember(
        cropAlbumArt, swipeToSong, swipeToRemoveSong, gridCardHeightOverride, gridItemSize,
    ) {
        ItemPrefs(
            cropAlbumArt = cropAlbumArt,
            swipeToSong = swipeToSong,
            swipeToRemoveSong = swipeToRemoveSong,
            gridCardHeightOverrideDp = gridCardHeightOverride,
            gridItemSize = gridItemSize,
        )
    }
}

/** Download state shared by rows; this dynamic local updates the badge when downloads change. */
val LocalDownloads = androidx.compose.runtime.compositionLocalOf<Map<String, androidx.media3.exoplayer.offline.Download>> { emptyMap() }
