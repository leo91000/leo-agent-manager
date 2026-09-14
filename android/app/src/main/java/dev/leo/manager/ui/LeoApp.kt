@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import dev.leo.manager.data.*

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations =
    listOf(
        Destination("chats", "Chats", LeoIcons.Chat),
        Destination("tasks", "Tâches", Icons.AutoMirrored.Filled.List),
        Destination("runs", "Activité", LeoIcons.Terminal),
        Destination("workspace", "Espace", Icons.Default.Menu),
    )

@Composable
fun LeoApp(
    sharedUrl: String = "",
    consumedShare: () -> Unit = {},
    vm: LeoViewModel = viewModel(),
    targetChat: String = "",
    targetOrigin: String = "",
    consumedTarget: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }
    if (!state.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (!state.session.authenticated) {
        LoginScreen(vm, state)
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "chats"
    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotBlank()) nav.navigate("authorize") { launchSingleTop = true }
    }
    LaunchedEffect(targetChat, state.origin) {
        if (targetChat.isNotBlank() && targetOrigin == state.origin) {
            nav.navigate("chat/${segment(targetChat)}") { launchSingleTop = true }
            consumedTarget()
        }
    }
    val focused =
        route.startsWith("chat/") || route.startsWith("new-chat") || route.startsWith("run/")
    val selected =
        if (route.startsWith("chat/") || route.startsWith("new-chat")) "chats"
        else if (route.startsWith("run/")) "runs"
        else if (destinations.any { it.route == route }) route else "workspace"
    fun navigate(target: String) {
        nav.navigate(target) {
            popUpTo("chats") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        Row {
            if (wide)
                NavigationRail(Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(24.dp))
                    destinations.forEach { d ->
                        NavigationRailItem(
                            selected == d.route,
                            { navigate(d.route) },
                            icon = { Icon(d.icon, d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            Scaffold(
                modifier = Modifier.weight(1f).imePadding(),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    if (!focused && route !in destinations.map { it.route })
                        TopAppBar(
                            title = { Text("Leo", style = MaterialTheme.typography.titleLarge) },
                            navigationIcon = {
                                if (route !in destinations.map { it.route })
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                                    }
                            },
                            actions = {
                                IconButton(
                                    onClick = { vm.perform { refresh() } },
                                    enabled = !state.busy,
                                ) {
                                    Icon(Icons.Default.Refresh, "Actualiser l’espace")
                                }
                            },
                        )
                },
                bottomBar = {
                    if (!wide && !focused && !WindowInsets.isImeVisible)
                        NavigationBar {
                            destinations.forEach { d ->
                                NavigationBarItem(
                                    selected == d.route,
                                    { navigate(d.route) },
                                    icon = { Icon(d.icon, d.label) },
                                    label = { Text(d.label) },
                                )
                            }
                        }
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { ErrorNotice(it, vm::clearMessage) }
                    NavHost(nav, "chats", Modifier.weight(1f)) {
                        composable("chats") {
                            ChatsScreen(
                                vm,
                                state,
                                { nav.navigate("chat/$it") },
                                { nav.navigate("new-chat") },
                            )
                        }
                        composable("chat/{id}") { entry ->
                            ChatScreen(
                                vm,
                                state,
                                entry.arguments?.getString("id"),
                                openChat = { nav.navigate("chat/$it") { popUpTo("chats") } },
                                openRun = { nav.navigate("run/$it") },
                                back = { nav.popBackStack() },
                            )
                        }
                        composable("new-chat?agent={agent}&project={project}") { entry ->
                            ChatScreen(
                                vm,
                                state,
                                null,
                                initialAgent = entry.arguments?.getString("agent") ?: MAIN_AGENT_ID,
                                initialProject = entry.arguments?.getString("project").orEmpty(),
                                openChat = { nav.navigate("chat/$it") { popUpTo("chats") } },
                                openRun = { nav.navigate("run/$it") },
                                back = { nav.popBackStack() },
                            )
                        }
                        composable("overview") {
                            OverviewScreen(
                                vm,
                                state,
                                { nav.navigate("run/$it") },
                                { nav.navigate("tasks") },
                            )
                        }
                        composable("tasks") { TasksScreen(vm, state) { nav.navigate("run/$it") } }
                        composable("runs") { RunsScreen(vm, state) { nav.navigate("run/$it") } }
                        composable("run/{id}") { entry ->
                            RunScreen(
                                vm,
                                state,
                                entry.arguments?.getString("id").orEmpty(),
                                openChat = { nav.navigate("chat/$it") },
                                back = { nav.popBackStack() },
                            ) {
                                nav.navigate("run/$it") { popUpTo("runs") }
                            }
                        }
                        composable("workspace") { WorkspaceScreen { nav.navigate(it) } }
                        composable("agents") {
                            ResourcesScreen(vm, state, true) { agent, project ->
                                nav.navigate("new-chat?agent=$agent&project=$project")
                            }
                        }
                        composable("projects") {
                            ResourcesScreen(vm, state, false) { agent, project ->
                                nav.navigate("new-chat?agent=$agent&project=$project")
                            }
                        }
                        composable("mcps") { McpsScreen(vm, state) }
                        composable("skills") { SkillsScreen(vm, state) }
                        composable("connections") {
                            ConnectionsScreen(vm, state) { nav.navigate("run/$it") }
                        }
                        composable("settings") { SettingsScreen(vm, state) }
                        composable("authorize") {
                            AuthorizeScreen(vm, state, sharedUrl, consumedShare)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(vm: LeoViewModel, state: Workspace) {
    var origin by rememberSaveable(state.origin) { mutableStateOf(state.origin) }
    // Passwords and bootstrap tokens deliberately never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var setupToken by remember { mutableStateOf("") }
    Scaffold { padding ->
        Box(
            Modifier.padding(padding).imePadding().fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.widthIn(max = 520.dp)) {
                Page {
                    Heading("Bienvenue dans Leo", "Votre espace de travail, dans votre poche.")
                    Panel {
                        Text(
                            "Connexion à votre serveur",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.origin.isBlank()) {
                            OutlinedTextField(
                                origin,
                                { origin = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("Adresse du serveur") },
                                placeholder = { Text("https://leo.exemple.fr") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Button(
                                onClick = { vm.perform { connect(origin) } },
                                enabled = origin.isNotBlank() && !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Continuer")
                            }
                        } else {
                            Text(state.origin, color = MaterialTheme.colorScheme.primary)
                            if (state.session.setupRequired) {
                                Text(
                                    "Créez le compte propriétaire avec le jeton d’installation du serveur."
                                )
                                OutlinedTextField(
                                    setupToken,
                                    { setupToken = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("Jeton d’installation") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                )
                            }
                            OutlinedTextField(
                                password,
                                { password = it },
                                Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        if (state.session.setupRequired)
                                            "Mot de passe · 12 caractères minimum"
                                        else "Mot de passe"
                                    )
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            Button(
                                onClick = {
                                    vm.perform {
                                        login(password, setupToken)
                                        password = ""
                                        setupToken = ""
                                    }
                                },
                                enabled =
                                    !state.busy &&
                                        password.isNotBlank() &&
                                        (!state.session.setupRequired ||
                                            (password.length >= 12 && setupToken.isNotBlank())),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (state.session.setupRequired) "Créer mon compte"
                                    else "Se connecter"
                                )
                            }
                            TextButton(
                                onClick = {
                                    vm.perform { forget() }
                                    password = ""
                                    setupToken = ""
                                },
                                enabled = !state.busy,
                            ) {
                                Text("Changer de serveur")
                            }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { ErrorNotice(it, vm::clearMessage) }
                    }
                    Text(
                        "Retrouvez vos agents, vos tâches et leurs résultats. Les exécutions continuent sur votre serveur.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceScreen(navigate: (String) -> Unit) {
    Page {
        Heading("Votre espace")
        listOf(
                "overview" to "Vue d’ensemble",
                "agents" to "Agents",
                "projects" to "Projets",
                "skills" to "Skills",
                "mcps" to "Serveurs MCP",
                "connections" to "Connexions",
                "settings" to "Paramètres et accès",
                "authorize" to "Autoriser un assistant",
            )
            .forEach { (route, label) ->
                ElevatedCard(onClick = { navigate(route) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(label) },
                        trailingContent = { Icon(LeoIcons.Right, null, Modifier.size(18.dp)) },
                    )
                }
            }
    }
}

@Composable
private fun OverviewScreen(
    vm: LeoViewModel,
    state: Workspace,
    openRun: (String) -> Unit,
    tasks: () -> Unit,
) {
    Poll("overview", 15000) {
        try {
            vm.refresh()
        } catch (e: Exception) {
            vm.report(e)
        }
    }
    Page {
        Heading("Vue d’ensemble")
        Button(onClick = tasks) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Gérer mes tâches")
        }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("En cours", state.overview.counts["running"] ?: 0)
                Metric("Terminées", state.overview.counts["succeeded"] ?: 0)
                Metric("Agents", state.agents.size)
            }
            Text(
                "${state.overview.counts["queued"] ?: 0} en attente · ${state.tasks.count { it.cron != null && it.enabled && !it.archived }} planifiées",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Activité récente", style = MaterialTheme.typography.titleLarge)
        if (state.overview.runs.isEmpty())
            Empty(
                "Votre prochaine réussite commence ici",
                "Créez une tâche et confiez-la à un agent.",
            )
        state.overview.runs.forEach { RunCard(it, openRun) }
        Text("À venir", style = MaterialTheme.typography.titleLarge)
        val upcoming =
            state.tasks
                .filter { it.enabled && !it.archived && it.nextRun != null }
                .sortedBy { it.nextRun }
                .take(3)
        if (upcoming.isEmpty()) Text("Aucune exécution planifiée.")
        upcoming.forEach { task ->
            Panel {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                Text(date(task.nextRun))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Int) {
    Column {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun RunCard(run: Run, open: (String) -> Unit) {
    Card(onClick = { open(run.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(run.title, style = MaterialTheme.typography.titleMedium)
            Status(run.status)
            Text(
                "${date(run.createdAt)} · ${duration(run)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
