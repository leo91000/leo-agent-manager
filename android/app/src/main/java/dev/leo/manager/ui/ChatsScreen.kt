@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.DragInteraction
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
    var query by rememberSaveable { mutableStateOf("") }
    val chats =
        live.state
            ?.chats
            .orEmpty()
            .filter {
                "${it.title} ${it.agentName} ${it.projectName.orEmpty()}".contains(query, true)
            }
            .sortedByDescending { it.updatedAt }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Conversations",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                ActionIcon("Nouvelle conversation", Icons.Default.Edit, onClick = create)
            }
        }
        item { SearchField("Rechercher une conversation", query, { query = it }) }
        if (live.status != "En direct")
            item { Text(live.error ?: live.status, style = MaterialTheme.typography.bodySmall) }
        if (chats.isEmpty() && live.state != null)
            item {
                Empty("Aucune conversation", "Confiez une mission à votre agent pour commencer.")
            }
        items(chats, key = { it.id }) { chat ->
            Card(onClick = { open(chat.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${chat.agentName} · ${chat.projectName ?: "Projets autorisés"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (chat.pendingQuestions > 0)
                        Text(
                            "${chat.pendingQuestions} question(s) en attente",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    if (chat.paused) Text("En pause")
                    else if (chat.status in listOf("running", "queued")) Status(chat.status)
                    Text(date(chat.updatedAt), style = MaterialTheme.typography.labelSmall)
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
) {
    val live =
        rememberLive(vm, state, if (id == null) "/chats/stream" else "/chats/${segment(id)}/stream")
    val chat = live.state?.chat?.takeIf { it.id == id }
    var createdId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var agent by rememberSaveable(id) { mutableStateOf(initialAgent) }
    var project by rememberSaveable(id) { mutableStateOf(initialProject) }
    var draft by rememberSaveable(id) { mutableStateOf("") }
    var model by rememberSaveable(id) { mutableStateOf("") }
    var reasoning by rememberSaveable(id) { mutableStateOf("") }
    var options by rememberSaveable(id) { mutableStateOf(false) }
    var attachments by rememberForm(emptyList<DraftAttachment>())
    var editing by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var submissionId by rememberSaveable(id) { mutableStateOf("") }
    var submissionKey by rememberSaveable(id) { mutableStateOf("") }
    var follow by rememberSaveable(id) { mutableStateOf(true) }
    var gallery by rememberSaveable(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val timeline =
        remember(live.events, live.state?.artifacts) {
            deliveryTimeline(timelineEntries(live.events), live.state?.artifacts.orEmpty())
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
    val loadOlder = rememberHistoryPaging(live, listState, positionReady && !gallery, follow, timeline.map { it.key }) { follow = false }
    val active = chat?.run?.active == true
    val selectedAgent = state.agents.find { it.id == (chat?.agentId ?: agent) }
    val projects =
        state.projects.filter {
            selectedAgent?.access?.projects == null ||
                it.id in selectedAgent.access.projects.orEmpty()
        }
    val pending = chat?.messages.orEmpty().filter { it.status != "delivered" }
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
                attachments = attachments.map {
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
            api.request(
                if (editing == null) "POST" else "PUT",
                "/chats/${segment(chatId)}/messages" + (editing?.let { "/${segment(it)}" } ?: ""),
                payload,
            )
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
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow = false
        }
    }
    FollowHistoryTail(listState, positionReady && follow && !gallery && !live.catchingUp, live.cursor, rendering)
    ArtifactLinkHost(vm, live.state?.artifacts.orEmpty()) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                chat?.title?.ifBlank { chat.agentName } ?: "Nouvelle conversation",
                if (chat?.paused == true) "En pause"
                else chat?.projectName.orEmpty().ifBlank { chat?.agentName.orEmpty() },
                back,
            ) {
                if (live.state?.artifacts?.isNotEmpty() == true)
                    ActionIcon("Artifacts", LeoIcons.Layers) { gallery = true }
                Box {
                    ActionIcon("Options de la conversation", Icons.Default.MoreVert) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
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
            Column(Modifier.weight(1f)) {
                if (live.catchingUp && id != null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                } else
                    LazyColumn(
                        Modifier.weight(1f),
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
                        if (active && !live.catchingUp)
                            item {
                                Text(
                                    "L’agent travaille…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        items(pending, key = { "pending:${it.id}" }) { message ->
                            val private =
                                chat?.questions.orEmpty().any {
                                    it.id == message.questionId && it.fields.any { f -> f.secret }
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
                                                                        put("text", message.text)
                                                                        put("mode", "steer")
                                                                        put("model", message.model)
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
                                                                                            it.id
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
                Surface(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(6.dp)) {
                        if (attachments.isNotEmpty())
                            Box(Modifier.heightIn(max = 140.dp)) {
                                LazyColumn {
                                    item {
                                        AttachmentList(
                                            vm,
                                            attachments.map { it.attachment },
                                            { key ->
                                                vm.files.discard(
                                                    attachments.filter { it.attachment.id == key }
                                                )
                                                attachments = attachments.filter {
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
                                                "Votre message…",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            if (active && draft.isBlank() && attachments.isEmpty())
                                ActionIcon("Arrêter", LeoIcons.Stop, !state.busy) {
                                    stopping = true
                                }
                            else
                                FilledIconButton(
                                    onClick = { send("queue") },
                                    enabled = canSend,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        if (editing != null) Icons.Default.Check else LeoIcons.Up,
                                        if (editing != null) "Modifier" else "Envoyer",
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
