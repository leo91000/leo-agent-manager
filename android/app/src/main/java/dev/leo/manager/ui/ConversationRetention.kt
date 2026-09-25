package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.leo.manager.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class RetentionPolicy(
    val enabled: Boolean = false,
    val inactivityDays: Int = 30,
    val coldAfterDays: Int = 90,
    val eligible: Int = 0,
    val configured: Boolean = false,
)

@Composable
internal fun ConversationRetention(vm: LeoViewModel, state: Workspace) {
    var policy by remember { mutableStateOf<RetentionPolicy?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("30") }
    var cold by remember { mutableStateOf("90") }
    var confirming by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        try {
            val value = vm.api.get<RetentionPolicy>("/conversation-retention")
            policy = value
            enabled = value.enabled
            days = value.inactivityDays.toString()
            cold = value.coldAfterDays.toString()
        } catch (e: Exception) {
            vm.report(e)
        }
    }
    fun save(confirm: Boolean) {
        vm.perform {
            require(days.toIntOrNull() in 1..3650 && cold.toIntOrNull() in 1..3650) {
                "Choisissez des délais de 1 à 3650 jours."
            }
            if (enabled && !confirm) {
                count =
                    api.get<RetentionPolicy>("/conversation-retention?inactivityDays=$days")
                        .eligible
                confirming = true
            } else {
                policy =
                    api.send(
                        "PUT",
                        "/conversation-retention",
                        buildJsonObject {
                            put("enabled", enabled)
                            put("inactivityDays", days.toInt())
                            put("coldAfterDays", cold.toInt())
                            put("confirmExisting", confirm)
                        },
                    )
                confirming = false
            }
        }
    }
    Panel {
        Text("Stockage des conversations", style = MaterialTheme.typography.titleLarge)
        Row {
            Text("Archiver automatiquement", Modifier.weight(1f))
            Switch(enabled, { enabled = it }, enabled = policy?.configured == true)
        }
        if (policy?.configured == false)
            Text("Configurez S3 sur le serveur pour activer l’archivage.")
        OutlinedTextField(
            days,
            { days = it },
            label = { Text("Inactivité avant archivage (jours)") },
            singleLine = true,
        )
        OutlinedTextField(
            cold,
            { cold = it },
            label = { Text("Délai S3 avant Glacier (jours)") },
            singleLine = true,
        )
        Text(
            "Les archives restent conservées jusqu’à suppression. La corbeille conserve les conversations 30 jours. Une restauration Glacier peut prendre plusieurs heures."
        )
        Button({ save(false) }, enabled = policy != null && !state.busy) {
            Text("Enregistrer les règles")
        }
    }
    if (confirming)
        Confirm(
            "Activer l’archivage ?",
            "$count conversations existantes sont éligibles. Elles seront archivées progressivement. Le travail en cours et les messages en attente sont protégés.",
            state.busy,
            state.error,
            { confirming = false },
        ) {
            save(true)
        }
}
