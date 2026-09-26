package dev.leo.manager.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.LiveSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pagination uses the real lazy list, async Markdown renderer and production paging hook. */
abstract class HistoryPagingCases(
    @get:Rule val compose: ComposeContentTestRule = createComposeRule()
) {
    private lateinit var list: LazyListState
    private lateinit var rendering: MarkdownRendering
    private lateinit var scope: CoroutineScope
    private var numbers by mutableStateOf((21..40).toList())
    private var loading by mutableStateOf(false)
    private var hasOlder by mutableStateOf(true)
    private var ready by mutableStateOf(false)
    private var requests = 0
    private val pageAnchor = HistoryPageAnchor()

    private fun start(
        atHeader: Boolean = false,
        initialOffset: Int = 420,
        expectLoading: Boolean = true,
    ) {
        compose.setContent {
            list = rememberLazyListState()
            rendering = remember { MarkdownRendering() }
            scope = rememberCoroutineScope()
            val live =
                LiveSnapshot(
                    oldest = numbers.first().toLong(),
                    hasOlder = hasOlder,
                    loadingOlder = loading,
                    loadOlder = {
                        requests++
                        loading = true
                    },
                )
            val load =
                rememberHistoryPaging(
                    live,
                    list,
                    ready,
                    false,
                    numbers.map { "message:$it" },
                    rendering,
                    pageAnchor,
                ) {}
            LeoTheme("dark") {
                Surface(Modifier.fillMaxWidth().height(620.dp)) {
                    CompositionLocalProvider(LocalMarkdownRendering provides rendering) {
                        LazyColumn(
                            modifier = Modifier.testTag("paged-history"),
                            state = list,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            historyHeader(live, load)
                            items(numbers, key = { "message:$it" }) { number ->
                                Markdown(
                                    (1..35).joinToString("\n\n") {
                                        "Message $number, paragraphe $it. Le lecteur doit garder exactement le même texte devant les yeux."
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(20000) { rendering.revision > 0 && rendering.pending == 0 }
        var positioned = false
        compose.runOnIdle {
            scope.launch {
                list.scrollToItem(if (atHeader) 0 else 1, if (atHeader) 0 else initialOffset)
                rendering.awaitLayout()
                list.scrollToItem(if (atHeader) 0 else 1, if (atHeader) 0 else initialOffset)
                positioned = true
            }
        }
        compose.waitUntil(20000) { positioned }
        compose.runOnIdle { ready = true }
        if (expectLoading) compose.waitUntil(10000) { loading } else compose.waitForIdle()
    }

    private fun positionAt(index: Int, offset: Int) {
        // Distant rows render asynchronously when a test jumps directly to them.
        repeat(3) {
            var done = false
            compose.runOnIdle {
                scope.launch {
                    list.scrollToItem(index, offset)
                    done = true
                }
            }
            compose.waitUntil(20000) { done }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
    }

    private fun capture(name: String) {
        val directory = System.getProperty("leo.screenshots.dir") ?: return
        File(directory).mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun offset() =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "message:21" }?.offset

    @Test
    fun olderMarkdownPageKeepsTheSameTextAtTheSamePixel() {
        start()
        val before = compose.runOnIdle { offset()!! }
        capture("before-page")
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            hasOlder = false
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals("The older page must be attached", 25, list.layoutInfo.totalItemsCount)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "Prepending must preserve the visible paragraph, not jump to a different message",
                before,
                offset(),
            )
            assertEquals(1, requests)
        }
        capture("after-page")
    }

    @Test
    fun scrollingWhilePageLoadsKeepsTheLatestReadingPosition() {
        start()
        var moved = false
        compose.runOnIdle {
            scope.launch {
                list.scrollBy(-170f)
                moved = true
            }
        }
        compose.waitUntil(10000) { moved }
        val before = compose.runOnIdle { offset()!! }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            hasOlder = false
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals("The older page must be attached", 25, list.layoutInfo.totalItemsCount)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "Loading must not undo scrolling performed while waiting",
                before,
                offset(),
            )
        }
    }

    @Test
    fun visibleLoaderDisappearsWithoutMovingTheFirstParagraph() {
        start(atHeader = true)
        val before = compose.runOnIdle { offset()!! }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            hasOlder = false
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "Removing the visible history header must preserve the first paragraph",
                before,
                offset(),
            )
        }
    }

    @Test
    fun visibleHeaderWithMorePagesDoesNotLoadTheEntireHistory() {
        start(atHeader = true)
        val before = compose.runOnIdle { offset()!! }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        try {
            compose.waitUntil(20000) { rendering.pending == 0 && offset() == before }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Page anchor not settled: expected=$before, actual=${offset()}, pending=${rendering.pending}, " +
                    "requests=$requests, scrolling=${list.isScrollInProgress}, visible=${list.layoutInfo.visibleItemsInfo.map { it.key to it.offset }}",
                failure,
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("A response must not automatically request another page", 1, requests)
            assertEquals("The reader must remain on the old first paragraph", before, offset())
        }
    }

    @Test
    fun pageArrivingDuringAHeldDragPreservesTextAndDoesNotCascade() {
        start(initialOffset = 100)
        val history = compose.onNodeWithTag("paged-history")
        history.performTouchInput {
            down(Offset(2f, 180f))
            moveBy(Offset(0f, 250f), delayMillis = 160)
        }
        compose.runOnIdle {
            assertTrue("The finger must still be dragging", list.isScrollInProgress)
            assertEquals(
                "The drag must reach the history control",
                "history:older",
                list.layoutInfo.visibleItemsInfo.first().key,
            )
        }
        val before = compose.runOnIdle { offset()!! }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        try {
            compose.waitUntil(20000) { rendering.pending == 0 && offset() == before }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Page anchor not settled: expected=$before, actual=${offset()}, pending=${rendering.pending}, " +
                    "requests=$requests, scrolling=${list.isScrollInProgress}, visible=${list.layoutInfo.visibleItemsInfo.map { it.key to it.offset }}",
                failure,
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Holding a finger must not trigger cascading requests", 1, requests)
            assertEquals(
                "New history must not replace the text under the held finger",
                before,
                offset(),
            )
        }
        history.performTouchInput { moveBy(Offset(0f, 48f), delayMillis = 160) }
        // Device input is dispatched asynchronously; await the existing MOVE's
        // resulting layout without injecting another gesture or lifting the finger.
        try {
            compose.waitUntil(5000) { (offset() ?: Int.MIN_VALUE) > before }
        } catch (failure: Throwable) {
            throw AssertionError(
                "The held MOVE was not applied: before=$before, after=${offset()}, " +
                    "scrolling=${list.isScrollInProgress}, visible=${list.layoutInfo.visibleItemsInfo.map { it.key to it.offset }}",
                failure,
            )
        }
        val moved = compose.runOnIdle { offset()!! }
        history.performTouchInput {
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(moved, offset())
        }
    }

    @Test
    fun responseInTheSameFrameAsReachingTheHeaderKeepsTheLatestText() {
        start(initialOffset = 100)
        var before = 0
        compose.runOnIdle {
            // A cached response can arrive before snapshotFlow observes this layout.
            // Keep movement and response in one main-thread turn to exercise that race.
            list.dispatchRawDelta(-250f)
            assertEquals("history:older", list.layoutInfo.visibleItemsInfo.first().key)
            before = offset()!!
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            loading = false
        }
        compose.waitForIdle()
        try {
            compose.waitUntil(20000) { rendering.pending == 0 && offset() == before }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Same-frame anchor not settled: expected=$before, actual=${offset()}, pending=${rendering.pending}, " +
                    "requests=$requests, visible=${list.layoutInfo.visibleItemsInfo.map { it.key to it.offset }}",
                failure,
            )
        }
        compose.runOnIdle {
            assertEquals(
                "The response must preserve the latest layout, even before its observer runs",
                before,
                offset(),
            )
            assertEquals("A cached response must not cascade into another page", 1, requests)
        }
    }

    @Test
    fun readingDeepInsideALongFirstMessageDoesNotLoadOlderPages() {
        start(initialOffset = 1800, expectLoading = false)
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {
            assertEquals(
                "A long message is not near the top just because its index is small",
                0,
                requests,
            )
        }
    }

    @Test
    fun consecutivePagesKeepTheCurrentParagraphWithoutDuplicateRequests() {
        start()
        val before = compose.runOnIdle { offset()!! }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(before, offset())
            assertEquals(1, requests)
        }
        positionAt(1, 300)
        compose.waitUntil(20000) { loading }
        val nextBefore = compose.runOnIdle {
            list.layoutInfo.visibleItemsInfo.first { it.key == "message:16" }.offset
        }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (11..40).toList()
            loading = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                nextBefore,
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "message:16" }?.offset,
            )
            assertEquals("Only one request per page", 2, requests)
        }
    }

    @Test
    fun returningToTheLatestMessageWhileLoadingIsNotUndone() {
        start()
        positionAt(20, 300)
        val before = compose.runOnIdle {
            list.layoutInfo.visibleItemsInfo.first { it.key == "message:40" }.offset
        }
        compose.runOnIdle {
            pageAnchor.beforeApply()
            numbers = (16..40).toList()
            loading = false
            hasOlder = false
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                before,
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "message:40" }?.offset,
            )
        }
    }
}
