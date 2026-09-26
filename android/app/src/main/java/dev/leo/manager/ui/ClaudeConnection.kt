package dev.leo.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.leo.manager.data.*
import kotlinx.serialization.Serializable

@Serializable
data class ClaudeLogin(
    val id: String = "",
    val state: String = "",
    val url: String? = null,
    val error: String? = null,
    val expiresAt: Long = 0,
)

@Serializable
data class ClaudeConnectionState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val maxConcurrent: Int = 4,
    val activeRuns: Int = 0,
    val serverConcurrency: Int = 4,
    val email: String? = null,
    val subscriptionType: String? = null,
    val error: String? = null,
    val login: ClaudeLogin? = null,
    val usage: ClaudeUsage? = null,
)

@Serializable
data class ClaudeUsageWindow(
    val id: String = "",
    val label: String = "",
    val usedPercent: Double = 0.0,
    val resetsAt: Long? = null,
)

@Serializable
data class ClaudeUsage(
    val windows: List<ClaudeUsageWindow> = emptyList(),
    val checkedAt: Long? = null,
    val stale: Boolean = true,
    val error: String? = null,
)

private fun usageLabel(window: ClaudeUsageWindow): String =
    when (window.id) {
        "five_hour" -> "Fenêtre de 5 heures"
        "seven_day" -> "Semaine"
        else -> window.label.replace("Weekly", "Semaine")
    }

@Composable
fun ClaudeConnection(vm: LeoViewModel, state: Workspace) {
    var account by remember { mutableStateOf(ClaudeConnectionState()) }
    var error by remember { mutableStateOf("") }
    // Authorization codes never enter saved instance state or preferences.
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var concurrency by remember { mutableStateOf("") }
    var concurrencyDirty by remember { mutableStateOf(false) }
    LaunchedEffect(account.maxConcurrent) {
        if (!concurrencyDirty) concurrency = account.maxConcurrent.toString()
    }
    suspend fun load() {
        account = vm.api.get("/claude/connection")
    }
    Poll("claude-connection", 3000) {
        try {
            load()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Connexion indisponible."
        }
    }
    val login = account.login
    Panel {
        Text("Claude Code", style = MaterialTheme.typography.titleLarge)
        Status(if (account.connected) "Connecté" else "Non connecté")
        account.email?.let { Text(it) }
        account.subscriptionType?.let { Text("Abonnement $it") }
        if (account.connected) {
            val usage = account.usage
            if (usage == null || usage.windows.isEmpty()) {
                Text(
                    "Limites d’utilisation indisponibles.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                usage.windows.forEach { window ->
                    val remaining = (100.0 - window.usedPercent).coerceIn(0.0, 100.0)
                    Text(
                        "${usageLabel(window)} · ${kotlin.math.round(remaining).toInt()} % restants"
                    )
                    LinearProgressIndicator(
                        progress = { (remaining / 100).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color =
                            when {
                                usage.stale -> MaterialTheme.colorScheme.outline
                                remaining < 5 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                    )
                    Text(
                        window.resetsAt?.let { "Renouvellement : ${date(it * 1000)}" }
                            ?: "Date de renouvellement indisponible",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            usage?.checkedAt?.let {
                Text(
                    "${if (usage.stale) "Dernières limites connues" else "Limites vérifiées"} · ${date(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (usage?.error != null)
                Text(
                    "Les limites Claude sont temporairement indisponibles. Une nouvelle vérification sera effectuée automatiquement.",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
        OutlinedTextField(
            value = concurrency,
            onValueChange = {
                concurrency = it
                concurrencyDirty = true
            },
            label = { Text("Conversations Claude simultanées") },
            singleLine = true,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
        )
        Text(
            "${account.activeRuns} en cours · Capacité du serveur : ${account.serverConcurrency} exécutions au total.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "De 1 à 32. Réduire la limite laisse les conversations en cours se terminer.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                val limit = concurrency.toIntOrNull() ?: return@Button
                vm.perform {
                    account =
                        api.send(
                            "PATCH",
                            "/claude/connection",
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "maxConcurrent",
                                    kotlinx.serialization.json.JsonPrimitive(limit),
                                )
                            },
                        )
                    concurrencyDirty = false
                    concurrency = account.maxConcurrent.toString()
                }
            },
            enabled = !state.busy && concurrencyDirty && (concurrency.toIntOrNull() ?: 0) in 1..32,
        ) {
            Text("Enregistrer la limite")
        }
        Text("Connectez votre compte Claude pour utiliser Claude Code avec vos agents.")
        if (login?.state == "pending") {
            LinearProgressIndicator()
            Text("Terminez la connexion sur la page d’Anthropic.")
            login.url?.let { ExternalButton("Ouvrir la connexion Claude", it) }
                ?: Text("Préparation du lien de connexion…")
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code d’autorisation Claude") },
                singleLine = true,
                keyboardOptions = InputKeyboards.Password,
                visualTransformation =
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                enabled = !state.busy,
            )
            Text(
                "Si Anthropic affiche un code, collez-le ici. Le lien expire après 15 minutes.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (submitted) Text("Vérification du code…")
            Button(
                onClick = {
                    vm.perform {
                        api.request(
                            "POST",
                            "/claude/login/code",
                            body("id" to login.id, "code" to code),
                        )
                        code = ""
                        submitted = true
                        load()
                    }
                },
                enabled = !state.busy && code.isNotBlank(),
            ) {
                Text("Terminer la connexion")
            }
            TextButton(
                onClick = {
                    vm.perform {
                        api.request("DELETE", "/claude/login")
                        code = ""
                        submitted = false
                        load()
                    }
                },
                enabled = !state.busy,
            ) {
                Text("Annuler la connexion Claude")
            }
        } else {
            Button(
                onClick = {
                    vm.perform {
                        api.request("POST", "/claude/login")
                        error = ""
                        submitted = false
                        load()
                    }
                },
                enabled = !state.busy && !account.busy,
            ) {
                Text(if (account.connected) "Reconnecter Claude Code" else "Connecter Claude Code")
            }
            if (account.connected)
                TextButton(
                    onClick = {
                        vm.perform {
                            api.request("DELETE", "/claude/connection")
                            load()
                        }
                    },
                    enabled = !state.busy && !account.busy,
                ) {
                    Text("Déconnecter Claude Code")
                }
        }
        if (account.busy)
            Text("Un agent Claude est en cours. Attendez sa fin pour modifier la connexion.")
        (login?.error ?: account.error ?: error.takeIf { it.isNotEmpty() })?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "La connexion utilise l’outil officiel Claude Code. Votre mot de passe reste chez Anthropic.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
