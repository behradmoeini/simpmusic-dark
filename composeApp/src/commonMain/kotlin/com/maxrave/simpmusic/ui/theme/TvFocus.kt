package com.maxrave.simpmusic.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
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
        val stroke = 2.dp.toPx()
        // Small round things (an icon button) get a round outline, cards a softly rounded one.
        val radius = minOf(8.dp.toPx(), size.minDimension / 2)
        val corner = CornerRadius(radius, radius)
        if (pressed) drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = corner)
        if (focused) {
            // Inside the bounds, so a clipped parent cannot cut it off.
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        }
    }
}
