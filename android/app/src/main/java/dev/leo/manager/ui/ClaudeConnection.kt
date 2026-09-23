package dev.leo.manager.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val email: String? = null,
    val subscriptionType: String? = null,
    val error: String? = null,
    val login: ClaudeLogin? = null,
)

@Composable
fun ClaudeConnection(vm: LeoViewModel, state: Workspace) {
    var account by remember { mutableStateOf(ClaudeConnectionState()) }
    var error by remember { mutableStateOf("") }
    // Authorization codes never enter saved instance state or preferences.
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    suspend fun load() { account = vm.api.get("/claude/connection") }
    Poll("claude-connection", 3000) {
        try { load() } catch (e: Exception) {
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
        Text("Connectez votre compte Claude pour utiliser Claude Code avec vos agents.")
        if (login?.state == "pending") {
            LinearProgressIndicator()
            Text("Terminez la connexion sur la page d’Anthropic.")
            login.url?.let { ExternalButton("Ouvrir la connexion Claude", it) }
                ?: Text("Préparation du lien de connexion…")
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code d’autorisation Claude") }, singleLine = true, keyboardOptions = InputKeyboards.Password, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), enabled = !state.busy)
            Text("Si Anthropic affiche un code, collez-le ici. Le lien expire après 15 minutes.", style = MaterialTheme.typography.bodySmall)
            if (submitted) Text("Vérification du code…")
            Button(onClick = {
                vm.perform {
                    api.request("POST", "/claude/login/code", body("id" to login.id, "code" to code))
                    code = ""
                    submitted = true
                    load()
                }
            }, enabled = !state.busy && code.isNotBlank()) { Text("Terminer la connexion") }
            TextButton(onClick = {
                vm.perform { api.request("DELETE", "/claude/login"); code = ""; submitted = false; load() }
            }, enabled = !state.busy) { Text("Annuler la connexion Claude") }
        } else {
            Button(onClick = {
                vm.perform { api.request("POST", "/claude/login"); error = ""; submitted = false; load() }
            }, enabled = !state.busy && !account.busy) {
                Text(if (account.connected) "Reconnecter Claude Code" else "Connecter Claude Code")
            }
            if (account.connected) TextButton(onClick = {
                vm.perform { api.request("DELETE", "/claude/connection"); load() }
            }, enabled = !state.busy && !account.busy) { Text("Déconnecter Claude Code") }
        }
        if (account.busy) Text("Un agent Claude est en cours. Attendez sa fin pour modifier la connexion.")
        (login?.error ?: account.error ?: error.takeIf { it.isNotEmpty() })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("La connexion utilise l’outil officiel Claude Code. Votre mot de passe reste chez Anthropic.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
