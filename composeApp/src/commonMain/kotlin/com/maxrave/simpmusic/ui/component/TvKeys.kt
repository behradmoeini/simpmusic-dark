package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.maxrave.simpmusic.expect.ui.isTelevision

/*
 * A floating header and the page under it, for a TV remote.
 *
 * Most screens here float their header over a page that scrolls underneath it, so the page starts
 * at the top of the screen, level with the header or above it. Compose's arrow-key focus search only
 * takes a group as a candidate for Down when its top edge is BELOW the focused item's, so from the
 * header Down found nothing and focus never reached the page (Home, Search and Library on the Google
 * TV Streamer, 2026-11-21). A text field makes it worse: it keeps Down for its cursor.
 *
 * So the header hands the key over by name: [tvPage] marks the page, [tvDownTo] goes on the header.
 * Only on a TV; elsewhere both do nothing but mark the group.
 */

/** Marks the page a header's Down key leads to. */
fun Modifier.tvPage(page: FocusRequester): Modifier = focusRequester(page).focusGroup()

/**
 * On a TV, Down from anything inside this header moves focus into [page]. When the page is not on
 * screen its requester is unattached and throws; the key then takes its normal course.
 */
@Composable
fun Modifier.tvDownTo(page: FocusRequester): Modifier {
    if (!isTelevision()) return this
    return onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionDown &&
            runCatching { page.requestFocus() }.isSuccess
    }
}

/**
 * Whether this device is a TV, for code that is not composable. A device does not stop being a TV,
 * so the theme writes it once on its first composition ([isTelevision] needs a composition to ask).
 */
object TvDevice {
    @Volatile
    var isTv: Boolean = false
}

/**
 * `focusable()` everywhere but on a TV. The scrolling (marquee) text lines are focusable so a
 * keyboard can reach them; with a remote each one was an extra stop inside its own row, where OK
 * does nothing. Not composable, so it can stand wherever `focusable()` stood.
 */
fun Modifier.focusableUnlessTv(): Modifier = if (TvDevice.isTv) this else focusable()

/**
 * Which face of Home a TV shows. On a phone Music and Podcasts are chips on Home; on a TV the chip
 * row is gone (two rows of controls above the page was one too many to steer with a remote) and
 * the two are tabs in the top bar, which lives outside Home and so needs the state here.
 */
object TvHomeMode {
    var podcasts by androidx.compose.runtime.mutableStateOf(false)
}

/** When the remote was last used, for the TV's idle behaviour (the player opening by itself). */
object TvIdle {
    @Volatile
    var lastKeyAt: Long = System.currentTimeMillis()
}
