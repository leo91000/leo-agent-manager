package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.*
import kotlinx.serialization.json.encodeToJsonElement

@Composable
fun TasksScreen(vm: LeoViewModel, state: Workspace, openRun: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var activity by remember { mutableStateOf<List<Run>>(emptyList()) }
    if (state.session.authenticated)
        Poll("task-activity", 5000) {
            try {
                activity = vm.api.get("/tasks/activity")
            } catch (e: Exception) {
                vm.report(e)
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Heading("Vos tâches") }
        item {
            Button(
                onClick = {
                    vm.clearMessage()
                    editing = ""
                },
                enabled = state.agents.isNotEmpty(),
            ) {
                Text("Créer une tâche")
            }
        }
        if (state.agents.isEmpty()) item { Text("Ajoutez d’abord un agent dans Espace.") }
        item { SearchField("Rechercher par nom ou étiquette", query, { query = it }) }
        item {
            Choice(
                "Afficher",
                filter,
                listOf(
                    "all" to "Toutes",
                    "scheduled" to "Planifiées",
                    "paused" to "En pause",
                    "once" to "Ponctuelles",
                    "archived" to "Archivées",
                ),
            ) {
                filter = it
            }
        }
        if (tasks.isEmpty())
            item {
                Empty("Aucune tâche dans cette vue", "Créez une mission ou modifiez les filtres.")
            }
        items(tasks, key = { it.id }) { task ->
            Panel {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${state.agents.find { it.id == task.agentId }?.name ?: "Agent supprimé"} · ${if (task.projectId == null) "Tous les projets autorisés" else state.projects.find { it.id == task.projectId }?.name ?: "Projet supprimé"}"
                )
                Text(
                    if (task.archived) "Archivée"
                    else if (!task.enabled) "En pause"
                    else if (task.cron == null) "Ponctuelle" else "${task.cron} · ${task.timezone}",
                    color = MaterialTheme.colorScheme.primary,
                )
                if (task.nextRun != null && task.enabled && !task.archived)
                    Text("Prochaine exécution : ${date(task.nextRun)}")
                activity
                    .find { it.taskId == task.id }
                    ?.let { run ->
                        TextButton(onClick = { openRun(run.id) }) {
                            Text("Dernière exécution · ")
                            Status(run.status)
                        }
                    }
                if (task.tags.isNotEmpty()) Text(task.tags.joinToString(" · "))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionIcon(
                        "Lancer",
                        Icons.Default.PlayArrow,
                        onClick = {
                            vm.perform {
                                openRun(api.send<Run>("POST", "/tasks/${task.id}/run").id)
                            }
                        },
                        enabled = !state.busy && !task.archived,
                    )
                    ActionIcon(
                        "Modifier",
                        Icons.Default.Edit,
                        onClick = {
                            vm.clearMessage()
                            editing = task.id
                        },
                    )
                    TaskMenu(
                        task,
                        state.busy,
                        pause = {
                            vm.perform {
                                save(
                                    "tasks",
                                    task.id,
                                    wireJson.encodeToJsonElement(
                                        task.copy(enabled = !task.enabled)
                                    ),
                                )
                            }
                        },
                        archive = {
                            vm.perform {
                                save(
                                    "tasks",
                                    task.id,
                                    wireJson.encodeToJsonElement(
                                        task.copy(archived = !task.archived, enabled = false)
                                    ),
                                )
                            }
                        },
                        duplicate = {
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
                            }
                        },
                        delete = {
                            vm.clearMessage()
                            deleting = task.id
                        },
                    )
                }
            }
        }
    }
    editing?.let { id ->
        TaskEditor(
            vm,
            state,
            state.tasks.find { it.id == id }
                ?: Task(
                    agentId = state.agents.firstOrNull()?.id.orEmpty(),
                    projectId = null,
                    timezone = java.time.ZoneId.systemDefault().id,
                ),
        ) {
            editing = null
        }
    }
    deleting?.let { id ->
        Confirm(
            "Supprimer cette tâche ?",
            "Cette action supprime la tâche et sa planification. Les exécutions déjà réalisées restent dans l’historique.",
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
private fun TaskMenu(
    task: Task,
    busy: Boolean,
    pause: () -> Unit,
    archive: () -> Unit,
    duplicate: () -> Unit,
    delete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ActionIcon(
            "Plus",
            Icons.Default.MoreVert,
            onClick = { expanded = true },
            enabled = !busy,
        )
        DropdownMenu(expanded, { expanded = false }) {
            if (!task.archived)
                DropdownMenuItem(
                    text = { Text(if (task.enabled) "Mettre en pause" else "Reprendre") },
                    onClick = {
                        expanded = false
                        pause()
                    },
                )
            DropdownMenuItem(
                text = { Text(if (task.archived) "Restaurer (en pause)" else "Archiver") },
                onClick = {
                    expanded = false
                    archive()
                },
            )
            DropdownMenuItem(
                text = { Text("Dupliquer (en pause)") },
                onClick = {
                    expanded = false
                    duplicate()
                },
            )
            DropdownMenuItem(
                text = { Text("Supprimer") },
                onClick = {
                    expanded = false
                    delete()
                },
            )
        }
    }
}

@Composable
internal fun TaskEditor(vm: LeoViewModel, state: Workspace, initial: Task, close: () -> Unit) {
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
                save(
                    "tasks",
                    initial.id,
                    wireJson.encodeToJsonElement(
                        form.copy(
                            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        )
                    ),
                )
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
                                    body(
                                        "cron" to form.cron.orEmpty(),
                                        "timezone" to form.timezone,
                                    ),
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
