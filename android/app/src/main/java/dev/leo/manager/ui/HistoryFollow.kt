package dev.leo.manager.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
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
    private var pointerMoved = false
    private var nonTouchScroll = false
    val busy get() = touching || flinging

    fun contact(down: Boolean) {
        touching = down
        if (down) {
            flinging = false
            moved = false
            pointerMoved = false
            nonTouchScroll = false
        }
    }

    fun motion() {
        pointerMoved = true
    }

    fun wheelOrKey() {
        nonTouchScroll = true
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Native selectable text can relocate after release as well as during
        // rebinding. Require an observed input, not just a UserInput scroll label.
        val directInput = source == NestedScrollSource.UserInput &&
            ((touching && pointerMoved) || nonTouchScroll)
        if (directInput || flinging) {
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
    nestedScroll(gesture)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(
                    Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown,
                    Key.MoveHome, Key.MoveEnd, Key.Spacebar,
                )) gesture.wheelOrKey()
            false
        }
        .pointerInput(gesture) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Scroll)
                        gesture.wheelOrKey()
                }
            }
        }
        .pointerInput(gesture) {
        awaitEachGesture {
            // Initial pass also observes touches handled by selectable Android text.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.contact(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.position != it.previousPosition }) gesture.motion()
                } while (event.changes.any { it.pressed })
                // Keep the contact active until selectable child views have handled
                // the release; their focus relocation is still part of this tap.
                awaitPointerEvent(PointerEventPass.Final)
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
            val tail = info.visibleItemsInfo.lastOrNull()
            // Selectable native text may relocate after release without changing
            // content or viewport size. Observe its measured position as well;
            // intentional gestures disable this collector before it can repin.
            val geometry = listOf(
                info.totalItemsCount, info.viewportSize.height, info.viewportEndOffset,
                info.afterContentPadding, tail?.index, tail?.offset, tail?.size,
            )
            Triple(geometry, rendering.revision, currentContent)
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
