package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GithubRepositoryPicker(api: LeoApi, selected: String, enabled: Boolean = true, select: (GithubRepository) -> Unit) {
    var repositories by remember { mutableStateOf(emptyList<GithubRepository>()) }
    var nextPage by remember { mutableStateOf<Int?>(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        val page = nextPage ?: return
        if (loading) return
        loading = true
        error = null
        try {
            val result = api.get<GithubRepositoryPage>("/github/repositories?page=$page")
            repositories = (repositories + result.repositories).distinctBy { it.fullName }
            nextPage = result.nextPage
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "GitHub indisponible. Réessayez."
        } finally { loading = false }
    }
    LaunchedEffect(api) { load() }
    Text("Choisissez un dépôt de la connexion GitHub partagée. Il sera cloné à l’enregistrement.")
    SearchField("Filtrer les dépôts chargés", query, { query = it })
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        Text("Vérifiez la connexion GitHub dans Connexions, puis réessayez.")
    }
    val filtered = repositories.filter { "${it.fullName} ${it.description}".contains(query, true) }
    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        filtered.forEach { repo ->
            OutlinedButton(onClick = { select(repo) }, enabled = enabled && !repo.imported, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text((if (selected == repo.fullName) "✓ " else "") + repo.fullName)
                    Text((if (repo.isPrivate) "Privé" else "Public") + (if (repo.archived) " · Archivé" else "") + (if (repo.imported) " · Déjà ajouté" else ""), style = MaterialTheme.typography.bodySmall)
                    if (repo.description.isNotBlank()) Text(repo.description, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (!loading && error == null && filtered.isEmpty()) Text("Aucun dépôt correspondant dans les résultats chargés.")
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (nextPage != null) TextButton(onClick = { scope.launch { load() } }, enabled = enabled && !loading) {
        Text(if (error != null) "Réessayer" else "Charger plus de dépôts")
    }
}
