package ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun WorkingTab(
    busy: Boolean,
    stepLabel: String,
    isComplete: Boolean,
    activeWorkId: String?,
    reviewLyricsText: String,
    onReviewLyricsTextChange: (String) -> Unit,
    logs: List<String>,
    logFile: File,
    outputPath: String?,
    canRender: Boolean,
    canResync: Boolean,
    onRender: () -> Unit,
    onResyncLyrics: () -> Unit,
    onOpenWorkFolder: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenLogFile: () -> Unit,
) {
    val logScrollState = rememberScrollState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(188.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> KaraokeLoadingAnimation(stepLabel = stepLabel)
                isComplete -> Text(
                    text = "Done — $stepLabel",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> Text(
                    text = stepLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        OutlinedTextField(
            value = reviewLyricsText,
            onValueChange = onReviewLyricsTextChange,
            label = { Text("Inferred lyrics (edit before render)") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            enabled = !busy,
            supportingText = {
                val workLabel = activeWorkId?.let { "Work: $it" }
                    ?: "Lyrics appear here after prepare or loading a past run"
                Text(workLabel)
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRender, enabled = canRender) {
                Icon(Icons.Default.VideoFile, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Render video")
            }
            if (activeWorkId != null) {
                OutlinedButton(onClick = onResyncLyrics, enabled = canResync) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Re-sync lyrics")
                }
                OutlinedButton(onClick = onOpenWorkFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Work folder")
                }
            }
            if (!outputPath.isNullOrBlank()) {
                OutlinedButton(onClick = onOpenOutput) {
                    Text("Open output")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onOpenLogFile) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Open log file")
            }
        }
        Text(
            text = logFile.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 140.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                )
                .verticalScroll(logScrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (logs.isEmpty()) {
                Text(
                    "Log output will appear here…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
