@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatProviderPicker(
    provider: String,
    switching: Boolean,
    enabled: Boolean,
    change: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("codex" to "Codex", "claude" to "Claude Code").forEach { (value, label) ->
                FilterChip(
                    selected = provider == value,
                    onClick = { if (provider != value) change(value) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
        if (switching) Text(
            "Au prochain message, l’assistant reprend le contexte du chat et les fichiers existants.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
