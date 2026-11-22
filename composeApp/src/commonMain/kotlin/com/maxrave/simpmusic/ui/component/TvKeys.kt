package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
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
