@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.*
import kotlinx.serialization.json.*

internal val LocalFocusMode = compositionLocalOf<(Boolean) -> Unit> { {} }

internal data class SendingMessage(val message: ChatMessage, val label: String)

internal data class ChatDelivery(val sending: List<SendingMessage>, val queued: List<ChatMessage>)

/** The server's durable dispatch queue is not necessarily a waiting user message. */
internal fun chatDelivery(
    chat: Chat?,
    events: List<RunEvent>,
    outgoing: ChatMessage? = null,
): ChatDelivery {
    val acknowledged =
        events
            .filter { it.type == "chat.user" }
            .mapNotNull { (it.payload?.get("messageId") as? JsonPrimitive)?.contentOrNull }
            .toSet()
    val messages = chat?.messages.orEmpty().filter { it.status != "delivered" }.toMutableList()
    if (outgoing != null && messages.none { it.id == outgoing.id }) messages.add(outgoing)
    val active = chat?.run?.active == true
    val canStart = chat?.paused != true && (chat?.run == null || chat.run.status == "succeeded")
    val sending = mutableListOf<SendingMessage>()
    val queued = mutableListOf<ChatMessage>()
    messages.forEachIndexed { index, message ->
        if (message.id in acknowledged) return@forEachIndexed
        val local =
            message.id == outgoing?.id && chat?.messages.orEmpty().none { it.id == message.id }
        val starting = active && chat?.run?.chatExecution?.messageId == message.id
        val steering =
            active &&
                message.mode == "steer" &&
                (chat?.paused != true || message.questionId != null)
        if (local || starting || steering || (canStart && index == 0)) {
            // Private/question responses are rendered only from the server transcript.
            if (message.questionId == null)
                sending.add(
                    SendingMessage(
                        message,
                        when {
                            starting -> "Démarrage de l’agent…"
                            steering -> "Transmission à l’agent…"
                            else -> "Envoi en cours…"
                        },
                    )
                )
        } else queued.add(message)
    }
    return ChatDelivery(sending, queued)
}

internal fun outcomeLabel(outcome: TaskOutcome) =
    when (outcome.status) {
        "completed" -> "Tâche terminée"
        "blocked" -> "Tâche bloquée"
        else -> "Votre réponse est nécessaire"
    }

@Composable
internal fun CompletionEvidence(outcome: TaskOutcome, agent: String) {
    var expanded by
        rememberSaveable(outcome.messageId, outcome.reportedAt) { mutableStateOf(false) }
    val attention = outcome.status != "completed"
    val color =
        if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color =
            if (attention) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = if (attention) 12.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (attention) Icons.Default.Info else Icons.Default.Check,
                    null,
                    Modifier.size(16.dp),
                    tint = color,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    outcomeLabel(outcome),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Masquer" else "Détails")
                    Icon(
                        if (expanded) LeoIcons.Down else LeoIcons.Right,
                        null,
                        Modifier.size(16.dp),
                    )
                }
            }
            if (attention) Markdown(outcome.reason)
            if (expanded) {
                if (!attention) Markdown(outcome.reason)
                outcome.evidence.forEach { evidence ->
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Markdown(evidence)
                }
                Text(
                    "Rapporté par $agent · ${date(outcome.reportedAt)}",
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One compact bar; secondary information belongs in the details sheet. */
@Composable
internal fun ConversationHeader(
    label: String,
    title: String,
    choose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(Modifier.testTag("conversation-header"), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = choose,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (label == "Conversations") LeoIcons.Chat else LeoIcons.Tasks,
                                null,
                                Modifier.padding(end = 8.dp).size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                LeoIcons.Down,
                                null,
                                Modifier.padding(start = 6.dp).size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (title.isNotBlank())
                            Text(
                                title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                    }
                }
                actions()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
internal fun DetailSheet(
    title: String,
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            ActionIcon("Fermer $title", Icons.Default.Close, onClick = close)
        }
        Box(Modifier.fillMaxHeight(0.85f)) { Page(content = content) }
    }
}
