package dev.leo.manager.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.conflate

/** Observe gestures without consuming them or deriving intent from streaming layout changes. */
internal class HistoryFollowGesture(
    private val list: LazyListState,
    private val changeFollow: (Boolean) -> Unit,
) : NestedScrollConnection {
    var touching by mutableStateOf(false)
        private set
    private var flinging by mutableStateOf(false)
    private var moved = false
    val busy get() = touching || flinging

    fun contact(down: Boolean) {
        touching = down
        if (down) {
            flinging = false
            moved = false
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput || flinging) {
            if (consumed.y > 0f) {
                // Touch slop has already been handled by LazyColumn. Any actual
                // movement toward older content wins, even during a fast stream.
                moved = true
                changeFollow(false)
            } else if (consumed.y < 0f || available.y < 0f) {
                moved = true
                // Only a user scroll toward the end can re-arm follow. Incoming
                // text, keyboard resizing and programmatic scrolling cannot.
                if (!list.canScrollForward) changeFollow(true)
            }
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        flinging = moved && available.y != 0f
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        flinging = false
        return Velocity.Zero
    }
}

@Composable
internal fun rememberHistoryFollowGesture(list: LazyListState, changeFollow: (Boolean) -> Unit): HistoryFollowGesture {
    val currentChange by rememberUpdatedState(changeFollow)
    return remember(list) { HistoryFollowGesture(list) { currentChange(it) } }
}

internal fun Modifier.historyFollowGesture(gesture: HistoryFollowGesture): Modifier =
    nestedScroll(gesture).pointerInput(gesture) {
        awaitEachGesture {
            // Initial pass also observes touches handled by selectable Android text.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.contact(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                gesture.contact(false)
            }
        }
    }

/** Follow measured content, while allowing the user's touch or fling to take over immediately. */
@Composable
internal fun FollowHistoryTail(
    list: LazyListState,
    enabled: Boolean,
    content: Any?,
    rendering: MarkdownRendering,
    gesture: HistoryFollowGesture? = null,
) {
    val currentContent by rememberUpdatedState(content)
    val allowed = enabled && gesture?.busy != true
    LaunchedEffect(list, allowed, rendering, gesture) {
        if (allowed) snapshotFlow {
            val info = list.layoutInfo
            Triple(info.totalItemsCount to info.viewportSize.height, rendering.revision, currentContent)
        }.conflate().collect {
            withFrameNanos { }
            if (gesture?.busy == true) return@collect
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) {
                // Locate the last item first, then use its measured bottom. Its
                // height may exceed the viewport, and content padding counts too.
                if (list.layoutInfo.visibleItemsInfo.none { it.index == count - 1 }) {
                    list.scrollToItem(count - 1)
                    withFrameNanos { }
                }
                if (gesture?.busy == true) return@collect
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull { it.index == count - 1 }
                if (last != null) {
                    val remaining = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
                    if (remaining > 0) list.scrollBy(remaining.toFloat())
                }
            }
        }
    }
}
