@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.leo.manager.data.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*

@Composable
fun ChatsScreen(vm: LeoViewModel, state: Workspace, open: (String) -> Unit, create: () -> Unit) {
    val live = rememberLive(vm, state, "/chats/stream")
    ConversationList(
        live.state?.chats.orEmpty(),
        null,
        live.state == null,
        live.error,
        open,
        create,
    )
}

@Composable
internal fun ConversationList(
    chats: List<Chat>,
    selected: String?,
    loading: Boolean,
    error: String?,
    open: (String) -> Unit,
    create: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val today =
        java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    val yesterday =
        java.time.LocalDate.now()
            .minusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    val filtered =
        chats
            .filter {
                "${it.title} ${it.agentName} ${it.projectName.orEmpty()}"
                    .contains(query.trim(), true)
            }
            .sortedByDescending { it.updatedAt }
    val groups =
        listOf(
            "Aujourd’hui" to filtered.filter { it.updatedAt >= today },
            "Hier" to filtered.filter { it.updatedAt in yesterday until today },
            "Plus tôt" to filtered.filter { it.updatedAt < yesterday },
        )
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Conversations", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            ActionIcon("Nouvelle conversation", Icons.Default.Add, onClick = create)
        }
        SearchField("Rechercher une conversation", query) { query = it }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!loading && filtered.isEmpty())
                item {
                    Empty(
                        if (query.isBlank()) "Aucune conversation" else "Aucun résultat",
                        if (query.isBlank()) "Confiez une mission à votre agent pour commencer."
                        else "Essayez un autre titre, agent ou projet.",
                    )
                }
            groups
                .filter { it.second.isNotEmpty() }
                .forEach { (label, items) ->
                    item(key = label) {
                        Text(
                            label,
                            Modifier.padding(top = 12.dp, bottom = 8.dp, start = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(items, key = { it.id }) { chat ->
                        Surface(
                            onClick = { open(chat.id) },
                            color =
                                if (chat.id == selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    chat.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(chat.agentName, chat.projectName)
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (chat.pendingQuestions > 0)
                                    Text(
                                        "${chat.pendingQuestions} question(s) en attente",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                else if (chat.paused)
                                    Text("En pause", style = MaterialTheme.typography.labelSmall)
                                else if (
                                    chat.status in
                                        listOf("running", "queued", "failed", "interrupted")
                                )
                                    Status(chat.status)
                            }
                        }
                    }
                }
        }
    }
}

@Composable
fun ChatScreen(
    vm: LeoViewModel,
    state: Workspace,
    id: String?,
    initialAgent: String = MAIN_AGENT_ID,
    initialProject: String = "",
    openChat: (String) -> Unit,
    openRun: (String) -> Unit,
    back: () -> Unit = {},
    create: () -> Unit = back,
) {
    val pageAnchor = remember(id) { HistoryPageAnchor() }
    val live =
        rememberLive(
            vm,
            state,
            if (id == null) "/chats/stream" else "/chats/${segment(id)}/stream",
            pageAnchor::beforeApply,
        )
    val chat = live.state?.chat?.takeIf { it.id == id }
    var createdId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var agent by rememberSaveable(id) { mutableStateOf(initialAgent) }
    var project by rememberSaveable(id) { mutableStateOf(initialProject) }
    val draftKey = id ?: "new:$initialAgent:$initialProject"
    val savedDraft = remember(draftKey) { vm.chatDrafts[draftKey] ?: ChatDraft() }
    var draft by rememberSaveable(id) { mutableStateOf(savedDraft.text) }
    var model by rememberSaveable(id) { mutableStateOf(savedDraft.model) }
    var reasoning by rememberSaveable(id) { mutableStateOf(savedDraft.reasoning) }
    var options by rememberSaveable(id) { mutableStateOf(false) }
    var attachments by rememberForm(savedDraft.attachments)
    var editing by rememberSaveable(id) { mutableStateOf(savedDraft.editing) }
    var submissionId by rememberSaveable(id) { mutableStateOf(savedDraft.submissionId) }
    var submissionKey by rememberSaveable(id) { mutableStateOf(savedDraft.submissionKey) }
    var follow by rememberSaveable(id) { mutableStateOf(true) }
    var gallery by rememberSaveable(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable(id) { mutableStateOf(false) }
    var queueExpanded by rememberSaveable(id) { mutableStateOf(true) }
    BackHandler(fullscreen) { fullscreen = false }
    var choosing by rememberSaveable(id) { mutableStateOf(false) }
    var details by rememberSaveable(id) { mutableStateOf(false) }
    var outgoing by remember(id) { mutableStateOf<ChatMessage?>(null) }
    fun persistDraft() {
        if (vm.state.value.session.authenticated && !vm.state.value.signingOut)
            vm.chatDrafts[draftKey] =
                ChatDraft(
                    draft,
                    model,
                    reasoning,
                    attachments,
                    editing,
                    submissionId,
                    submissionKey,
                )
    }
    SideEffect { persistDraft() }
    DisposableEffect(draftKey) { onDispose { persistDraft() } }
    LaunchedEffect(chat?.messages, live.events) {
        outgoing?.let { pending ->
            if (
                chat?.messages.orEmpty().any { it.id == pending.id && it.status == "delivered" } ||
                    live.events.any {
                        it.type == "chat.user" &&
                            it.payload?.get("messageId")?.jsonPrimitive?.contentOrNull == pending.id
                    }
            )
                outgoing = null
        }
    }
    val timeline =
        remember(live.events, live.state?.artifacts) {
            deliveryTimeline(
                timelineEntries(live.events, chat = true),
                live.state?.artifacts.orEmpty(),
            )
        }
    var asking by remember { mutableStateOf<ChatQuestion?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val rendering = remember(id) { MarkdownRendering() }
    val positionReady =
        rememberHistoryPosition(
            vm,
            state,
            if (id == null) "/chats/stream" else "/chats/${segment(id)}/stream",
            live,
            listState,
            !gallery,
            follow,
            rendering,
        ) {
            follow = it
        }
    val loadOlder =
        rememberHistoryPaging(
            live,
            listState,
            positionReady && !gallery,
            follow,
            timeline.map { it.key },
            rendering,
            pageAnchor,
        ) {
            follow = false
        }
    val active = chat?.run?.active == true
    val selectedAgent = state.agents.find { it.id == (chat?.agentId ?: agent) }
    val projects =
        state.projects.filter {
            selectedAgent?.access?.projects == null ||
                it.id in selectedAgent.access.projects.orEmpty()
        }
    val delivery = chatDelivery(chat, live.events, outgoing)
    val pending = delivery.queued
    val questions = chat?.questions.orEmpty().filter { it.status == "pending" }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty())
                vm.perform {
                    require(attachments.size + uris.size <= 8) {
                        "Vous pouvez joindre jusqu’à 8 fichiers."
                    }
                    val staged = mutableListOf<DraftAttachment>()
                    try {
                        uris.forEach { staged.add(files.stage(it)) }
                        require(
                            (attachments + staged).sumOf { it.attachment.size } <= 40L * 1024 * 1024
                        ) {
                            "Les pièces jointes doivent totaliser au maximum 40 Mo."
                        }
                        attachments = attachments + staged
                    } catch (e: Exception) {
                        files.discard(staged)
                        throw e
                    }
                }
        }
    fun clearDraft() {
        vm.files.discard(attachments)
        attachments = emptyList()
        draft = ""
        editing = null
        submissionKey = ""
        submissionId = ""
    }
    fun send(mode: String) {
        vm.perform {
            val chatId =
                id
                    ?: createdId
                    ?: api.send<Chat>(
                            "POST",
                            "/chats",
                            buildJsonObject {
                                put("agentId", agent)
                                put("projectId", project.ifEmpty { null })
                            },
                        )
                        .id
                        .also { createdId = it }
            for (entry in attachments.toList()) if (
                entry.localPath != null && entry.attachment.chatId != chatId
            ) {
                val uploaded =
                    api.upload(
                        "/chats/${segment(chatId)}/attachments/${segment(entry.attachment.id)}?name=${segment(entry.attachment.name)}",
                        File(entry.localPath),
                    )
                attachments =
                    attachments.map {
                        if (it.attachment.id == uploaded.id) it.copy(attachment = uploaded) else it
                    }
            }
            val content = buildJsonObject {
                put("text", draft.trim())
                put("mode", mode)
                put("model", model)
                put("reasoning", reasoning)
                put(
                    "attachmentIds",
                    wireJson.encodeToJsonElement(attachments.map { it.attachment.id }),
                )
            }
            val key = "$chatId/${editing.orEmpty()}/$content"
            if (key != submissionKey) {
                submissionKey = key
                submissionId = UUID.randomUUID().toString()
            }
            val payload = JsonObject(content + ("id" to JsonPrimitive(editing ?: submissionId)))
            if (editing == null)
                outgoing =
                    ChatMessage(
                        submissionId,
                        chatId,
                        draft.trim(),
                        model,
                        reasoning,
                        mode,
                        "sending",
                        System.currentTimeMillis(),
                        attachments.map { it.attachment },
                    )
            try {
                api.request(
                    if (editing == null) "POST" else "PUT",
                    "/chats/${segment(chatId)}/messages" +
                        (editing?.let { "/${segment(it)}" } ?: ""),
                    payload,
                )
            } catch (e: Exception) {
                outgoing = null
                throw e
            }
            clearDraft()
            if (id == null) openChat(chatId)
        }
    }
    fun edit(message: ChatMessage) {
        if (draft.isNotBlank() || attachments.isNotEmpty()) {
            vm.report(
                IllegalStateException(
                    "Envoyez ou effacez votre brouillon avant de modifier la file d’attente."
                )
            )
            return
        }
        editing = message.id
        draft = message.text
        model = message.model
        reasoning = message.reasoning
        attachments = message.attachments.map { DraftAttachment(it) }
    }
    val followGesture = rememberHistoryFollowGesture(listState) { follow = it }
    FollowHistoryTail(
        listState,
        positionReady && follow && !gallery && !live.catchingUp,
        live.cursor,
        rendering,
        followGesture,
    )
    ArtifactLinkHost(vm, live.state?.artifacts.orEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen)
                ConversationHeader(
                    "Conversations",
                    chat?.title ?: "Nouvelle conversation",
                    {
                        persistDraft()
                        choosing = true
                    },
                ) {
                    ActionIcon("Nouvelle conversation", Icons.Default.Add) {
                        persistDraft()
                        create()
                    }
                    Box {
                        ActionIcon("Options de la conversation", Icons.Default.MoreVert) {
                            menu = true
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Plein écran") },
                                onClick = {
                                    menu = false
                                    fullscreen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Détails de la conversation") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = {
                                    menu = false
                                    details = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Fichiers · ${live.state?.artifacts?.size ?: 0}") },
                                leadingIcon = { Icon(LeoIcons.Layers, null) },
                                onClick = {
                                    menu = false
                                    gallery = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Modèle et préférences") },
                                leadingIcon = { Icon(LeoIcons.Tune, null) },
                                onClick = {
                                    menu = false
                                    options = true
                                },
                            )
                            chat?.let { current ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (current.paused) "Reprendre" else "Mettre en pause")
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (current.paused) Icons.Default.PlayArrow
                                            else LeoIcons.Pause,
                                            null,
                                        )
                                    },
                                    enabled = !state.busy,
                                    onClick = {
                                        menu = false
                                        vm.perform {
                                            api.request(
                                                "POST",
                                                "/chats/${segment(current.id)}/pause",
                                                buildJsonObject { put("paused", !current.paused) },
                                            )
                                        }
                                    },
                                )
                                if (
                                    active &&
                                        editing == null &&
                                        (draft.isNotBlank() || attachments.isNotEmpty())
                                )
                                    DropdownMenuItem(
                                        text = { Text("Intervenir avec ce message") },
                                        enabled = !state.busy && live.error == null,
                                        onClick = {
                                            menu = false
                                            send("steer")
                                        },
                                    )
                                current.runId?.let { runId ->
                                    DropdownMenuItem(
                                        text = { Text("Voir l’exécution") },
                                        leadingIcon = { Icon(LeoIcons.Terminal, null) },
                                        onClick = {
                                            menu = false
                                            openRun(runId)
                                        },
                                    )
                                }
                                if (active)
                                    DropdownMenuItem(
                                        text = { Text("Arrêter l’agent") },
                                        leadingIcon = { Icon(LeoIcons.Stop, null) },
                                        enabled = !state.busy,
                                        onClick = {
                                            menu = false
                                            stopping = true
                                        },
                                    )
                            }
                        }
                    }
                }
            if (fullscreen)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Conversation",
                        Modifier.weight(1f).padding(start = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ActionIcon("Quitter le plein écran", Icons.Default.Close) { fullscreen = false }
                }
            if (choosing) {
                val conversations = rememberLive(vm, state, "/chats/stream")
                ModalBottomSheet(
                    onDismissRequest = { choosing = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Box(Modifier.fillMaxHeight(0.88f)) {
                        ConversationList(
                            conversations.state?.chats.orEmpty(),
                            id,
                            conversations.state == null,
                            conversations.error,
                            {
                                choosing = false
                                persistDraft()
                                openChat(it)
                            },
                            {
                                choosing = false
                                persistDraft()
                                create()
                            },
                        )
                    }
                }
            }
            if (details)
                DetailSheet("Détails de la conversation", { details = false }) {
                    Text(
                        chat?.title ?: "Nouvelle conversation",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(chat?.agentName ?: selectedAgent?.name.orEmpty())
                    Text(chat?.projectName ?: "Tous les projets autorisés")
                    Text(
                        if (chat?.paused == true) "En pause"
                        else if (active) "L’agent travaille…" else "Prêt"
                    )
                    Text(
                        "${live.state?.artifacts?.size ?: 0} fichiers · ${questions.size} questions en attente"
                    )
                    chat?.run?.let {
                        Text(
                            "Dernière activité : ${date(it.finishedAt ?: it.startedAt ?: it.createdAt)}"
                        )
                    }
                }
            if (live.status != "En direct")
                Text(
                    live.error ?: live.status,
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            chat?.error?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (gallery) {
                ModalBottomSheet(
                    onDismissRequest = { gallery = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Artifacts",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        ActionIcon("Fermer les artifacts", Icons.Default.Close) { gallery = false }
                    }
                    Box(Modifier.fillMaxHeight(0.85f)) {
                        Page { ArtifactsPanel(vm, live.state?.artifacts.orEmpty()) }
                    }
                }
            }
            if (options) {
                ModalBottomSheet(onDismissRequest = { options = false }) {
                    Column(
                        Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Préférences",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            ActionIcon("Fermer les préférences", Icons.Default.Close) {
                                options = false
                            }
                        }
                        ModelPicker(
                            state.models,
                            model,
                            reasoning,
                            selectedAgent?.model.orEmpty(),
                        ) { m, r ->
                            model = m
                            reasoning = r
                        }
                        Toggle("Suivre les réponses", follow) { follow = it }
                    }
                }
            }
            Column(
                Modifier.weight(1f)
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            ) {
                if (live.catchingUp && id != null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                } else
                    LazyColumn(
                        Modifier.weight(1f)
                            .testTag("conversation-history")
                            .historyFollowGesture(followGesture),
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (id == null)
                            item {
                                Panel {
                                    Choice("Agent", agent, state.agents.map { it.id to it.name }) {
                                        agent = it
                                        project = ""
                                    }
                                    Choice(
                                        "Projet",
                                        project,
                                        listOf("" to "Tous les projets autorisés") +
                                            projects.map { it.id to it.name },
                                    ) {
                                        project = it
                                    }
                                    Text("Envoyez un message ou un fichier pour commencer.")
                                }
                            }
                        historyHeader(live, loadOlder)
                        items(timeline, key = { it.key }) {
                            TimelineRow(vm, it, chat?.agentName ?: "Leo", rendering)
                        }
                        if (!active && delivery.sending.isEmpty() && !live.catchingUp)
                            chat?.run?.outcome?.let { outcome ->
                                item(key = "outcome:${outcome.messageId}:${outcome.reportedAt}") {
                                    CompletionEvidence(outcome, chat.agentName)
                                }
                            }
                        items(delivery.sending, key = { "sending:${it.message.id}" }) { sending ->
                            Column {
                                EventRow(
                                    vm,
                                    RunEvent(
                                        -1,
                                        sending.message.createdAt,
                                        "chat.user",
                                        sending.message.text,
                                        mapOf(
                                            "text" to JsonPrimitive(sending.message.text),
                                            "attachments" to
                                                wireJson.encodeToJsonElement(
                                                    sending.message.attachments
                                                ),
                                        ),
                                    ),
                                    chat?.agentName ?: "Leo",
                                )
                                Text(
                                    sending.label,
                                    Modifier.align(Alignment.End).padding(end = 16.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (active && !live.catchingUp)
                            item {
                                Text(
                                    "L’agent travaille…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        if (pending.isNotEmpty())
                            item(key = "queue-header") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { queueExpanded = !queueExpanded },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("${pending.size} message(s) en attente")
                                        Icon(
                                            if (queueExpanded) LeoIcons.Down else LeoIcons.Right,
                                            null,
                                            Modifier.size(16.dp),
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            vm.perform {
                                                api.request(
                                                    "POST",
                                                    "/chats/${segment(chat!!.id)}/pause",
                                                    buildJsonObject { put("paused", !chat.paused) },
                                                )
                                            }
                                        },
                                        enabled = !state.busy,
                                    ) {
                                        Text(if (chat?.paused == true) "Reprendre" else "Pause")
                                    }
                                }
                            }
                        if (queueExpanded)
                            items(pending, key = { "pending:${it.id}" }) { message ->
                                val private =
                                    chat?.questions.orEmpty().any {
                                        it.id == message.questionId &&
                                            it.fields.any { f -> f.secret }
                                    }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(
                                            if (message.status == "sending") "Envoi en cours…"
                                            else "En attente",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Text(if (private) "Réponse privée" else message.text)
                                        if (message.attachments.isNotEmpty())
                                            AttachmentList(vm, message.attachments)
                                        if (message.status == "queued")
                                            Row {
                                                if (message.questionId == null) {
                                                    ActionIcon(
                                                        "Modifier le message en attente",
                                                        Icons.Default.Edit,
                                                        onClick = { edit(message) },
                                                        enabled = !state.busy,
                                                    )
                                                    if (active && message.mode != "steer")
                                                        TextButton(
                                                            onClick = {
                                                                vm.perform {
                                                                    api.request(
                                                                        "PUT",
                                                                        "/chats/${segment(chat.id)}/messages/${segment(message.id)}",
                                                                        buildJsonObject {
                                                                            put("id", message.id)
                                                                            put(
                                                                                "text",
                                                                                message.text,
                                                                            )
                                                                            put("mode", "steer")
                                                                            put(
                                                                                "model",
                                                                                message.model,
                                                                            )
                                                                            put(
                                                                                "reasoning",
                                                                                message.reasoning,
                                                                            )
                                                                            put(
                                                                                "attachmentIds",
                                                                                wireJson
                                                                                    .encodeToJsonElement(
                                                                                        message
                                                                                            .attachments
                                                                                            .map {
                                                                                                it
                                                                                                    .id
                                                                                            }
                                                                                    ),
                                                                            )
                                                                        },
                                                                    )
                                                                }
                                                            },
                                                            enabled = !state.busy,
                                                        ) {
                                                            Text("Intervenir")
                                                        }
                                                }
                                                ActionIcon(
                                                    "Retirer le message",
                                                    Icons.Default.Delete,
                                                    onClick = { removing = message },
                                                    enabled = !state.busy,
                                                )
                                            }
                                    }
                                }
                            }
                    }
                if (!follow)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ActionIcon("Derniers messages", LeoIcons.Bottom) { follow = true }
                    }
                if (questions.isNotEmpty())
                    OutlinedButton(
                        onClick = { asking = questions.first() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(
                            "${questions.size} ${if (questions.size == 1) "question" else "questions"} · Répondre"
                        )
                    }
                if (!fullscreen)
                    Surface(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("conversation-composer"),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(4.dp)) {
                            if (attachments.isNotEmpty())
                                Box(Modifier.heightIn(max = 140.dp)) {
                                    LazyColumn {
                                        item {
                                            AttachmentList(
                                                vm,
                                                attachments.map { it.attachment },
                                                { key ->
                                                    vm.files.discard(
                                                        attachments.filter {
                                                            it.attachment.id == key
                                                        }
                                                    )
                                                    attachments =
                                                        attachments.filter {
                                                            it.attachment.id != key
                                                        }
                                                },
                                                attachments
                                                    .mapNotNull {
                                                        it.localPath?.let { path ->
                                                            it.attachment.id to path
                                                        }
                                                    }
                                                    .toMap(),
                                            )
                                        }
                                    }
                                }
                            if (editing != null)
                                Row {
                                    Text("Modifier le message en attente", Modifier.weight(1f))
                                    TextButton(onClick = ::clearDraft) { Text("Annuler") }
                                }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                ActionIcon(
                                    "Joindre",
                                    Icons.Default.Add,
                                    !state.busy && attachments.size < 8,
                                ) {
                                    picker.launch(arrayOf("*/*"))
                                }
                                BasicTextField(
                                    draft,
                                    { if (it.length <= 50000) draft = it },
                                    Modifier.weight(1f)
                                        .heightIn(min = 48.dp)
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    textStyle =
                                        MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                    cursorBrush =
                                        androidx.compose.ui.graphics.SolidColor(
                                            MaterialTheme.colorScheme.primary
                                        ),
                                    maxLines = 4,
                                    enabled = !state.busy,
                                    decorationBox = { inner ->
                                        Box {
                                            if (draft.isEmpty())
                                                Text(
                                                    if (chat?.paused == true) "Ajouter à la file…"
                                                    else if (active) "Ajouter un message…"
                                                    else "Votre message…",
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            inner()
                                        }
                                    },
                                )
                                val canSend =
                                    !state.busy &&
                                        (draft.isNotBlank() || attachments.isNotEmpty()) &&
                                        (id == null || chat != null) &&
                                        live.error == null
                                if (
                                    active &&
                                        editing == null &&
                                        (draft.isNotBlank() || attachments.isNotEmpty())
                                )
                                    ActionIcon("Intervenir maintenant", LeoIcons.Steer, canSend) {
                                        send("steer")
                                    }
                                if (active && draft.isBlank() && attachments.isEmpty())
                                    ActionIcon("Arrêter", LeoIcons.Stop, !state.busy) {
                                        stopping = true
                                    }
                                else
                                    FilledIconButton(
                                        onClick = { send("queue") },
                                        enabled = canSend,
                                        modifier = Modifier.size(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = androidx.compose.ui.graphics.Color(0xFF4545F5),
                                            contentColor = androidx.compose.ui.graphics.Color.White,
                                        ),
                                    ) {
                                        Icon(
                                            if (editing != null) Icons.Default.Check
                                            else if (active || chat?.paused == true)
                                                Icons.Default.Add
                                            else LeoIcons.Up,
                                            if (editing != null) "Modifier"
                                            else if (active || chat?.paused == true)
                                                "Ajouter à la file"
                                            else "Envoyer",
                                        )
                                    }
                            }
                        }
                    }
            }
        }
        asking?.let { selected ->
            val current = questions.find { it.id == selected.id }
            if (current == null) LaunchedEffect(selected.id) { asking = null }
            else QuestionDialog(vm, state, current, questions, { asking = it }) { asking = null }
        }
        if (stopping && chat != null)
            Confirm(
                "Arrêter l’agent ?",
                "La conversation sera mise en pause. Le travail déjà effectué sera conservé.",
                state.busy,
                state.error,
                { stopping = false },
            ) {
                vm.perform {
                    api.request("POST", "/chats/${segment(chat.id)}/stop")
                    stopping = false
                }
            }
        removing?.let { message ->
            Confirm(
                "Retirer ce message ?",
                "Il sera supprimé de la file d’attente.",
                state.busy,
                state.error,
                { removing = null },
            ) {
                vm.perform {
                    api.request(
                        "DELETE",
                        "/chats/${segment(message.chatId)}/messages/${segment(message.id)}",
                    )
                    removing = null
                }
            }
        }
    }
}

@Composable
private fun QuestionDialog(
    vm: LeoViewModel,
    state: Workspace,
    question: ChatQuestion,
    questions: List<ChatQuestion>,
    select: (ChatQuestion) -> Unit,
    close: () -> Unit,
) {
    // Private answers never enter SavedState or on-disk drafts.
    var answers by remember(question.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var submission by remember(question.id) { mutableStateOf("") }
    var submissionKey by remember(question.id) { mutableStateOf("") }
    Dialog(
        onDismissRequest = { if (!state.busy) close() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy =
                    if (question.fields.any { it.secret }) SecureFlagPolicy.SecureOn
                    else SecureFlagPolicy.Inherit,
            ),
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = { TopAppBar(title = { Text("Votre réponse") }) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    Page {
                        Text(
                            if (question.blocking) "L’agent attend votre réponse."
                            else "Répondez quand vous êtes prêt."
                        )
                        if (questions.size > 1)
                            Choice(
                                "Question",
                                question.id,
                                questions.map {
                                    it.id to it.fields.firstOrNull()?.title.orEmpty().take(100)
                                },
                            ) { key ->
                                select(questions.first { it.id == key })
                            }
                        question.fields.forEach { field ->
                            Text(field.title, style = MaterialTheme.typography.titleMedium)
                            field.options.forEach { option ->
                                OutlinedCard(
                                    onClick = { answers = answers + (field.id to option.label) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.padding(12.dp)) {
                                        RadioButton(
                                            answers[field.id] == option.label,
                                            { answers = answers + (field.id to option.label) },
                                        )
                                        Column {
                                            Text(option.label)
                                            if (option.description.isNotBlank())
                                                Text(
                                                    option.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                        }
                                    }
                                }
                            }
                            if (field.secret)
                                OutlinedTextField(
                                    answers[field.id].orEmpty(),
                                    { answers = answers + (field.id to it.take(10000)) },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("Réponse privée") },
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            else
                                Field(
                                    if (field.options.isEmpty()) "Réponse"
                                    else "Ou votre propre réponse",
                                    answers[field.id].orEmpty(),
                                    { answers = answers + (field.id to it.take(10000)) },
                                    2,
                                )
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = close, enabled = !state.busy) { Text("Plus tard") }
                    Button(
                        onClick = {
                            vm.perform {
                                val values =
                                    wireJson.encodeToJsonElement(
                                        answers.mapValues { listOf(it.value.trim()) }
                                    )
                                if (submissionKey != values.toString()) {
                                    submissionKey = values.toString()
                                    submission = UUID.randomUUID().toString()
                                }
                                api.request(
                                    "POST",
                                    "/chats/${segment(question.chatId)}/questions/${segment(question.id)}/answer",
                                    buildJsonObject {
                                        put("id", submission)
                                        put("answers", values)
                                    },
                                )
                                answers = emptyMap()
                                close()
                            }
                        },
                        enabled =
                            !state.busy && question.fields.all { !answers[it.id].isNullOrBlank() },
                    ) {
                        Text("Envoyer la réponse")
                    }
                }
            }
        }
    }
}
