package dev.leo.manager.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.leo.manager.data.*

@Composable
fun ConnectionsScreen(vm: LeoViewModel, state: Workspace, openRun: (String) -> Unit = {}) {
    var items by remember { mutableStateOf<List<Connection>>(emptyList()) }
    var flow by remember { mutableStateOf<DeviceFlow?>(null) }
    var loaded by remember { mutableStateOf(false) }
    suspend fun load(force: Boolean = false) {
        items = vm.api.get("/connections" + if (force) "?refresh=true" else "")
        flow = vm.api.get("/connections/login")
        loaded = true
    }
    Poll("connections", 3000) {
        try {
            if (!loaded) load()
            else {
                val next = vm.api.get<DeviceFlow?>("/connections/login")
                val completed = flow?.state == "pending" && next?.state == "complete"
                flow = next
                if (completed) load(true)
            }
        } catch (e: Exception) {
            vm.report(e)
        }
    }
    Page {
        Heading("Connexions", "Vos comptes. Vos outils. Les mêmes permissions.")
        OutlinedButton(onClick = { vm.perform { load(true) } }, enabled = !state.busy) {
            Text("Vérifier les connexions")
        }
        if (!loaded) LinearProgressIndicator()
        CodexAccounts(vm, state, openRun)
        ClaudeConnection(vm, state)
        OnePasswordAccounts(vm, state)
        items
            .filter { it.provider == "github" }
            .forEach { item ->
                Panel {
                    Text(
                        if (item.provider == "codex") "Codex" else "GitHub",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Status(
                        if (item.connected) "Connecté"
                        else if (item.installed) "Non connecté" else "CLI non installé"
                    )
                    item.account?.let { Text(it) }
                    item.version?.let { Code(it) }
                    Button(
                        onClick = {
                            vm.perform {
                                flow =
                                    api.send(
                                        "POST",
                                        "/connections/login",
                                        body("provider" to item.provider),
                                    )
                            }
                        },
                        enabled = !state.busy && item.installed && flow?.state != "pending",
                    ) {
                        Text(if (item.connected) "Reconnecter" else "Connecter le compte")
                    }
                }
            }
        flow?.let { current ->
            Panel {
                Text(
                    when (current.state) {
                        "complete" -> "Compte connecté"
                        "failed" -> "Connexion échouée"
                        else -> "Terminer la connexion"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                current.code
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Code(it)
                        CopyButton("Copier le code", it)
                    }
                if (current.state == "pending") {
                    Text("Ouvrez la page de vérification et saisissez ce code.")
                    current.url
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ExternalButton("Ouvrir la page de vérification", it) }
                    if (current.code.isNullOrBlank())
                        Text("Le serveur prépare le code de connexion…")
                    TextButton(
                        onClick = {
                            vm.perform {
                                api.request("DELETE", "/connections/login")
                                flow = null
                            }
                        },
                        enabled = !state.busy,
                    ) {
                        Text("Annuler la connexion")
                    }
                }
                current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Text(
            "Les comptes sont conservés sur votre serveur. Les agents utilisent les outils officiels qui y sont installés.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
