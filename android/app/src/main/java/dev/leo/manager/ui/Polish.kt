@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.leo.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Small shared outline family, with native 48 dp targets and accessible labels.
internal object LeoIcons {
    private fun line(name: String, draw: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply {
                path(
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathBuilder = draw,
                )
            }
            .build()

    val Chat =
        line("Chat") {
            moveTo(5f, 4f)
            lineTo(19f, 4f)
            quadTo(21f, 4f, 21f, 6f)
            lineTo(21f, 16f)
            quadTo(21f, 18f, 19f, 18f)
            lineTo(8f, 18f)
            lineTo(3f, 21f)
            lineTo(3f, 6f)
            quadTo(3f, 4f, 5f, 4f)
        }
    val File =
        line("File") {
            moveTo(14f, 3f)
            lineTo(5f, 3f)
            lineTo(5f, 21f)
            lineTo(19f, 21f)
            lineTo(19f, 8f)
            lineTo(14f, 3f)
            lineTo(14f, 8f)
            lineTo(19f, 8f)
            moveTo(9f, 13f)
            lineTo(15f, 13f)
            moveTo(9f, 17f)
            lineTo(13f, 17f)
        }
    val Layers =
        line("Layers") {
            moveTo(3f, 7f)
            lineTo(12f, 3f)
            lineTo(21f, 7f)
            lineTo(12f, 11f)
            close()
            moveTo(3f, 12f)
            lineTo(12f, 16f)
            lineTo(21f, 12f)
            moveTo(3f, 17f)
            lineTo(12f, 21f)
            lineTo(21f, 17f)
        }
    val Down =
        line("Down") {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    val Right =
        line("Right") {
            moveTo(9f, 6f)
            lineTo(15f, 12f)
            lineTo(9f, 18f)
        }
    val Up =
        line("Send") {
            moveTo(12f, 19f)
            lineTo(12f, 5f)
            moveTo(6f, 11f)
            lineTo(12f, 5f)
            lineTo(18f, 11f)
        }
    val Bottom =
        line("Latest") {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(6f, 13f)
            lineTo(12f, 19f)
            lineTo(18f, 13f)
        }
    val Pause =
        line("Pause") {
            moveTo(8f, 5f)
            lineTo(8f, 19f)
            moveTo(16f, 5f)
            lineTo(16f, 19f)
        }
    val Stop =
        line("Stop") {
            moveTo(6f, 6f)
            lineTo(18f, 6f)
            lineTo(18f, 18f)
            lineTo(6f, 18f)
            close()
        }
    val Tune =
        line("Tune") {
            moveTo(4f, 7f)
            lineTo(8f, 7f)
            moveTo(12f, 7f)
            lineTo(20f, 7f)
            moveTo(10f, 4f)
            lineTo(10f, 10f)
            moveTo(4f, 17f)
            lineTo(12f, 17f)
            moveTo(16f, 17f)
            lineTo(20f, 17f)
            moveTo(14f, 14f)
            lineTo(14f, 20f)
        }
    val Terminal =
        line("Terminal") {
            moveTo(4f, 6f)
            lineTo(10f, 12f)
            lineTo(4f, 18f)
            moveTo(13f, 18f)
            lineTo(20f, 18f)
        }
    val Download =
        line("Download") {
            moveTo(12f, 3f)
            lineTo(12f, 15f)
            moveTo(7f, 10f)
            lineTo(12f, 15f)
            lineTo(17f, 10f)
            moveTo(4f, 16f)
            lineTo(4f, 21f)
            lineTo(20f, 21f)
            lineTo(20f, 16f)
        }
    val External =
        line("External") {
            moveTo(14f, 3f)
            lineTo(21f, 3f)
            lineTo(21f, 10f)
            moveTo(21f, 3f)
            lineTo(10f, 14f)
            moveTo(10f, 5f)
            lineTo(4f, 5f)
            lineTo(4f, 21f)
            lineTo(19f, 21f)
            lineTo(19f, 15f)
        }
}

@Composable
internal fun ActionIcon(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label, Modifier.size(22.dp)) }
    }
}

@Composable
internal fun SearchField(label: String, value: String, change: (String) -> Unit) {
    TextField(
        value,
        change,
        Modifier.fillMaxWidth(),
        placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    )
}

@Composable
internal fun DetailHeader(
    title: String,
    subtitle: String,
    back: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank())
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
        },
        navigationIcon = {
            ActionIcon(
                "Retour",
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                onClick = back,
            )
        },
        actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
    )
}
