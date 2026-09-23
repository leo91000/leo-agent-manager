@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.leo.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.leo.manager.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun effortLabel(value: String): String =
    when (value) {
        "none" -> "Aucun"
        "minimal" -> "Minimal"
        "low" -> "Faible"
        "medium" -> "Moyen"
        "high" -> "Élevé"
        "xhigh" -> "Très élevé"
        "max" -> "Maximal"
        "ultra" -> "Ultra"
        "" -> "Par défaut"
        else -> value
    }

internal fun selectedModel(
    catalog: ModelCatalog,
    model: String,
    defaultModel: String,
): CodexModel? {
    val name = model.ifBlank { defaultModel }
    return if (name.isNotBlank()) catalog.models.find { it.model == name }
    else catalog.models.find { it.isDefault }
}

/** Shared by the composer and agent settings. Empty values preserve server inheritance. */
@Composable
fun ModelPicker(
    catalog: ModelCatalog,
    model: String,
    reasoning: String,
    defaultModel: String = "",
    defaultReasoning: String = "",
    inherit: Boolean = false,
    enabled: Boolean = true,
    refresh: (suspend () -> Unit)? = null,
    change: (String, String) -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        if (refreshing || refresh == null) return
        refreshing = true
        refreshError = ""
        try { refresh() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            refreshError = "Catalogue temporairement indisponible. Réessayez."
        } finally { refreshing = false }
    }
    val selected = selectedModel(catalog, model, defaultModel)
    val inheritedEffort =
        if (inherit && model.isBlank())
            defaultReasoning.ifBlank { selected?.defaultReasoningEffort.orEmpty() }
        else selected?.defaultReasoningEffort.orEmpty()
    val effectiveEffort = reasoning.ifBlank { inheritedEffort }
    val modelLabel =
        selected?.displayName?.ifBlank { selected.model }
            ?: model.ifBlank { defaultModel }.ifBlank { "Par défaut" }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { sheet = "model" },
            enabled = enabled,
            modifier =
                Modifier.testTag("model-picker").semantics {
                    contentDescription = "Choisir le modèle : $modelLabel"
                },
        ) {
            Text("$modelLabel ▾")
        }
        TextButton(
            onClick = { sheet = "reasoning" },
            enabled = enabled,
            modifier =
                Modifier.testTag("reasoning-picker").semantics {
                    contentDescription = "Régler le raisonnement : ${effortLabel(effectiveEffort)}"
                },
        ) {
            Text("Effort : ${effortLabel(effectiveEffort)} ▾")
        }
    }
    if (sheet.isNotEmpty())
        ModalBottomSheet(
            onDismissRequest = { sheet = "" },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            if (refresh != null) Poll(sheet, 300_000) { reload() }
            Column(
                Modifier.fillMaxWidth()
                    .testTag("model-settings-sheet")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (sheet == "model") "Modèle" else "Raisonnement",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = { sheet = "" }) { Text("Terminé") }
                }
                if (inherit)
                    Text(
                        "Ces réglages s’appliquent au prochain message.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (refresh != null) {
                    TextButton(
                        onClick = { scope.launch { reload() } },
                        enabled = !refreshing,
                        modifier = Modifier.testTag("refresh-models"),
                    ) { Text(if (refreshing) "Actualisation des modèles…" else "Actualiser les modèles") }
                }
                if (refreshError.isNotBlank()) Text(refreshError, color = MaterialTheme.colorScheme.error)
                if (sheet == "model") {
                    var query by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Rechercher un modèle") },
                        singleLine = true,
                        keyboardOptions = InputKeyboards.Search,
                    )
                    ModelRow(
                        if (inherit) "Modèle de l’agent" else "Modèle par défaut",
                        defaultModel.ifBlank { "Suivre le réglage par défaut" },
                        model.isBlank(),
                        enabled,
                    ) {
                        change("", "")
                        sheet = ""
                    }
                    val models =
                        catalog.models.filter {
                            (!it.hidden || it.model == model) &&
                                (it.displayName.contains(query, true) ||
                                    it.model.contains(query, true))
                        }
                    models.forEach { item ->
                        ModelRow(
                            item.displayName.ifBlank { item.model },
                            item.description,
                            item.model == model,
                            enabled,
                        ) {
                            change(item.model, "")
                            sheet = ""
                        }
                    }
                    if (models.isEmpty() && query.isNotBlank()) Text("Aucun modèle trouvé.")
                    if (model.isNotBlank() && catalog.models.none { it.model == model })
                        Text("Modèle enregistré : $model (absent du catalogue)")
                    if (catalog.models.isEmpty())
                        Field("Nom du modèle", model, { change(it, "") }, keyboardOptions = InputKeyboards.Literal)
                } else {
                    ReasoningControl(selected, reasoning, inheritedEffort, enabled) {
                        change(model, it)
                    }
                }
                if (catalog.stale || catalog.error.isNotBlank())
                    Text(
                        catalog.error.ifBlank {
                            "Catalogue enregistré ; actualisation en attente."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
}

@Composable
private fun ModelRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    choose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = choose,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description.isNotBlank())
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        RadioButton(selected, onClick = null, enabled = enabled)
    }
}

@Composable
internal fun ReasoningControl(
    model: CodexModel?,
    value: String,
    default: String,
    enabled: Boolean,
    change: (String) -> Unit,
) {
    val order = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    val efforts =
        model
            ?.supportedReasoningEfforts
            .orEmpty()
            .distinctBy { it.reasoningEffort }
            .sortedBy {
                order.indexOf(it.reasoningEffort).let { rank ->
                    if (rank < 0) Int.MAX_VALUE else rank
                }
            }
    val effective = value.ifBlank { default }
    val index = efforts.indexOfFirst { it.reasoningEffort == effective }
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Text(
        "Effort : ${effortLabel(effective)}",
        Modifier.alignCenter(),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (efforts.size > 1) {
        val primary = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Slider(
            value = index.coerceAtLeast(0).toFloat(),
            onValueChange = { position ->
                val next = efforts[position.roundToInt().coerceIn(efforts.indices)].reasoningEffort
                if (next != effective || value.isBlank()) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    change(next)
                }
            },
            enabled = enabled,
            valueRange = 0f..efforts.lastIndex.toFloat(),
            steps = efforts.size - 2,
            modifier =
                Modifier.fillMaxWidth().height(72.dp).testTag("reasoning-slider").semantics {
                    contentDescription = "Effort de raisonnement"
                    stateDescription =
                        if (index < 0) "Choisir un niveau" else effortLabel(effective)
                },
            thumb = { Box(Modifier.size(48.dp).background(Color.White, CircleShape)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                    // Slider lays out the track between the centers of the 48 dp thumb.
                    // Extend its capsule beneath the thumb at both ends so dots and stops align.
                    val radius = size.height / 2
                    val inset = 24.dp.toPx()
                    val fraction = state.value / efforts.lastIndex
                    scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f) {
                        drawRoundRect(
                            track,
                            topLeft = Offset(-inset, 0f),
                            size = Size(size.width + 2 * inset, size.height),
                            cornerRadius = CornerRadius(radius),
                        )
                        drawRoundRect(
                            primary,
                            topLeft = Offset(-inset, 0f),
                            size = Size(2 * inset + size.width * fraction, size.height),
                            cornerRadius = CornerRadius(radius),
                        )
                        efforts.indices.forEach { step ->
                            drawCircle(
                                if (step <= state.value) Color.White.copy(alpha = 0.3f)
                                else Color.Gray,
                                4.dp.toPx(),
                                Offset(size.width * step / efforts.lastIndex, radius),
                            )
                        }
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                effortLabel(efforts.first().reasoningEffort),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                effortLabel(efforts.last().reasoningEffort),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    } else if (efforts.size == 1) {
        TextButton(onClick = { change(efforts.single().reasoningEffort) }, enabled = enabled) {
            Text(effortLabel(efforts.single().reasoningEffort))
        }
    }
    if (index >= 0 && efforts[index].description.isNotBlank())
        Text(efforts[index].description, style = MaterialTheme.typography.bodySmall)
    if (model == null)
        Text("Catalogue indisponible pour ce modèle. Le réglage enregistré est conservé.")
    else if (efforts.isEmpty()) Text("Ce modèle ne propose pas de réglage du raisonnement.")
    else if (index < 0 && value.isNotBlank())
        Text(
            "Ce niveau n’est pas proposé par le modèle. Choisissez un niveau disponible ou le réglage par défaut.",
            color = MaterialTheme.colorScheme.error,
        )
    TextButton(
        onClick = { change("") },
        enabled = enabled,
        modifier = Modifier.testTag("reasoning-default"),
    ) {
        Text(
            "${if (value.isBlank()) "✓ " else ""}Par défaut${if (default.isNotBlank()) " · ${effortLabel(default)}" else ""}"
        )
    }
}

private fun Modifier.alignCenter() = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
