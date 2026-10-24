/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.convx.music.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp

/* Pure geometry for the mini-player/full-player shared transition. Each element
 * uses its own easing so the artwork leads the container during expansion.
 */
/** Weight of the linear term in the artwork's vertical travel; the rest is [easeInCubic]. */
const val ARTWORK_VERTICAL_LINEAR_WEIGHT = 0.4f

/**
 * How long the screen corners take to open out to square once the sheet is fully
 * expanded. Deliberately short and deliberately separate from the sheet's own motion:
 * the corners resolve after the sheet arrives, not with it.
 */
const val SCREEN_CORNER_EXPANSION_MILLIS = 140

/**
 * Single source of truth for progress sanitization: NaN/Inf from a torn drag
 * read or a divide-by-zero must clamp to rest, never propagate NaN into a Rect
 * (which draws nothing and poisons every lerp downstream). All geometry below
 * routes through here so edge handling lives in one place.
 */
internal fun sanitizedProgress(progress: Float): Float =
    if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)

fun easeInCubic(value: Float): Float {
    val clamped = sanitizedProgress(value)
    return clamped * clamped * clamped
}

fun easeOutCubic(value: Float): Float {
    val clamped = sanitizedProgress(value)
    val inverse = 1f - clamped
    return 1f - inverse * inverse * inverse
}

/**
 * The sheet container's bounds at [progress], 0 being [source] (the mini player) and 1
 * being [target] (the full player).
 *
 * Plain linear edge interpolation (the reference app PlayerSheetGeometry): the spring driving
 * progress already shapes the motion, so the rect itself adds no second curve.
 */
fun sharedContainerRect(source: Rect, target: Rect, progress: Float): Rect {
    val fraction = sanitizedProgress(progress)
    return Rect(
        left = lerp(source.left, target.left, fraction),
        top = lerp(source.top, target.top, fraction),
        right = lerp(source.right, target.right, fraction),
        bottom = lerp(source.bottom, target.bottom, fraction),
    )
}

/**
 * Who owns finger input during the flight (the reference app PlayerInputOwner).
 *
 * While dragging, the end the drag started from owns input so a close gesture
 * can reverse from the current frame. At rest the mini owns input only before
 * the handoff; the full player owns it once settled or its layers are ready.
 * In between, neither does -- taps would fight the settling spring.
 */
internal enum class PlayerInputOwner { MINI, FULL, NONE }

internal const val PLAYER_INPUT_MINI_END_PROGRESS = 0.2f

internal fun playerSheetInputOwner(
    targetOpen: Boolean,
    isDragging: Boolean,
    dragStartedFromMiniPlayer: Boolean,
    progress: Float,
    sharedLayersReady: Boolean,
): PlayerInputOwner {
    val safeProgress = sanitizedProgress(progress)
    return when {
        isDragging -> if (dragStartedFromMiniPlayer) PlayerInputOwner.MINI else PlayerInputOwner.FULL
        !targetOpen && safeProgress <= PLAYER_INPUT_MINI_END_PROGRESS ->
            PlayerInputOwner.MINI
        sharedLayersReady || targetOpen && safeProgress >= 1f -> PlayerInputOwner.FULL
        else -> PlayerInputOwner.NONE
    }
}

/**
 * Where the mini player's halves sit inside the animating container (the reference app
 * clip split, pill-origin recording, fullscreen coords).
 *
 * Title side: x compensates the corner travel so the title stays absolute
 * while the card grows around it; y rides the card top. Controls side adds
 * [sharedMiniPlayerControlsShift] so play/next keep their right inset and
 * break away rightward with the edge.
 */
fun sharedMiniPlayerTitleShift(
    sourcePlayerBounds: Rect,
    animatedPlayerBounds: Rect,
): Float = sourcePlayerBounds.left - animatedPlayerBounds.left

fun sharedMiniPlayerControlsShift(
    sourcePlayerBounds: Rect,
    animatedPlayerBounds: Rect,
    controlsBounds: Rect,
): Float {
    val sourceRightInset = sourcePlayerBounds.right - controlsBounds.right
    return animatedPlayerBounds.right - sourceRightInset - controlsBounds.right
}

/**
 * The artwork's bounds at [progress].
 *
 * Scaled uniformly from the source rather than interpolated per-edge, so a non-square
 * mini-player cover keeps its aspect ratio the whole way across instead of stretching
 * into the target's shape.
 */
fun sharedArtworkRect(source: Rect, target: Rect, progress: Float): Rect {
    val fraction = sanitizedProgress(progress)
    val centerX = lerp(source.center.x, target.center.x, easeOutCubic(fraction))
    val verticalFraction = lerp(
        easeInCubic(fraction),
        fraction,
        ARTWORK_VERTICAL_LINEAR_WEIGHT,
    )
    val centerY = lerp(source.center.y, target.center.y, verticalFraction)
    val sourceWidth = source.width.coerceAtLeast(1f)
    // coerceAtLeast(sourceWidth): a target narrower than the source would shrink the
    // cover mid-flight, which only happens from a bad measurement, never by design.
    val targetWidth = target.width.coerceAtLeast(sourceWidth)
    val scale = lerp(1f, targetWidth / sourceWidth, fraction)
    val width = sourceWidth * scale
    val height = source.height.coerceAtLeast(1f) * scale
    return Rect(
        left = centerX - width / 2f,
        top = centerY - height / 2f,
        right = centerX + width / 2f,
        bottom = centerY + height / 2f,
    )
}

/**
 * The travelling cover's visible window at [progress]: centre on the same curves as
 * [sharedArtworkRect], but width and height interpolate per axis, so a square pill cover
 * can open into a non-square target (V2's Hero, 0.65 of the screen tall) and land on
 * exactly that rect instead of a square that pops to the real shape on arrival.
 */
fun sharedArtworkWindow(source: Rect, target: Rect, progress: Float): Rect {
    val fraction = sanitizedProgress(progress)
    val centerX = lerp(source.center.x, target.center.x, easeOutCubic(fraction))
    val centerY = lerp(
        source.center.y,
        target.center.y,
        lerp(easeInCubic(fraction), fraction, ARTWORK_VERTICAL_LINEAR_WEIGHT),
    )
    val halfWidth = lerp(source.width, target.width, fraction).coerceAtLeast(1f) / 2f
    val halfHeight = lerp(source.height, target.height, fraction).coerceAtLeast(1f) / 2f
    return Rect(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
}

/**
 * Ease-in-out sine: zero velocity at both ends, so a fade or colour change has no
 * visible start or finish -- the curve every "ambient" transition (background colour,
 * video fade, artwork melt) uses.
 */
val SeamlessEasing = androidx.compose.animation.core.CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * Length of an ambient track-change transition: the DJ crossfade's own length while
 * one is running, so the backdrop turns over with the audio, else a slow default.
 */
fun ambientTransitionMillis(crossfadeMs: Long): Int =
    if (crossfadeMs > 0L) crossfadeMs.coerceIn(900L, 8_000L).toInt() else AMBIENT_TRANSITION_DEFAULT_MS

const val AMBIENT_TRANSITION_DEFAULT_MS = 1_100

/**
 * Width of the progress band over which the screen-corner expansion fades out.
 *
 * Gating the expansion on `progress == 1f` exactly is fine on the way
 * in — the corners open out after the sheet has settled — but it pops on the way out: the
 * first pixel of a downward drag takes progress off 1f, which kills the expansion in one
 * frame and snaps the radius from square back to the full screen radius. Fading the
 * expansion across the top slice of the drag removes the jump while still keeping the
 * corners square for the entire time the sheet is actually at rest.
 */
const val CORNER_EXPANSION_PROGRESS_BAND = 0.02f

/**
 * Per-corner radii of the travelling card (the reference app SharedContainerCornerRadii).
 * Uniform in practice; the struct keeps the door open for squircle-style
 * per-corner treatment without reshaping the call chain.
 */
internal data class SharedContainerCornerRadii(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float,
) {
    companion object {
        fun uniform(cornerRadius: Float): SharedContainerCornerRadii =
            SharedContainerCornerRadii(
                topStart = cornerRadius,
                topEnd = cornerRadius,
                bottomEnd = cornerRadius,
                bottomStart = cornerRadius,
            )
    }
}

/**
 * Corner radii mid-flight, exactly 0 at full expansion: the card's own curve
 * ends where the screen glass takes over.
 */
internal fun sharedContainerCornerRadii(
    source: SharedContainerCornerRadii,
    target: SharedContainerCornerRadii,
    progress: Float,
): SharedContainerCornerRadii {
    val fraction = sanitizedProgress(progress)
    fun interpolate(sourceRadius: Float, targetRadius: Float): Float = if (fraction < 1f) {
        lerp(sourceRadius, targetRadius, fraction)
    } else {
        0f
    }
    return SharedContainerCornerRadii(
        topStart = interpolate(source.topStart, target.topStart),
        topEnd = interpolate(source.topEnd, target.topEnd),
        bottomEnd = interpolate(source.bottomEnd, target.bottomEnd),
        bottomStart = interpolate(source.bottomStart, target.bottomStart),
    )
}

/**
 * Corner radius for the sheet at [progress].
 *
 * Two stages. Through the transition the radius runs from the mini player's corner to
 * [expandedCornerRadius] — which should be the device's *physical* screen corner radius,
 * not a design value, so the sheet's corners sit exactly on the glass. Then, as the sheet
 * settles, [screenCornerExpansionProgress] takes the radius to zero so the player fills
 * the display edge to edge and the glass itself provides the rounding.
 *
 * The second stage is confined to the very top of the progress range (see
 * [CORNER_EXPANSION_PROGRESS_BAND]); through the body of a drag the radius stays on the
 * first curve, so the corners never go square during a gesture the user has not committed
 * to.
 */
fun sharedContainerCornerRadius(
    collapsedCornerRadius: Float,
    expandedCornerRadius: Float,
    progress: Float,
    screenCornerExpansionProgress: Float,
): Float {
    val fraction = sanitizedProgress(progress)
    val collapsed = if (collapsedCornerRadius.isFinite()) collapsedCornerRadius else 0f
    val expanded = if (expandedCornerRadius.isFinite()) expandedCornerRadius else 0f
    val transitionRadius = lerp(collapsed, expanded, fraction)
    val settled = ((fraction - (1f - CORNER_EXPANSION_PROGRESS_BAND)) / CORNER_EXPANSION_PROGRESS_BAND)
        .coerceIn(0f, 1f)
    val expansion = sanitizedProgress(screenCornerExpansionProgress) * settled
    return lerp(transitionRadius, 0f, expansion)
}

/**
 * Whether the player is filling the physical display, and may therefore round its corners
 * to the device's own radius.
 *
 * In split screen or picture-in-picture the window's corners are not the screen's, so
 * matching the physical radius there would draw a curve that lines up with nothing.
 */
fun playerWindowUsesPhysicalScreenCorners(
    currentWidth: Int,
    currentHeight: Int,
    maximumWidth: Int,
    maximumHeight: Int,
    isInMultiWindowMode: Boolean,
    isInPictureInPictureMode: Boolean,
): Boolean = !isInMultiWindowMode &&
    !isInPictureInPictureMode &&
    currentWidth >= maximumWidth &&
    currentHeight >= maximumHeight
