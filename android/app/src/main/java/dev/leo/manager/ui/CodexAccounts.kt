package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import dev.leo.manager.data.*
import kotlinx.serialization.json.*

@Composable
fun CodexAccounts(vm: LeoViewModel, state: Workspace, openRun: (String) -> Unit) {
    var accounts by remember { mutableStateOf<List<CodexAccount>>(emptyList()) }
    var flow by remember { mutableStateOf<CodexLogin?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CodexAccount?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CodexAccount?>(null) }
    suspend fun load(force: Boolean = false) {
        accounts =
            if (force) vm.api.send("POST", "/codex/accounts/refresh")
            else vm.api.get("/codex/accounts")
        flow = vm.api.get("/codex/accounts/login")
        loaded = true
    }
    Poll("codex-accounts", 3000) {
        try {
            load()
        } catch (e: Exception) {
            vm.report(e)
        }
    }
    val next =
        accounts
            .filter {
                it.enabled &&
                    it.state == "ready" &&
                    !it.stale &&
                    !it.blocked &&
                    it.runs.size < it.maxConcurrentRuns &&
                    (it.remainingPercent ?: 0.0) > 0
            }
            .sortedWith(
                compareByDescending<CodexAccount> { it.remainingPercent }
                    .thenBy { it.lastUsedAt ?: 0 }
            )
            .firstOrNull()
            ?.id
    Text("Comptes Codex", style = MaterialTheme.typography.titleLarge)
    Row {
        Button(
            onClick = {
                vm.clearMessage()
                adding = true
            },
            enabled = !state.busy && flow?.state != "pending",
        ) {
            Text("Ajouter un compte")
        }
        TextButton(onClick = { vm.perform { load(true) } }, enabled = !state.busy) {
            Text("Actualiser")
        }
    }
    if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
    accounts.forEach { account ->
        Panel {
            Text(account.name, style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(account.email, account.plan).joinToString(" · "))
            Text(
                when {
                    !account.enabled -> "En pause"
                    account.state == "pending" -> "Connexion à terminer"
                    account.state == "error" -> "À reconnecter"
                    account.stale -> "Quotas à actualiser"
                    account.blocked || account.remainingPercent == 0.0 ->
                        "En attente de renouvellement"
                    account.remainingPercent == null -> "Quotas indisponibles"
                    account.runs.size >= account.maxConcurrentRuns -> "Capacité atteinte"
                    account.id == next -> "Prioritaire pour la prochaine exécution"
                    else -> "Disponible"
                },
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                account.remainingPercent?.let { "${it.toInt()} % restants" } ?: "Quota inconnu",
                style = MaterialTheme.typography.headlineSmall,
            )
            val buckets =
                listOf("Quota principal" to account.limits?.rateLimits) +
                    account.limits?.rateLimitsByLimitId.orEmpty().map { it.key to it.value }
            buckets.forEach { (label, bucket) ->
                listOfNotNull(bucket?.primary, bucket?.secondary).forEach { window ->
                    Text(
                        "$label · ${window.windowDurationMins?.let { "$it min" } ?: "Fenêtre"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            ((100.0 - window.usedPercent) / 100.0).toFloat().coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Renouvellement : ${date(window.resetsAt?.times(1000))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            account.limits?.rateLimitResetCredits?.let {
                Text(
                    "${it.availableCount} renouvellements en réserve · utilisation automatique à 2 % si le compte est actif"
                )
            }
            if (account.error.isNotBlank())
                Text(account.error, color = MaterialTheme.colorScheme.error)
            if (account.resetError.isNotBlank())
                Text(account.resetError, color = MaterialTheme.colorScheme.error)
            Text(
                "${account.runs.size} / ${account.maxConcurrentRuns} exécutions parallèles · mise à jour ${date(account.checkedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            account.runs.forEachIndexed { index, id ->
                TextButton(onClick = { openRun(id) }) { Text("Voir l’exécution ${index + 1}") }
            }
            Row {
                TextButton(
                    onClick = {
                        vm.perform {
                            api.request(
                                "PUT",
                                "/codex/accounts/${segment(account.id)}",
                                buildJsonObject {
                                    put("name", account.name)
                                    put("enabled", !account.enabled)
                                },
                            )
                            load()
                        }
                    },
                    enabled = !state.busy && flow?.state != "pending",
                ) {
                    Text(if (account.enabled) "Pause" else "Activer")
                }
                TextButton(
                    onClick = {
                        vm.clearMessage()
                        editing = account
                    },
                    enabled = !state.busy,
                ) {
                    Text("Modifier")
                }
                TextButton(
                    onClick = { removing = account },
                    enabled = !state.busy && account.runs.isEmpty() && flow?.state != "pending",
                ) {
                    Text("Retirer")
                }
            }
            OutlinedButton(
                onClick = {
                    vm.perform {
                        flow =
                            api.send(
                                "POST",
                                "/codex/accounts/login",
                                body("name" to account.name, "id" to account.id),
                            )
                    }
                },
                enabled = !state.busy && account.runs.isEmpty() && flow?.state != "pending",
            ) {
                Text("Reconnecter")
            }
        }
    }
    flow?.let { current ->
        if (current.state != "complete")
            Panel {
                Text(
                    if (current.state == "failed") "Connexion échouée" else "Connecter le compte",
                    style = MaterialTheme.typography.titleLarge,
                )
                current.code?.let {
                    Code(it)
                    CopyButton("Copier le code", it)
                }
                current.url?.let { ExternalButton("Ouvrir la page de vérification", it) }
                if (current.code.isNullOrBlank() && current.state == "pending")
                    Text(
                        if (current.phase == "verifying") "Vérification de la connexion…"
                        else "Préparation du code…"
                    )
                current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(
                        onClick = {
                            vm.perform {
                                api.request("DELETE", "/codex/accounts/login")
                                flow = null
                                load()
                            }
                        },
                        enabled = !state.busy,
                    ) {
                        Text("Annuler")
                    }
                    if (current.state == "failed")
                        TextButton(
                            onClick = {
                                vm.perform {
                                    accounts
                                        .find { it.id == current.accountId }
                                        ?.let { account ->
                                            flow =
                                                api.send(
                                                    "POST",
                                                    "/codex/accounts/login",
                                                    body(
                                                        "name" to account.name,
                                                        "id" to account.id,
                                                    ),
                                                )
                                        }
                                }
                            },
                            enabled = !state.busy,
                        ) {
                            Text("Réessayer")
                        }
                }
            }
    }
    if (adding || editing != null) {
        val initial = editing
        var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
        var concurrency by
            rememberSaveable(initial?.id) {
                mutableStateOf((initial?.maxConcurrentRuns ?: 4).toString())
            }
        Editor(
            if (initial == null) "Ajouter un compte Codex" else "Modifier le compte",
            state.busy,
            state.error,
            {
                adding = false
                editing = null
            },
            save = {
                vm.perform {
                    if (initial == null)
                        flow = api.send("POST", "/codex/accounts/login", body("name" to name))
                    else
                        api.request(
                            "PUT",
                            "/codex/accounts/${segment(initial.id)}",
                            buildJsonObject {
                                put("name", name)
                                put("enabled", initial.enabled)
                                put("maxConcurrentRuns", concurrency.toInt())
                            },
                        )
                    adding = false
                    editing = null
                    load()
                }
            },
            valid = name.isNotBlank() && name.length <= 100 && concurrency.toIntOrNull() in 1..4,
        ) {
            Field("Nom du compte", name, { name = it })
            if (initial != null)
                Field(
                    "Exécutions parallèles (1–4)",
                    concurrency,
                    { concurrency = it },
                    keyboardOptions = InputKeyboards.Number,
                )
            else
                Text("La connexion s’effectue avec votre compte ChatGPT et un code à usage unique.")
        }
    }
    removing?.let { account ->
        Confirm(
            "Retirer ${account.name} ?",
            "Les identifiants enregistrés de ce compte seront supprimés du serveur.",
            state.busy,
            state.error,
            { removing = null },
        ) {
            vm.perform {
                api.request("DELETE", "/codex/accounts/${segment(account.id)}")
                removing = null
                load()
            }
        }
    }
}
