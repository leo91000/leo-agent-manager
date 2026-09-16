@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.leo.manager.data.*
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

fun date(value: Long?): String =
    value
        ?.takeIf { it > 0 }
        ?.let {
            DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
        } ?: "—"

fun duration(run: Run): String =
    run.startedAt?.let {
        val seconds = ((run.finishedAt ?: System.currentTimeMillis()) - it).coerceAtLeast(0) / 1000
        "${seconds / 60} min ${seconds % 60} s"
    } ?: "—"

@Composable
inline fun <reified T : Any> rememberForm(initial: T): MutableState<T> =
    rememberSaveable(
        stateSaver =
            Saver(
                save = { wireJson.encodeToString(it) },
                restore = { wireJson.decodeFromString<T>(it) },
            )
    ) {
        mutableStateOf(initial)
    }

@Composable
fun Poll(key: Any?, interval: Long = 3000, action: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    LaunchedEffect(owner, key) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                latest()
                delay(interval)
            }
        }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun Heading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun Empty(
    title: String = "Rien pour le moment",
    text: String = "Les éléments de votre espace apparaîtront ici.",
) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorNotice(message: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = dismiss) { Text("Fermer") }
        }
    }
}

internal fun statusLabel(value: String): String =
    when (value) {
        "running" -> "En cours"
        "queued" -> "En attente"
        "succeeded" -> "Terminée"
        "failed" -> "Échec"
        "cancelled" -> "Annulée"
        "interrupted" -> "Interrompue"
        else -> value
    }

@Composable
fun Status(value: String) {
    val label = statusLabel(value)
    val bad = value in setOf("failed", "interrupted")
    Surface(
        color =
            if (bad) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    lines: Int = 1,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = lines,
        maxLines = if (lines == 1) 1 else 20,
        singleLine = lines == 1,
        enabled = enabled,
    )
}

@Composable
fun Choice(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(choices.find { it.first == selected }?.second ?: "Choisir…")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onChange(key)
                    },
                )
            }
        }
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun Editor(
    title: String,
    busy: Boolean,
    error: String?,
    close: () -> Unit,
    save: () -> Unit,
    valid: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) close() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = close, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fermer")
                        }
                    },
                    actions = {
                        TextButton(onClick = save, enabled = valid && !busy) { Text("Enregistrer") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                content()
            }
        }
    }
}

@Composable
fun Confirm(
    title: String,
    description: String,
    busy: Boolean,
    error: String?,
    dismiss: () -> Unit,
    action: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(description)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = action, enabled = !busy) { Text("Confirmer") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Annuler") } },
    )
}

@Composable
fun Markdown(content: String) {
    val openArtifact by rememberUpdatedState(LocalArtifactLinks.current)
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val markwon =
        remember(context) {
            Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(
                    object : io.noties.markwon.AbstractMarkwonPlugin() {
                        override fun configureConfiguration(
                            builder: io.noties.markwon.MarkwonConfiguration.Builder
                        ) {
                            builder.linkResolver { view, link ->
                                if (openArtifact(link)) return@linkResolver
                                val uri = link.toUri()
                                if (uri.scheme in setOf("https", "http") && uri.host != null) {
                                    runCatching {
                                        CustomTabsIntent.Builder()
                                            .build()
                                            .launchUrl(view.context, uri)
                                    }.onFailure {
                                        android.widget.Toast.makeText(
                                            view.context,
                                            "Aucune application disponible pour ouvrir ce lien.",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                )
                .build()
        }
    val rendering = LocalMarkdownRendering.current
    var initial by remember(markwon, rendering) { mutableStateOf(true) }
    DisposableEffect(markwon, rendering) {
        rendering?.begin()
        onDispose { if (initial) rendering?.end() }
    }
    val currentContent by rememberUpdatedState(content)
    var blocks by remember(markwon) { mutableStateOf(emptyList<MarkdownBlock>()) }
    LaunchedEffect(markwon, rendering) {
        val renderer = MarkdownBlocks(markwon)
        // Keep displaying the last complete render while working. Conflation applies
        // to whole texts, after the stream accumulator has accepted every delta.
        snapshotFlow { currentContent }.conflate().collect { text ->
            blocks = withContext(Dispatchers.Default) { renderer.render(text) }
            withFrameNanos { }
            withFrameNanos { }
            if (initial) {
                initial = false
                rendering?.end()
            }
            rendering?.changed()
            delay(80)
        }
    }
    val copyText = remember { { currentContent } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index) { MarkdownBlockView(markwon, block, color, linkColor, copyText) }
        }
    }
}

@Composable
private fun MarkdownBlockView(markwon: Markwon, block: MarkdownBlock, color: Int, linkColor: Int, copyText: () -> String) {
    AndroidView(
        factory = { MarkdownTextView(it, copyText) },
        modifier = Modifier.fillMaxWidth(),
        update = {
            it.setTextColor(color)
            it.setLinkTextColor(linkColor)
            it.bind(markwon, block)
        },
    )
}

@Composable
fun Code(value: String) {
    SelectionContainer {
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ShareButton(text: String) {
    val context = LocalContext.current
    ActionIcon(
        "Partager",
        Icons.Default.Share,
        onClick = {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Partager",
                )
            )
        },
    )
}

@Composable
fun ExternalButton(label: String, url: String) {
    val context = LocalContext.current
    val uri = url.toUri()
    if (uri.scheme in setOf("https", "http") && uri.host != null) {
        OutlinedButton(
            onClick = { runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) } }
        ) {
            Text(label)
        }
    }
}

@Composable
fun CopyButton(label: String, value: String, sensitive: Boolean = false) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    TextButton(
        onClick = {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            val clip = android.content.ClipData.newPlainText(label, value)
            if (sensitive)
                clip.description.extras =
                    android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
            clipboard.setPrimaryClip(clip)
            copied = true
        }
    ) {
        Text(if (copied) "Copié" else label)
    }
}
