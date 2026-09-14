@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.*
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RunsScreen(vm: LeoViewModel, state: Workspace, open: (String) -> Unit) {
    var runs by remember { mutableStateOf<List<Run>>(emptyList()) }
    var status by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var taskId by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var filters by rememberSaveable { mutableStateOf(false) }
    Poll("$status/$offset/$taskId", 10000) {
        try {
            runs =
                vm.api.get(
                    "/runs?limit=30&offset=$offset" +
                        (if (status.isEmpty()) "" else "&status=${segment(status)}") +
                        if (taskId.isEmpty()) "" else "&taskId=${segment(taskId)}"
                )
        } catch (e: Exception) {
            vm.report(e)
        } finally {
            loading = false
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Activité",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                ActionIcon("Filtres", LeoIcons.Tune) { filters = !filters }
            }
        }
        if (filters || status.isNotBlank() || taskId.isNotBlank())
            item {
                Choice(
                    "Statut",
                    status,
                    listOf(
                        "" to "Tous",
                        "running" to "En cours",
                        "queued" to "En attente",
                        "succeeded" to "Terminées",
                        "failed" to "Échecs",
                        "cancelled" to "Annulées",
                        "interrupted" to "Interrompues",
                    ),
                ) {
                    status = it
                    offset = 0
                    runs = emptyList()
                    loading = true
                }
            }
        if (filters || status.isNotBlank() || taskId.isNotBlank())
            item {
                Choice(
                    "Tâche",
                    taskId,
                    listOf("" to "Toutes les tâches") + state.tasks.map { it.id to it.name },
                ) {
                    taskId = it
                    offset = 0
                    runs = emptyList()
                    loading = true
                }
            }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (runs.isEmpty() && !loading)
            item {
                Empty(
                    "Aucune exécution",
                    "Les résultats correspondant à ces filtres apparaîtront ici.",
                )
            }
        items(runs, key = { it.id }) { RunCard(it, open) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = {
                        offset = (offset - 30).coerceAtLeast(0)
                        loading = true
                    },
                    enabled = offset > 0 && !loading,
                ) {
                    Text("Précédent")
                }
                OutlinedButton(
                    onClick = {
                        offset += 30
                        loading = true
                    },
                    enabled = runs.size == 30 && !loading,
                ) {
                    Text("Suivant")
                }
            }
        }
    }
}

@Composable
fun RunScreen(
    vm: LeoViewModel,
    state: Workspace,
    id: String,
    openChat: (String) -> Unit = {},
    back: () -> Unit = {},
    openRun: (String) -> Unit,
) {
    val live = rememberLive(vm, state, "/runs/${segment(id)}/stream")
    val run = live.state?.run
    val events = live.events
    val more = live.catchingUp
    val timeline =
        remember(events, live.state?.artifacts) {
            deliveryTimeline(timelineEntries(events), live.state?.artifacts.orEmpty())
        }
    var menu by remember { mutableStateOf(false) }
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }
    var autoTab by rememberSaveable(id) { mutableStateOf(true) }
    var follow by rememberSaveable(id) { mutableStateOf(true) }
    var confirm by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val logState = rememberLazyListState()
    val positionReady =
        rememberHistoryPosition(
            vm,
            state,
            "/runs/${segment(id)}/stream",
            live,
            logState,
            tab == 1,
            follow,
        ) {
            follow = it
        }
    LaunchedEffect(run?.id, live.status, live.catchingUp) {
        if (autoTab && run != null && live.status == "En direct" && !live.catchingUp) {
            autoTab = false
            if (run.active) tab = 1
        }
    }
    LaunchedEffect(logState) {
        logState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(events.lastOrNull(), follow, tab, more, positionReady) {
        if (positionReady && follow && tab == 1 && !more)
            snapshotFlow {
                logState.layoutInfo.totalItemsCount to logState.layoutInfo.viewportSize.height
            }
                .collectLatest { (count, _) ->
                    if (count > 0) logState.scrollToItem(count - 1)
                }
    }
    ArtifactLinkHost(vm, live.state?.artifacts.orEmpty()) {
        Column(Modifier.fillMaxSize()) {
            live.error?.let { ErrorNotice(it) {} }
            run?.let { current ->
                DetailHeader(
                    current.title,
                    "${statusLabel(current.status)} · ${duration(current)}",
                    back,
                ) {
                    if (live.state.artifacts.isNotEmpty())
                        ActionIcon("Fichiers", LeoIcons.Layers) {
                            autoTab = false
                            tab = 2
                        }
                    Box {
                        ActionIcon("Options de l’exécution", Icons.Default.MoreVert) { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Mission et détails") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = {
                                    menu = false
                                    autoTab = false
                                    tab = 3
                                },
                            )
                            if (current.resumeAvailable && current.trigger != "chat")
                                DropdownMenuItem(
                                    text = { Text("Reprendre") },
                                    enabled = !state.busy,
                                    onClick = {
                                        menu = false
                                        vm.perform { api.request("POST", "/runs/$id/resume") }
                                    },
                                )
                            if (current.active)
                                DropdownMenuItem(
                                    text = { Text("Arrêter") },
                                    leadingIcon = { Icon(LeoIcons.Stop, null) },
                                    enabled = !state.busy,
                                    onClick = {
                                        menu = false
                                        vm.clearMessage()
                                        confirm = "cancel"
                                    },
                                )
                            else if (current.trigger != "chat")
                                DropdownMenuItem(
                                    text = { Text("Relancer") },
                                    enabled = !state.busy,
                                    onClick = {
                                        menu = false
                                        vm.perform {
                                            openRun(api.send<Run>("POST", "/runs/$id/retry").id)
                                        }
                                    },
                                )
                            if (current.trigger == "chat")
                                DropdownMenuItem(
                                    text = { Text("Ouvrir le chat") },
                                    leadingIcon = { Icon(LeoIcons.Chat, null) },
                                    onClick = {
                                        menu = false
                                        openChat(current.taskId)
                                    },
                                )
                        }
                    }
                }
                current.accountWaitReason?.let {
                    Text(
                        "En attente : $it",
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (current.recoveryPending)
                    Text(
                        "Récupération en cours…",
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (current.cancelRequestedAt != null && current.active)
                    Text(
                        "Arrêt demandé…",
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (tab < 2)
                    SecondaryTabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        listOf("Résultat", "Activité").forEachIndexed { index, title ->
                            Tab(
                                tab == index,
                                {
                                    autoTab = false
                                    tab = index
                                },
                                text = { Text(title) },
                            )
                        }
                    }
                else
                    Row(
                        Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionIcon(
                            "Retour à l’activité",
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        ) {
                            tab = 1
                        }
                        Text(
                            if (tab == 2) "Artifacts" else "Mission et détails",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                when (tab) {
                    0 ->
                        Page {
                            if (current.summary.isBlank())
                                Empty(
                                    if (current.active) "Le travail est en cours"
                                    else "Aucun résumé",
                                    "Consultez l’activité pour les détails.",
                                )
                            else {
                                Markdown(current.summary)
                                ShareButton(current.summary)
                            }
                            if (live.state.artifacts.isNotEmpty())
                                ArtifactStrip(vm, latestArtifacts(live.state.artifacts))
                        }
                    1 -> {
                        if (live.status != "En direct")
                            Text(
                                live.status,
                                Modifier.padding(horizontal = 20.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (more) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(
                            Modifier.weight(1f),
                            state = logState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(timeline, key = { it.key }) { entry ->
                                TimelineRow(
                                    vm,
                                    entry,
                                    current.snapshot.agent.name.ifBlank { "Leo" },
                                )
                            }
                            if (events.isEmpty()) item { Text("L’activité apparaîtra ici.") }
                        }
                        if (!follow)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                ActionIcon("Dernière activité", LeoIcons.Bottom) { follow = true }
                            }
                    }
                    2 -> Page { ArtifactsPanel(vm, live.state.artifacts) }
                    3 ->
                        Page {
                            Text(
                                current.snapshot.agent.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${current.snapshot.project?.name ?: current.snapshot.projects.joinToString { it.name }.ifBlank { "Projets autorisés" }} · ${current.snapshot.agent.model.ifBlank { "Modèle par défaut" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            current.codexAccountName?.let {
                                Text("Compte : $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Heading("Instructions d’origine")
                            Markdown(current.snapshot.task.prompt)
                            Panel {
                                Text(
                                    "Espace de travail",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                current.workspaces.forEach { workspace ->
                                    Text(
                                        current.snapshot.projects
                                            .find { it.id == workspace.projectId }
                                            ?.name ?: workspace.projectId
                                    )
                                    Code("${workspace.kind} · ${workspace.path}")
                                }
                                Code(
                                    current.workspace
                                        ?: if (current.workspaceCleanedAt != null)
                                            "Worktree nettoyé"
                                        else "Pas encore préparé"
                                )
                                if (
                                    !current.active &&
                                        (current.workspace != null ||
                                            current.workspaces.isNotEmpty()) &&
                                        (current.isolated || current.snapshot.task.worktree)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            vm.clearMessage()
                                            confirm = "cleanup"
                                        },
                                        enabled = !state.busy,
                                    ) {
                                        Text("Nettoyer le worktree")
                                    }
                                }
                                Text("Déclenchement : ${current.trigger}")
                                Text(
                                    "Créée : ${date(current.createdAt)}\nDébut : ${date(current.startedAt)}\nFin : ${date(current.finishedAt)}"
                                )
                                Text(
                                    "Accès : ${current.snapshot.agent.access.sandbox} · ${current.snapshot.agent.timeoutMinutes} minutes maximum"
                                )
                                Text(
                                    "Skills : ${current.snapshot.skills.joinToString { it.name }.ifEmpty { "Aucun" }}"
                                )
                                current.usage?.forEach { (key, value) -> Text("$key : $value") }
                                Code(
                                    "Exécution : ${current.id}\nSession : ${current.sessionId ?: "—"}"
                                )
                            }
                        }
                }
            }
                ?: Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
        }
        confirm?.let { action ->
            Confirm(
                if (action == "cancel") "Arrêter cette exécution ?" else "Nettoyer ce worktree ?",
                if (action == "cancel")
                    "Le processus s’arrêtera. Les fichiers et les effets déjà produits resteront en place."
                else
                    "Le répertoire sera supprimé. La branche Git et l’historique restent disponibles. Le serveur refuse de supprimer un worktree contenant des changements.",
                state.busy,
                state.error,
                { confirm = null },
            ) {
                vm.perform {
                    api.request("POST", "/runs/$id/$action")
                    confirm = null
                }
            }
        }
    }
}
