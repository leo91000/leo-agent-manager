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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
fun rememberLive(vm: LeoViewModel, workspace: Workspace, path: String): LiveSnapshot {
    val owner = LocalLifecycleOwner.current
    val enabled = workspace.session.authenticated && !workspace.signingOut
    val api = if (enabled) vm.api else null
    var value by remember(path, api) { mutableStateOf(LiveSnapshot()) }
    val session = remember(path, api) { LiveSession() }
    LaunchedEffect(path, api, owner) {
        if (enabled && api != null) {
            val cacheGeneration = vm.historyCache.generation
            val key = vm.historyCache.key(workspace.origin, api.csrf, path)
            vm.historyCache.read(key)?.let {
                session.restore(it, path, api.streamGeneration.get())
                value = session.snapshot
            }
            try {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        api.live(path, session).collect {
                            if (!it.catchingUp || it.httpStatus != null) value = it
                            if (it.httpStatus in listOf(401, 403, 404)) {
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
                                    CachedHistory(it.cursor, it.history, it.state, it.events),
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
    return value
}

/** Restore once the feed exists; background resumes retain the existing list state. */
@Composable
fun rememberHistoryPosition(
    vm: LeoViewModel,
    workspace: Workspace,
    path: String,
    live: LiveSnapshot,
    list: LazyListState,
    visible: Boolean,
    follow: Boolean,
    setFollow: (Boolean) -> Unit,
): Boolean {
    var ready by remember(path) { mutableStateOf(false) }
    val currentFollow by rememberUpdatedState(follow)
    val api = vm.api
    val key = remember(path, api) { vm.historyCache.key(workspace.origin, api.csrf, path) }
    LaunchedEffect(path, live.catchingUp, visible) {
        if (!ready && !live.catchingUp && visible) {
            val saved = live.position
            if (saved != null) {
                setFollow(saved.follow)
                if (!saved.follow && live.events.isNotEmpty()) {
                    snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
                    list.scrollToItem(
                        saved.index.coerceIn(0, list.layoutInfo.totalItemsCount - 1),
                        saved.offset.coerceAtLeast(0),
                    )
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
                        ),
                    )
                    vm.historyCache.flush(key)
                }
            }
        }
    }
    return ready
}

@Composable
fun ModelPicker(
    catalog: ModelCatalog,
    model: String,
    reasoning: String,
    defaultModel: String = "",
    change: (String, String) -> Unit,
) {
    val models = catalog.models.filter { !it.hidden || it.model == model }
    val choices =
        listOf("" to "Par défaut") +
            models.map { it.model to it.displayName.ifBlank { it.model } } +
            if (model.isNotBlank() && models.none { it.model == model }) listOf(model to model)
            else emptyList()
    Choice("Modèle", model, choices.distinctBy { it.first }) { change(it, "") }
    val selected =
        catalog.models.find { it.model == model.ifBlank { defaultModel } }
            ?: catalog.models.find { it.isDefault }
    val efforts =
        selected?.supportedReasoningEfforts.orEmpty().map {
            it.reasoningEffort to it.reasoningEffort
        }
    if (efforts.isEmpty())
        Field("Raisonnement (vide = par défaut)", reasoning, { change(model, it) })
    else
        Choice(
            "Raisonnement",
            reasoning,
            (listOf("" to "Par défaut") +
                    efforts +
                    if (reasoning.isNotEmpty() && efforts.none { it.first == reasoning })
                        listOf(reasoning to reasoning)
                    else emptyList())
                .distinctBy { it.first },
        ) {
            change(model, it)
        }
    if (catalog.models.isEmpty()) Field("Nom du modèle", model, { change(it, reasoning) })
    if (catalog.stale || catalog.error.isNotBlank())
        Text(
            catalog.error.ifBlank { "Catalogue enregistré ; actualisation en attente." },
            style = MaterialTheme.typography.bodySmall,
        )
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
)

/** Fold updates in place within a turn; keep messages and errors in chronological order. */
internal fun timelineEntries(events: List<RunEvent>): List<TimelineEntry> {
    val folded = mutableListOf<RunEvent>()
    val positions = mutableMapOf<String, Int>()
    var legacyTool: Int? = null
    for (event in events) {
        if (event.type == "artifact") continue
        if (event.type == "turn.started") {
            positions.clear()
            legacyTool = null
        }
        if (event.activityData() == null && event.type == "item.completed" && legacyTool != null) {
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
        if (event.activityData() == null && event.type == "item.started") legacyTool = folded.size
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
    for (event in folded) {
        if (event.isMessage()) result.add(TimelineEntry("message:${event.id}", listOf(event), true))
        else {
            val last = result.lastOrNull()
            if (last != null && !last.message)
                result[result.lastIndex] = last.copy(events = last.events + event)
            else result.add(TimelineEntry("activity:${event.id}", listOf(event), false))
        }
    }
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
internal fun TimelineRow(vm: LeoViewModel, entry: TimelineEntry, agent: String) {
    if (entry.files.isNotEmpty()) ArtifactStrip(vm, entry.files)
    else if (entry.message) EventRow(vm, entry.events.single(), agent)
    else {
        var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
        val presentations = remember(entry.events) { entry.events.map(::presentActivity) }
        val errors = presentations.count { it.failed }
        val tools = presentations.count { it.kind != ActivityKind.NOTICE }
        Column(Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = !expanded },
                color = MaterialTheme.colorScheme.background,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        LeoIcons.Terminal,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (tools > 0) "$tools action${if (tools > 1) "s" else ""} de l’agent"
                        else "Suivi de l’exécution",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (errors > 0)
                        Text(
                            "$errors erreur${if (errors > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    Icon(
                        if (expanded) LeoIcons.Down else LeoIcons.Right,
                        if (expanded) "Réduire les actions" else "Afficher les actions",
                        Modifier.size(18.dp),
                    )
                }
            }
            if (expanded)
                Column(
                    Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.events.forEachIndexed { index, event ->
                        key(event.id) { ActivityCard(event, presentations[index]) }
                    }
                }
        }
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
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                color =
                    if (user) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(22.dp),
                modifier = if (user) Modifier.fillMaxWidth(0.9f) else Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(horizontal = if (user) 16.dp else 2.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (user) Text(content, style = MaterialTheme.typography.bodyLarge)
                    else Markdown(content)
                    val attachments = runCatching {
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
