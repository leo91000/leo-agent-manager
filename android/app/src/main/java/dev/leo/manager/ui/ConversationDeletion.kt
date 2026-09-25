package dev.leo.manager.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leo.manager.data.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun rememberTrashConversation(vm: LeoViewModel, onTrashed: (String) -> Unit): (String) -> Unit {
    var confirming by remember { mutableStateOf<String?>(null) }
    fun trash(id: String, confirm: Boolean = false) {
        vm.perform {
            try {
                api.request("DELETE", "/chats/${segment(id)}", buildJsonObject { put("confirm", confirm) })
                chatDrafts.remove(id)
                historyCache.clear()
                confirming = null
                onTrashed(id)
            } catch (e: ApiException) {
                if (e.status == 409 && !confirm) confirming = id else throw e
            }
        }
    }
    confirming?.let { id ->
        val state by vm.state.collectAsStateWithLifecycle()
        Confirm(
            "Arrêter et supprimer ?",
            "Le travail sera arrêté et les envois annulés. La conversation restera récupérable pendant 30 jours.",
            state.busy, state.error, { confirming = null },
        ) { trash(id, true) }
    }
    return { trash(it) }
}

/** A swipe only reveals the explicit delete action; swiping right closes it. */
@Composable
internal fun SwipeToTrashRow(
    enabled: Boolean = true,
    onDelete: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val distance = with(LocalDensity.current) { 104.dp.roundToPx() }
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        if (revealed && enabled)
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterEnd).width(104.dp)) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        content(
            Modifier.offset { IntOffset(if (revealed && enabled) -distance else 0, 0) }
                .draggable(
                    state = rememberDraggableState { drag += it },
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStarted = { drag = 0f },
                    onDragStopped = {
                        if (drag < -threshold) revealed = true
                        else if (drag > threshold) revealed = false
                    },
                )
        )
    }
}
