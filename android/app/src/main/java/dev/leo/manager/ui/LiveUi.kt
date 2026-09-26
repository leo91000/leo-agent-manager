package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.leo.manager.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
fun rememberLive(
    vm: LeoViewModel,
    workspace: Workspace,
    path: String,
    beforeOlderPage: () -> Unit = {},
    streaming: Boolean = true,
): LiveSnapshot {
    val owner = LocalLifecycleOwner.current
    val enabled = workspace.session.authenticated && !workspace.signingOut
    val api = if (enabled) vm.api else null
    var value by remember(path, api) { mutableStateOf(LiveSnapshot()) }
    val session = remember(path, api) { LiveSession() }
    val scope = rememberCoroutineScope()
    var older by remember(path, api) { mutableStateOf(emptyList<RunEvent>()) }
    var olderHistory by remember(path, api) { mutableStateOf<String?>(null) }
    var boundary by remember(path, api) { mutableStateOf<Long?>(null) }
    var moreOlder by remember(path, api) { mutableStateOf(false) }
    var loadingOlder by remember(path, api) { mutableStateOf(false) }
    var olderError by remember(path, api) { mutableStateOf<String?>(null) }
    fun display(snapshot: LiveSnapshot): LiveSnapshot {
        if (snapshot.history != olderHistory) {
            older = emptyList()
            boundary = null
            olderHistory = snapshot.history
            olderError = null
        }
        return if (boundary == null) snapshot
        else
            snapshot.copy(
                events = mergeHistory(older, snapshot.events),
                oldest = boundary!!,
                hasOlder = moreOlder,
            )
    }
    LaunchedEffect(path, api, owner, streaming) {
        if (enabled && api != null) {
            var cacheGeneration = vm.historyCache.generation
            val key = vm.historyCache.key(workspace.origin, api.csrf, path)
            // A resumed session is already at least as recent as its cache.
            if (session.path != path) vm.historyCache.read(key)?.let {
                session.restore(it, path, api.streamGeneration.get())
                value = session.snapshot
            }
            try {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        // A preview settles on its first complete snapshot, then releases the stream:
                        // a restored session emits it before connecting, so a cached preview never connects.
                        api.live(path, session)
                            .transformWhile { emit(it); streaming || (it.catchingUp && it.httpStatus == null) }
                            .collect {
                            vm.acceptCacheRevision(it.state?.cacheRevision)
                            cacheGeneration = vm.historyCache.generation
                            if (!it.catchingUp || it.httpStatus != null) value = display(it)
                            if (it.httpStatus in listOf(401, 403, 404, 409)) {
                                vm.historyCache.remove(key)
                                value =
                                    LiveSnapshot(
                                        httpStatus = it.httpStatus,
                                        status = it.status,
                                        error = it.error,
                                    )
                            } else if (!it.catchingUp && it.state != null && it.history != null) {
                                vm.historyCache.save(
                                    key,
                                    CachedHistory(
                                        it.cursor,
                                        it.history,
                                        it.state,
                                        value.events,
                                        oldest = value.oldest,
                                        hasOlder = value.hasOlder,
                                    ),
                                    expectedGeneration = cacheGeneration,
                                )
                            }
                            if (it.httpStatus == 401)
                                vm.report(ApiException(401, "Session expirée"))
                        }
                    } finally {
                        withContext(NonCancellable) { vm.historyCache.flush(key) }
                    }
                }
            } finally {
                withContext(NonCancellable) { vm.historyCache.flush(key) }
            }
        }
    }
    return value.copy(
        loadingOlder = loadingOlder,
        olderError = olderError,
        loadOlder = {
            val history = value.history
            if (enabled && api != null && history != null && value.hasOlder && !loadingOlder) {
                loadingOlder = true
                olderError = null
                val before = value.oldest
                val cacheGeneration = vm.historyCache.generation
                scope.launch {
                    var pageApplied = false
                    try {
                        val page =
                            api.get<HistoryPage>(
                                path.removeSuffix("/stream") +
                                    "/history?before=$before&history=${segment(history)}"
                            )
                        if (value.history == history && page.history == history) {
                            require(page.oldest < before || !page.hasOlder) {
                                "Page d’historique invalide."
                            }
                            // Capture the reader synchronously before any response state is applied.
                            beforeOlderPage()
                            older = mergeHistory(page.events, older)
                            olderHistory = history
                            boundary = page.oldest
                            moreOlder = page.hasOlder
                            value = display(value)
                            // Release paging in the same UI turn as insertion. Disk persistence
                            // must not leave the reader on the new rows while restoration waits.
                            pageApplied = true
                            loadingOlder = false
                            value.state?.let { detail ->
                                vm.historyCache.save(
                                    vm.historyCache.key(workspace.origin, api.csrf, path),
                                    CachedHistory(
                                        value.cursor,
                                        history,
                                        detail,
                                        value.events,
                                        oldest = value.oldest,
                                        hasOlder = value.hasOlder,
                                    ),
                                    expectedGeneration = cacheGeneration,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A cache failure cannot undo an already applied server page.
                        if (!pageApplied) {
                            olderError = e.message ?: "Historique indisponible. Réessayez."
                            if (e is ApiException && e.status == 401) vm.report(e)
                        }
                    } finally {
                        // A later request may have started while this page was being cached.
                        if (!pageApplied) loadingOlder = false
                    }
                }
            }
        },
    )
}

/** The response boundary can capture a layout before snapshotFlow observes its final movement. */
internal class HistoryPageAnchor {
    var capture: (() -> Unit)? = null
    fun beforeApply() { capture?.invoke() }
}

/**
 * Keep Compose's key-based anchor: requesting an index here overrides the reader's current position
 * and may target a layout measured before the page was inserted.
 */
@Composable
internal fun rememberHistoryPaging(
    live: LiveSnapshot,
    list: LazyListState,
    ready: Boolean,
    follow: Boolean,
    keys: List<String>,
    rendering: MarkdownRendering? = null,
    pageAnchor: HistoryPageAnchor? = null,
    stopFollowing: () -> Unit,
): () -> Unit {
    val current by rememberUpdatedState(live)
    var headerAnchor by remember(list) { mutableStateOf<Pair<String, Int>?>(null) }
    var settling by remember(list) { mutableStateOf(false) }
    var requested by remember(list) { mutableStateOf<Pair<String?, Long>?>(null) }
    var capturedBeforeApply by remember(list) { mutableStateOf(false) }
    DisposableEffect(pageAnchor, list) {
        val capture = {
            val visible = list.layoutInfo.visibleItemsInfo
            headerAnchor =
                if (visible.firstOrNull()?.key == "history:older")
                    visible.firstOrNull { it.key != "history:older" }
                        ?.let { it.key.toString() to -it.offset }
                else null
            // A null anchor is intentional when the reader has left the header.
            capturedBeforeApply = true
        }
        pageAnchor?.capture = capture
        onDispose {
            pageAnchor?.let { if (it.capture === capture) it.capture = null }
        }
    }
    LaunchedEffect(list, live.loadingOlder) {
        val before = live.oldest
        if (live.loadingOlder)
            snapshotFlow { list.layoutInfo.visibleItemsInfo }
                .collect { visible ->
                    // Ignore the new page's layout if it arrives before this collector is
                    // cancelled.
                    if (capturedBeforeApply || current.oldest != before) return@collect
                    // Follow the reader during the request; never restore its initial position.
                    headerAnchor =
                        if (visible.firstOrNull()?.key == "history:older") {
                            visible
                                .firstOrNull { it.key != "history:older" }
                                ?.let { it.key.toString() to -it.offset }
                        } else null
                }
    }
    LaunchedEffect(live.loadingOlder, live.oldest, keys) {
        // A loading request can start before this effect from the previous frame runs.
        // Do not consume its anchor until a response (or an error) is actually present.
        val responseArrived =
            requested != (live.history to live.oldest) || live.olderError != null || !live.hasOlder
        if (!live.loadingOlder && settling && responseArrived) {
            val saved = headerAnchor
            headerAnchor = null
            if (saved != null) {
                val index = keys.indexOf(saved.first)
                if (index >= 0) {
                    val header = live.hasOlder || live.olderError != null
                    val target = index + if (header) 1 else 0
                    // Keep the content anchor even when a finger is still dragging.
                    // Discarding it leaves the persistent header pinned at index zero.
                    list.requestScrollToItem(target, saved.second)
                    rendering?.awaitLayout()
                    // An idle list can measure the final position immediately. While a
                    // finger owns scrolling, request its next layout without taking the gesture.
                    if (list.isScrollInProgress) list.requestScrollToItem(target, saved.second)
                    else list.scrollToItem(target, saved.second)
                }
            }
            rendering?.awaitLayout()
            settling = false
        }
    }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 640.dp.toPx() }
    val load = {
        if (current.hasOlder && !current.loadingOlder) {
            // Fast cached/local responses may finish before a loading frame exists.
            // Capture here as well; the observer refreshes this if the reader moves.
            val visible = list.layoutInfo.visibleItemsInfo
            headerAnchor =
                if (visible.firstOrNull()?.key == "history:older") {
                    visible
                        .firstOrNull { it.key != "history:older" }
                        ?.let { it.key.toString() to -it.offset }
                } else null
            settling = true
            requested = current.history to current.oldest
            capturedBeforeApply = false
            stopFollowing()
            current.loadOlder()
        }
    }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(list, ready, follow, threshold) {
        // Re-arm only after leaving the top zone, never just because a response
        // changed the cursor. The old layout can still show index zero then.
        var armed = true
        if (ready && !follow)
            snapshotFlow {
                    val snapshot = current
                    val nearStart =
                        list.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                            list.firstVisibleItemIndex <= 1 &&
                            list.firstVisibleItemScrollOffset < threshold
                    val available = !settling && !snapshot.loadingOlder
                    Triple(
                        nearStart,
                        available,
                        snapshot.hasOlder &&
                            snapshot.olderError == null &&
                            requested != (snapshot.history to snapshot.oldest),
                    )
                }
                .collect { (nearStart, available, canLoad) ->
                    if (available) {
                        if (!nearStart) armed = true
                        else if (armed && canLoad) {
                            armed = false
                            currentLoad()
                        }
                    }
                }
    }
    return load
}

internal fun androidx.compose.foundation.lazy.LazyListScope.historyHeader(
    live: LiveSnapshot,
    load: () -> Unit,
) {
    if (live.hasOlder || live.loadingOlder || live.olderError != null)
        item(key = "history:older") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                    if (live.loadingOlder) CircularProgressIndicator(Modifier.size(20.dp))
                    else TextButton(onClick = load) { Text("Messages précédents") }
                }
                live.olderError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
}

/** Restore once the feed exists; background resumes retain the existing list state. */
@Composable
internal fun rememberHistoryPosition(
    vm: LeoViewModel,
    workspace: Workspace,
    path: String,
    live: LiveSnapshot,
    list: LazyListState,
    visible: Boolean,
    follow: Boolean,
    rendering: MarkdownRendering? = null,
    setFollow: (Boolean) -> Unit,
): Boolean {
    var ready by remember(path) { mutableStateOf(false) }
    val currentFollow by rememberUpdatedState(follow)
    val firstEvent by rememberUpdatedState(live.events.firstOrNull()?.id)
    val api = vm.api
    val key = remember(path, api) { vm.historyCache.key(workspace.origin, api.csrf, path) }
    LaunchedEffect(path, live.catchingUp, visible) {
        if (!ready && !live.catchingUp && visible) {
            val saved = live.position
            if (saved != null) {
                setFollow(saved.follow)
                if (!saved.follow && live.events.isNotEmpty()) {
                    restoreHistoryPosition(list, saved, rendering)
                }
            }
            ready = true
        }
    }
    LaunchedEffect(key, ready, visible) {
        if (ready && visible) {
            try {
                snapshotFlow {
                        ReadingPosition(
                            list.firstVisibleItemIndex,
                            list.firstVisibleItemScrollOffset,
                            currentFollow,
                            firstEvent,
                        )
                    }
                    .collectLatest { vm.historyCache.position(key, it) }
            } finally {
                withContext(NonCancellable) {
                    vm.historyCache.position(
                        key,
                        ReadingPosition(
                            list.firstVisibleItemIndex,
                            list.firstVisibleItemScrollOffset,
                            currentFollow,
                            firstEvent,
                        ),
                    )
                    vm.historyCache.flush(key)
                }
            }
        }
    }
    return ready
}

internal fun RunEvent.item(): JsonObject? = activityData()?.get("item") as? JsonObject

internal fun JsonObject?.string(key: String) =
    this?.get(key)?.let { it as? JsonPrimitive }?.contentOrNull.orEmpty()

internal fun RunEvent.isMessage(): Boolean =
    type == "chat.user" ||
        item().string("type") == "agent_message" ||
        (activityData() == null && type == "item.completed" && item() == null)

internal data class TimelineEntry(
    val key: String,
    val events: List<RunEvent>,
    val message: Boolean,
    val files: List<Deliverable> = emptyList(),
    val notice: Boolean = false,
)

/** Fold updates in place within a turn; keep messages and errors in chronological order. */
internal fun timelineEntries(events: List<RunEvent>, chat: Boolean = false): List<TimelineEntry> {
    val folded = mutableListOf<RunEvent>()
    val positions = mutableMapOf<String, Int>()
    var legacyTool: Int? = null
    for (event in events) {
        if (event.type == "artifact") continue
        if (event.type == "turn.started") {
            positions.clear()
            legacyTool = null
        }
        val legacy =
            (event.type == "item.started" || event.type == "item.completed") &&
                event.activityData() == null
        if (legacy && event.type == "item.completed" && legacyTool != null) {
            val index = legacyTool
            val original = folded[index]
            folded[index] =
                event.copy(
                    id = original.id,
                    createdAt = original.createdAt,
                    payload =
                        mapOf(
                            "item" to
                                buildJsonObject {
                                    put("type", "saved_output")
                                    put("text", event.text)
                                }
                        ),
                )
            legacyTool = null
            continue
        }
        if (legacy && event.type == "item.started") legacyTool = folded.size
        val item = event.item()
        val id = item.string("id")
        if (!event.isMessage() && id.isNotBlank()) {
            val key = "${item.string("type")}:$id"
            val index = positions[key]
            if (index == null) {
                positions[key] = folded.size
                folded.add(event)
            } else folded[index] = event.copy(id = folded[index].id)
        } else folded.add(event)
    }
    val result = mutableListOf<TimelineEntry>()
    var activity = mutableListOf<RunEvent>()
    fun flushActivity() {
        if (activity.isNotEmpty()) {
            result.add(TimelineEntry("activity:${activity.first().id}", activity.toList(), false))
            activity = mutableListOf()
        }
    }
    val recovered =
        if (chat)
            recoveredChatConnections(events, folded.filter { it.isMessage() }.map { it.id }.toSet())
        else emptySet()
    for (event in folded) {
        if (event.isMessage()) {
            flushActivity()
            result.add(TimelineEntry("message:${event.displayId ?: event.id}", listOf(event), true))
        } else {
            val presentation = if (chat) presentActivity(event) else null
            if (presentation?.kind == ActivityKind.NOTICE) {
                val interrupted =
                    event.type == "status" &&
                        event.text.lowercase() in listOf("cancelled", "interrupted")
                if (
                    event.id !in recovered &&
                        (presentation.failed || interrupted || presentation.connectionInterrupted())
                ) {
                    flushActivity()
                    result.add(
                        TimelineEntry("notice:${event.id}", listOf(event), false, notice = true)
                    )
                }
            } else activity.add(event)
        }
    }
    flushActivity()
    return result
}

/**
 * Match the web feed: attach each publication beside its response, keeping versions per message.
 */
internal fun deliveryTimeline(
    entries: List<TimelineEntry>,
    artifacts: List<Deliverable>,
): List<TimelineEntry> {
    val groups = linkedMapOf<Int, MutableList<Deliverable>>()
    val latest =
        artifacts
            .groupBy { it.messageId.orEmpty() to it.key }
            .values
            .map { it.maxBy { file -> file.version } }
    for (file in latest) {
        var position = entries.lastIndex
        for ((index, entry) in entries.withIndex()) {
            if (!entry.message) continue
            val event = entry.events.single()
            val time =
                (event.payload?.get("createdAt") as? JsonPrimitive)?.longOrNull?.takeIf {
                    it > 0 && it <= event.createdAt
                } ?: event.createdAt
            if (time < file.createdAt) continue
            position = if (event.type == "chat.user") index - 1 else index
            break
        }
        groups.getOrPut(position) { mutableListOf() }.add(file)
    }
    return buildList {
        fun append(index: Int) {
            groups[index]?.let {
                add(
                    TimelineEntry(
                        "files:${entries.getOrNull(index)?.key ?: "start"}",
                        emptyList(),
                        false,
                        it,
                    )
                )
            }
        }
        append(-1)
        entries.forEachIndexed { index, entry ->
            add(entry)
            append(index)
        }
    }
}

@Composable
internal fun TimelineRow(
    vm: LeoViewModel,
    entry: TimelineEntry,
    agent: String,
    rendering: MarkdownRendering? = null,
    /** While the agent works, its step in progress is shown by the working indicator instead. */
    hideRunning: Boolean = false,
) {
    if (entry.files.isNotEmpty()) ArtifactStrip(vm, entry.files)
    else if (entry.message)
        CompositionLocalProvider(LocalMarkdownRendering provides rendering) {
            EventRow(vm, entry.events.single(), agent)
        }
    else if (entry.notice) ChatNotice(presentActivity(entry.events.single()))
    else {
        val presentations = remember(entry.events) { entry.events.map(::presentActivity) }
        AgentActions(entry.key, presentations, hideRunning)
    }
}

@Composable
fun EventRow(vm: LeoViewModel, event: RunEvent, agent: String = "Leo") {
    val item = event.item()
    val user = event.type == "chat.user"
    if (event.isMessage()) {
        val content =
            if (user) (event.payload?.get("text") as? JsonPrimitive)?.contentOrNull ?: event.text
            else item.string("text").ifEmpty { event.text }
        BoxWithConstraints(
            Modifier.fillMaxWidth(),
            contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                color =
                    if (user) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                modifier = if (user) Modifier.widthIn(max = maxWidth * 0.9f) else Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(horizontal = if (user) 16.dp else 0.dp, vertical = if (user) 12.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!user)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                agent,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                java.text
                                    .SimpleDateFormat(
                                        "HH:mm",
                                        androidx.compose.ui.platform.LocalConfiguration.current.locales[
                                                0],
                                    )
                                    .format(java.util.Date(event.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (user)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                highlightSkills(content, LocalSkillNames.current, skillMentionStyle()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    else Markdown(content)
                    if (user)
                        Text(
                            java.text.SimpleDateFormat("HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                                .format(java.util.Date(event.createdAt)),
                            Modifier.align(Alignment.End),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    val attachments =
                        runCatching {
                                event.payload?.get("attachments")?.let {
                                    wireJson.decodeFromJsonElement<List<ChatAttachment>>(it)
                                }
                            }
                            .getOrNull()
                            .orEmpty()
                    if (attachments.isNotEmpty()) AttachmentList(vm, attachments)
                }
            }
        }
    } else ActivityCard(event)
}

internal suspend fun restoreHistoryPosition(
    list: LazyListState,
    saved: ReadingPosition,
    rendering: MarkdownRendering?,
) {
    snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
    val index = saved.index.coerceIn(0, list.layoutInfo.totalItemsCount - 1)
    list.scrollToItem(index, saved.offset.coerceAtLeast(0))
    rendering?.awaitLayout()
    list.scrollToItem(index, saved.offset.coerceAtLeast(0))
}
