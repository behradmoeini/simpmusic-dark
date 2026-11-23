package com.maxrave.simpmusic.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What a `clickable` shows on a TV: a white outline around whatever the remote is on, the way
 * Spotify's TV app marks focus.
 *
 * The app-wide ripple paints focus as a 4% tint, which is right for a phone (where focus hardly
 * exists) and invisible from a sofa. A ripple cannot draw an outline, so on a TV this takes its place
 * as LocalIndication; there is no touch to ripple for there anyway.
 */
/**
 * The colour of focus on a TV: the theme's accent, set by AppTheme. The owner's rule (2026-11-23):
 * whatever the remote is on is painted in the theme's colour, so there is never a question of
 * where it is. A white outline on a dark page of white text and bright covers did not stand out.
 */
internal object TvFocusColor {
    @Volatile
    var accent: Color = Color(0xFF1ED760)
}

internal object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = TvFocusNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -7201
}

private class TvFocusNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(),
    DrawModifierNode {
    private var focused = false
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            var focusCount = 0
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusCount++
                    is FocusInteraction.Unfocus -> focusCount--
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressCount--
                }
                focused = focusCount > 0
                pressed = pressCount > 0
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused && !pressed) return
        val stroke = 3.dp.toPx()
        val accent = TvFocusColor.accent
        // Small round things (an icon button) get a round outline, cards a softly rounded one.
        val radius = minOf(8.dp.toPx(), size.minDimension / 2)
        val corner = CornerRadius(radius, radius)
        if (pressed) drawRoundRect(accent.copy(alpha = 0.3f), cornerRadius = corner)
        if (focused) {
            // A wash of the accent over the whole item and a thick outline in it. Inside the
            // bounds, so a clipped parent cannot cut it off.
            drawRoundRect(accent.copy(alpha = 0.22f), cornerRadius = corner)
            drawRoundRect(
                color = accent,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        }
    }
}

/**
 * Where a list puts the item the remote moves to, on a TV: its leading edge a fixed distance in
 * from the list's own, the way TV apps keep the focused row and card in one place and move the
 * page under it.
 *
 * The default only scrolls far enough for the item to be inside the list, and the lists here run
 * under a floating header, so the focused shelf ended up behind the header with nothing visibly
 * focused. [pivotPx] clears the header and leaves the shelf's title showing above its cards. In a
 * row the same distance keeps the previous card peeking in at the left.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class TvBringIntoViewSpec(
    private val pivotPx: Float,
    /** What covers the list's far end (the mini player): the focused item is kept clear of it. */
    private val coveredEndPx: Float = 0f,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        // An item too large to sit at the pivot goes as far in as still fits whole.
        val target = minOf(pivotPx, (containerSize - coveredEndPx - size).coerceAtLeast(0f))
        return offset - target
    }
}
