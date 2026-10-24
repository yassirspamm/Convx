/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.ios.foundation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.music.ui.component.backdrop.Backdrop
import com.convx.music.ui.component.backdrop.backdrops.emptyBackdrop

/** SwiftUI's `ControlSize`, read from the environment the way iOS controls read it. */
enum class IosControlSize { Mini, Small, Regular, Large, ExtraLarge }

val LocalIosColors = staticCompositionLocalOf { lightIosColors() }
val LocalIosTypography = staticCompositionLocalOf { iosTypography() }
val LocalIosControlSize = compositionLocalOf { IosControlSize.Regular }

/** SwiftUI's `.tint(_:)`. [Color.Unspecified] falls back to the theme accent. */
val LocalIosTint = compositionLocalOf { Color.Unspecified }

/** The inherited text style, so `IosText` behaves like SwiftUI's `.font(_:)`. */
val LocalIosTextStyle = compositionLocalOf { iosTypography().body }

/** The colour text and icons draw in, inherited down the tree. */
val LocalIosContentColor = compositionLocalOf { Color.Unspecified }

/**
 * The backdrop glass samples. Null means no backdrop is wired up — glass then
 * degrades to a translucent fill rather than crashing.
 */
val LocalIosBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The nearest container's corner radius, for `ContainerRelativeShape`. */
val LocalIosContainerShape = compositionLocalOf<IosContainerShape?> { null }

object IosTheme {
    val colors: IosColors
        @Composable @ReadOnlyComposable get() = LocalIosColors.current

    val typography: IosTypography
        @Composable @ReadOnlyComposable get() = LocalIosTypography.current

    val controlSize: IosControlSize
        @Composable @ReadOnlyComposable get() = LocalIosControlSize.current

    /** The active tint, already resolved against the theme accent. */
    val tint: Color
        @Composable @ReadOnlyComposable get() = LocalIosTint.current
            .takeIf { it != Color.Unspecified } ?: LocalIosColors.current.accent

    /** The inherited content colour, falling back to the primary label. */
    val contentColor: Color
        @Composable @ReadOnlyComposable get() = LocalIosContentColor.current
            .takeIf { it != Color.Unspecified } ?: LocalIosColors.current.label

    val backdrop: Backdrop
        @Composable get() = LocalIosBackdrop.current ?: emptyBackdrop()
}

@Composable
fun IosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = Color.Unspecified,
    typography: IosTypography = iosTypography(),
    backdrop: Backdrop? = LocalIosBackdrop.current,
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme, accent) {
        when {
            darkTheme && accent != Color.Unspecified -> darkIosColors(accent)
            darkTheme -> darkIosColors()
            accent != Color.Unspecified -> lightIosColors(accent)
            else -> lightIosColors()
        }
    }
    CompositionLocalProvider(
        LocalIosColors provides colors,
        LocalIosTypography provides typography,
        LocalIosTextStyle provides typography.body,
        LocalIosContentColor provides colors.label,
        LocalIosBackdrop provides backdrop,
    ) {
        // The compat widgets draw with compose-hig underneath, and it reads its colours
        // from its own theme rather than from the locals above. Bridging here rather than
        // at the app root means a preview that calls IosTheme is themed too.
        ProvideHigTheme(colors = colors, typography = typography, content = content)
    }
}

/** SwiftUI's `.controlSize(_:)`. */
@Composable
fun IosControlSize(size: IosControlSize, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIosControlSize provides size, content = content)
}

/** SwiftUI's `.tint(_:)`. */
@Composable
fun IosTint(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIosTint provides color, content = content)
}

/** SwiftUI's `.font(_:)`, scoped to [content]. */
@Composable
fun IosFont(style: TextStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIosTextStyle provides style, content = content)
}

/** SwiftUI's `.foregroundStyle(_:)`, scoped to [content]. */
@Composable
fun IosForegroundStyle(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIosContentColor provides color, content = content)
}

/** Declares the container radius children see through `ContainerRelativeShape`. */
@Composable
fun IosContainer(radius: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIosContainerShape provides IosContainerShape(radius),
        content = content,
    )
}

/** Metrics shared across controls, at the values iOS 26 actually ships. */
object IosMetrics {
    val ListRowHeight: Dp = 44.dp
    val ListRowMinHeightWithSubtitle: Dp = 58.dp
    val ListRowInset: Dp = 16.dp
    val SectionSpacing: Dp = 35.dp
    val SeparatorThickness: Dp = 0.33.dp
    /** Corner radius for inset-grouped sections. */
    val GroupedCornerRadius: Dp = 26.dp
    val SheetCornerRadius: Dp = 38.dp
    val TabBarHeight: Dp = 56.dp
    val TabBarMargin: Dp = 12.dp
    val MenuCornerRadius: Dp = 14.dp
    val ButtonCornerRadius: Dp = 12.dp
    val IconTileSize: Dp = 29.dp
    val IconTileRadius: Dp = 7.dp
}
