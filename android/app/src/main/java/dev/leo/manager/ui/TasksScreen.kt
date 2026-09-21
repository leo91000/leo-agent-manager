@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.*
import kotlinx.serialization.json.encodeToJsonElement

internal fun taskGroup(task: Task, run: Run?): String =
    when {
        run?.active == true -> "En cours"
        run?.status in listOf("failed", "interrupted") ||
            (run?.status == "succeeded" &&
                run.outcome?.status?.let { it != "completed" } == true) -> "À examiner"
        run == null -> "Prêtes"
        else -> "Terminées"
    }

@Composable
fun TasksScreen(vm: LeoViewModel, state: Workspace, openRun: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var activity by remember { mutableStateOf<List<Run>>(emptyList()) }
    var loading by remember { mutableStateOf(state.session.authenticated) }
    if (state.session.authenticated)
        Poll("task-activity", 5000) {
            try {
                activity = vm.api.get("/tasks/activity")
                vm.refresh()
            } catch (e: Exception) {
                vm.report(e)
            } finally {
                loading = false
            }
        }
    val tasks =
        state.tasks.filter {
            "${it.name} ${it.tags.joinToString(" ")}".contains(query, true) &&
                (if (filter == "archived") it.archived else !it.archived) &&
                when (filter) {
                    "scheduled" -> it.cron != null && it.enabled
                    "paused" -> !it.enabled
                    "once" -> it.cron == null
                    else -> true
                }
        }
    val latest = activity.associateBy { it.taskId }
    val selected =
        tasks.find { it.id == selectedId }
            ?: tasks.find { latest[it.id]?.active == true }
            ?: tasks.firstOrNull()
    val run = selected?.let { latest[it.id] }
    fun choose(task: Task) {
        selectedId = task.id
        choosing = false
        details = false
    }
    fun create() {
        vm.clearMessage()
        editing = ""
        choosing = false
    }
    fun launch(task: Task) {
        vm.perform {
            val started = api.send<Run>("POST", "/tasks/${segment(task.id)}/run")
            activity = listOf(started) + activity.filter { it.taskId != task.id }
            selectedId = task.id
            details = false
        }
    }
    @Composable
    fun Inbox() {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tâches", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(
                    "Créer une tâche",
                    Icons.Default.Add,
                    state.agents.isNotEmpty(),
                    ::create,
                )
            }
            SearchField("Rechercher une tâche", query) { query = it }
            Choice(
                "Afficher",
                filter,
                listOf(
                    "all" to "Toutes",
                    "scheduled" to "Planifiées",
                    "once" to "Ponctuelles",
                    "paused" to "En pause",
                    "archived" to "Archivées",
                ),
            ) {
                filter = it
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (tasks.isEmpty())
                    item {
                        Empty(
                            "Aucune tâche dans cette vue",
                            "Créez une mission ou modifiez les filtres.",
                        )
                    }
                listOf("En cours", "À examiner", "Prêtes", "Terminées").forEach { group ->
                    val rows = tasks.filter { taskGroup(it, latest[it.id]) == group }
                    if (rows.isNotEmpty()) {
                        item(key = group) {
                            Text(
                                group,
                                Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(rows, key = { it.id }) { task ->
                            Surface(
                                onClick = { choose(task) },
                                color =
                                    if (task.id == selected?.id)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        task.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        if (task.archived) "Archivée"
                                        else if (!task.enabled) "En pause"
                                        else if (task.nextRun != null)
                                            "Prochaine : ${date(task.nextRun)}"
                                        else "Ponctuelle",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    latest[task.id]?.let { Status(it.status) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Box(Modifier.width(280.dp).fillMaxHeight()) { Inbox() }
                VerticalDivider()
            }
            Box(Modifier.weight(1f)) {
                when {
                    run != null ->
                        key(run.id) {
                            RunScreen(
                                vm,
                                state,
                                run.id,
                                back = { choosing = true },
                                chooseTask = { choosing = true },
                                taskDetails = { details = true },
                                createTask = ::create,
                                openRun = openRun,
                            )
                        }
                    else ->
                        Column(Modifier.fillMaxSize()) {
                            ConversationHeader(
                                "Tâches",
                                selected?.name.orEmpty(),
                                { choosing = true },
                            ) {
                                ActionIcon(
                                    "Créer une tâche",
                                    Icons.Default.Add,
                                    state.agents.isNotEmpty(),
                                    ::create,
                                )
                                if (selected != null)
                                    ActionIcon("Détails de la tâche", Icons.Default.MoreVert) {
                                        details = true
                                    }
                            }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Page {
                                selected?.let { task ->
                                    Text(task.name, style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "La conversation apparaîtra ici après la première exécution.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = { launch(task) },
                                        enabled = !state.busy && !task.archived,
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text("Lancer")
                                    }
                                    HorizontalDivider()
                                    Text("Mission", style = MaterialTheme.typography.titleMedium)
                                    Markdown(task.prompt)
                                }
                                    ?: Empty(
                                        "Vos tâches, au même endroit",
                                        "Créez une mission pour démarrer.",
                                    )
                            }
                        }
                }
            }
        }
        if (choosing && !wide)
            ModalBottomSheet(
                onDismissRequest = { choosing = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Box(Modifier.fillMaxHeight(0.9f)) { Inbox() }
            }
    }
    if (details && selected != null)
        DetailSheet("Détails de la tâche", { details = false }) {
            val task = selected
            Text(task.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${state.agents.find { it.id == task.agentId }?.name ?: "Agent supprimé"} · ${state.projects.find { it.id == task.projectId }?.name ?: "Tous les projets autorisés"}"
            )
            Text(
                if (task.archived) "Archivée"
                else if (!task.enabled) "En pause"
                else if (task.cron == null) "Ponctuelle" else "${task.cron} · ${task.timezone}"
            )
            task.nextRun
                ?.takeIf { task.enabled && !task.archived }
                ?.let { Text("Prochaine exécution : ${date(it)}") }
            if (task.tags.isNotEmpty()) Text(task.tags.joinToString(" · "))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launch(task) }, enabled = !state.busy && !task.archived) {
                    Text("Lancer")
                }
                OutlinedButton(
                    onClick = {
                        details = false
                        editing = task.id
                    }
                ) {
                    Text("Modifier")
                }
            }
            if (!task.archived)
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        vm.perform {
                            save(
                                "tasks",
                                task.id,
                                wireJson.encodeToJsonElement(task.copy(enabled = !task.enabled)),
                            )
                        }
                    },
                ) {
                    Text(if (task.enabled) "Mettre en pause" else "Reprendre la planification")
                }
            TextButton(
                enabled = !state.busy,
                onClick = {
                    vm.perform {
                        save(
                            "tasks",
                            task.id,
                            wireJson.encodeToJsonElement(
                                task.copy(archived = !task.archived, enabled = false)
                            ),
                        )
                        details = false
                    }
                },
            ) {
                Text(if (task.archived) "Restaurer (en pause)" else "Archiver")
            }
            TextButton(
                enabled = !state.busy,
                onClick = {
                    vm.perform {
                        save(
                            "tasks",
                            "",
                            wireJson.encodeToJsonElement(
                                task.copy(
                                    id = "",
                                    name = task.name.take(92) + " (copie)",
                                    enabled = false,
                                    archived = false,
                                )
                            ),
                        )
                        details = false
                    }
                },
            ) {
                Text("Dupliquer (en pause)")
            }
            if (run != null)
                TextButton(
                    onClick = {
                        details = false
                        openRun(run.id)
                    }
                ) {
                    Text("Ouvrir l’exécution")
                }
            TextButton(
                onClick = {
                    details = false
                    deleting = task.id
                }
            ) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            Text("Mission", style = MaterialTheme.typography.titleMedium)
            Markdown(task.prompt)
        }
    editing?.let { id ->
        TaskEditor(
            vm,
            state,
            state.tasks.find { it.id == id }
                ?: Task(
                    agentId = state.agents.firstOrNull()?.id.orEmpty(),
                    timezone = java.time.ZoneId.systemDefault().id,
                ),
            onSaved = { task ->
                query = ""
                filter = if (task.archived) "archived" else "all"
                choose(task)
            },
        ) {
            editing = null
        }
    }
    deleting?.let { id ->
        Confirm(
            "Supprimer cette tâche ?",
            "La planification sera supprimée. Les exécutions restent dans l’historique.",
            state.busy,
            state.error,
            { deleting = null },
        ) {
            vm.perform {
                delete("/tasks/$id")
                deleting = null
            }
        }
    }
}

@Composable
internal fun TaskEditor(
    vm: LeoViewModel,
    state: Workspace,
    initial: Task,
    onSaved: (Task) -> Unit = {},
    close: () -> Unit,
) {
    var form by rememberForm(initial)
    var cadence by rememberSaveable {
        mutableStateOf(if (initial.cron == null) "once" else "custom")
    }
    var tags by rememberSaveable { mutableStateOf(initial.tags.joinToString(", ")) }
    var occurrences by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(form.cron, form.timezone) { occurrences = emptyList() }
    val access = state.agents.find { it.id == form.agentId }?.access ?: AccessPolicy()
    val projects = state.projects.filter { access.projects == null || it.id in access.projects }
    val available =
        state.skills.filter {
            it.valid &&
                (access.skills == null || "${it.scope}/${it.name}" in access.skills) &&
                (it.scope == "global" || projects.any { project -> project.id == it.scope }) &&
                (form.projectId == null || it.scope == "global" || it.scope == form.projectId)
        }
    Editor(
        if (initial.id.isEmpty()) "Créer une tâche" else "Modifier la tâche",
        state.busy,
        state.error,
        close,
        save = {
            vm.perform {
                val saved =
                    api.send<Task>(
                        if (initial.id.isBlank()) "POST" else "PUT",
                        "/tasks" + if (initial.id.isBlank()) "" else "/${segment(initial.id)}",
                        wireJson.encodeToJsonElement(
                            form.copy(
                                tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            )
                        ),
                    )
                refresh()
                onSaved(saved)
                close()
            }
        },
        valid =
            form.name.isNotBlank() &&
                form.name.length <= 100 &&
                form.prompt.isNotBlank() &&
                form.prompt.length <= 50000 &&
                form.agentId.isNotBlank() &&
                (form.cron == null || !form.cron.isNullOrBlank()),
    ) {
        Field("Nom", form.name, { form = form.copy(name = it) })
        Field("Mission et critères de réussite", form.prompt, { form = form.copy(prompt = it) }, 6)
        Choice("Agent", form.agentId, state.agents.map { it.id to it.name }) {
            val nextAccess = state.agents.find { agent -> agent.id == it }?.access ?: AccessPolicy()
            form =
                form.copy(
                    agentId = it,
                    projectId =
                        form.projectId?.takeIf { project ->
                            nextAccess.projects == null || project in nextAccess.projects
                        },
                    skills =
                        form.skills?.filter { key ->
                            nextAccess.skills == null || key in nextAccess.skills
                        },
                )
        }
        Choice(
            "Projet",
            form.projectId.orEmpty(),
            listOf("" to "Tous les projets autorisés") + projects.map { it.id to it.name },
        ) { id ->
            val keys =
                state.skills
                    .filter { it.valid && (id.isEmpty() || it.scope == "global" || it.scope == id) }
                    .map { "${it.scope}/${it.name}" }
            form =
                form.copy(
                    projectId = id.ifEmpty { null },
                    skills = form.skills?.filter { it in keys },
                )
        }
        Toggle("Utiliser un worktree isolé", form.worktree) { form = form.copy(worktree = it) }
        Choice(
            "Fréquence",
            cadence,
            listOf(
                "once" to "Ponctuelle",
                "daily" to "Chaque jour à 9 h",
                "weekly" to "Chaque lundi à 9 h",
                "custom" to "Cron personnalisé",
            ),
        ) {
            cadence = it
            form =
                form.copy(
                    cron =
                        when (it) {
                            "once" -> null
                            "daily" -> "0 9 * * *"
                            "weekly" -> "0 9 * * 1"
                            else -> form.cron ?: "0 9 * * 1"
                        }
                )
        }
        if (cadence != "once") {
            Field(
                "Expression cron",
                form.cron.orEmpty(),
                {
                    form = form.copy(cron = it)
                    cadence = "custom"
                },
            )
            Field("Fuseau horaire", form.timezone, { form = form.copy(timezone = it) })
            OutlinedButton(
                onClick = {
                    vm.perform {
                        occurrences =
                            api.send<Occurrences>(
                                    "POST",
                                    "/schedule/preview",
                                    body("cron" to form.cron.orEmpty(), "timezone" to form.timezone),
                                )
                                .occurrences
                    }
                },
                enabled = !state.busy,
            ) {
                Text("Prévisualiser les prochaines dates")
            }
            occurrences.forEach { Text(date(it)) }
        }
        Toggle("Planification active", form.enabled) { form = form.copy(enabled = it) }
        Field("Étiquettes séparées par des virgules", tags, { tags = it })
        Text("Skills", style = MaterialTheme.typography.titleMedium)
        Toggle("Tous les skills autorisés", form.skills == null) {
            form = form.copy(skills = if (it) null else emptyList())
        }
        if (form.skills != null)
            available.forEach { skill ->
                val key = "${skill.scope}/${skill.name}"
                Toggle(
                    "${skill.name} · ${if (skill.scope == "global") "Global" else "Projet"}",
                    key in form.skills.orEmpty(),
                ) { checked ->
                    form =
                        form.copy(
                            skills =
                                if (checked) form.skills.orEmpty() + key
                                else form.skills.orEmpty() - key
                        )
                }
            }
        if (available.isEmpty()) Text("Aucun skill disponible pour ce projet.")
    }
}
