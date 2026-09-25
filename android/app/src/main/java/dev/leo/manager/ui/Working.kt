package dev.leo.manager.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.RunEvent
import kotlinx.coroutines.delay

/** What the working indicator says: the step in progress, or the last one reached. */
internal data class WorkingStep(val title: String, val detail: String, val since: Long?)

/**
 * The agent's current step since the latest user message: an in-progress action is named with
 * its command or files; otherwise the agent is simply working, with its last completed step.
 */
internal fun workingStep(events: List<RunEvent>, agent: String, fallbackStart: Long?): WorkingStep {
    val turnStart = events.indexOfLast { it.type == "chat.user" }
    val turn = if (turnStart >= 0) events.drop(turnStart + 1) else events
    val since = events.getOrNull(turnStart)?.createdAt?.takeIf { it > 0 } ?: fallbackStart
    val latest =
        turn.asReversed()
            .asSequence()
            .filter { !it.isMessage() }
            .map(::presentActivity)
            .firstOrNull { it.kind != ActivityKind.NOTICE }
    val name = agent.ifBlank { "L’agent" }
    return when {
        latest == null -> WorkingStep("$name travaille", "", since)
        latest.state == "En cours" && !latest.failed ->
            WorkingStep(latest.title, latest.command.ifBlank { latest.subtitle }, since)
        else -> WorkingStep("$name travaille", "Dernière étape : ${latest.title}", since)
    }
}

/** Seconds precision for the live indicator: "45 s", "2 min 14 s", "1 h 05". */
internal fun liveElapsed(start: Long?, now: Long = System.currentTimeMillis()): String {
    if (start == null || start <= 0) return ""
    val seconds = ((now - start).coerceAtLeast(0) / 1000)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s"
        else -> "${seconds / 3600} h ${((seconds % 3600) / 60).toString().padStart(2, '0')}"
    }
}

/**
 * "Souffle": a breathing halo, a light sweep across the current step and the elapsed time.
 * Animations follow the system animation scale, so they stop when motion is turned off.
 */
@Composable
internal fun WorkingIndicator(step: WorkingStep, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(step.since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "working")
    val ring by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)), label = "ring")
    val breath by transition.animateFloat(
        0.92f, 1.06f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val sweep by transition.animateFloat(-520f, 1040f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    val elapsed = liveElapsed(step.since, now)
    val base = MaterialTheme.colorScheme.onSurface
    Row(
        modifier.fillMaxWidth()
            .testTag("agent-working")
            .clearAndSetSemantics {
                contentDescription = listOf(step.title, step.detail).filter { it.isNotBlank() }.joinToString(" : ")
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(primary.copy(alpha = 0.35f * (1 - ring)), radius * (0.55f + 0.45f * ring), style = Stroke(2.dp.toPx()))
                drawCircle(primary.copy(alpha = 0.12f), radius * 0.62f)
            }
            Box(
                Modifier.size(20.dp).scale(breath).clip(CircleShape).background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LeoIcons.Steer, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${step.title}…",
                style = MaterialTheme.typography.titleSmall.copy(
                    brush = Brush.linearGradient(
                        listOf(base.copy(alpha = 0.55f), base, base.copy(alpha = 0.55f)),
                        start = Offset(sweep - 170f, 0f),
                        end = Offset(sweep + 170f, 0f),
                    ),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.detail.isNotBlank())
                Text(
                    step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = if (step.detail.startsWith("Dernière étape")) null else FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        if (elapsed.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(elapsed, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
