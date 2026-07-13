package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import api.HealthResponse
import api.LanguageOption
import api.WorkRunSummary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupTab(
    modelsReady: Boolean,
    inferenceLabel: String,
    health: HealthResponse?,
    busy: Boolean,
    pastRuns: List<WorkRunSummary>,
    selectedPastRunLabel: String,
    pastRunsExpanded: Boolean,
    onPastRunsExpandedChange: (Boolean) -> Unit,
    onPastRunSelected: (String) -> Unit,
    youtubeUrl: String,
    onYoutubeUrlChange: (String) -> Unit,
    selectedLanguageName: String,
    languageExpanded: Boolean,
    onLanguageExpandedChange: (Boolean) -> Unit,
    languages: List<LanguageOption>,
    selectedLanguageCode: String,
    onLanguageSelected: (String) -> Unit,
    hintLyricsText: String,
    onHintLyricsTextChange: (String) -> Unit,
    onPickLyricsFile: () -> Unit,
    vocalBlendPercent: Float,
    onVocalBlendPercentChange: (Float) -> Unit,
    bgColor: String,
    onBgColorChange: (String) -> Unit,
    outputDir: String,
    onOutputDirChange: (String) -> Unit,
    onPickOutputDir: () -> Unit,
    onDownloadModels: () -> Unit,
    onPrepare: () -> Unit,
    canPrepare: Boolean,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (modelsReady) {
                    "Models: ready ($inferenceLabel)"
                } else {
                    "Models: missing (${health?.missing?.size ?: 0})"
                },
                color = if (modelsReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (!modelsReady) {
                OutlinedButton(onClick = onDownloadModels, enabled = !busy) {
                    Text("Download models")
                }
            }
        }

        if (pastRuns.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = pastRunsExpanded,
                onExpandedChange = { if (!busy) onPastRunsExpandedChange(it) },
            ) {
                OutlinedTextField(
                    value = selectedPastRunLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Past runs") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pastRunsExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    supportingText = { Text("Load settings and lyrics from a previous run") },
                )
                ExposedDropdownMenu(
                    expanded = pastRunsExpanded,
                    onDismissRequest = { onPastRunsExpandedChange(false) },
                ) {
                    pastRuns.forEach { run ->
                        DropdownMenuItem(
                            text = { Text(pastRunLabel(run)) },
                            onClick = {
                                onPastRunsExpandedChange(false)
                                onPastRunSelected(run.workId)
                            },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = youtubeUrl,
            onValueChange = onYoutubeUrlChange,
            label = { Text("YouTube URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )

        ExposedDropdownMenuBox(
            expanded = languageExpanded,
            onExpandedChange = { if (!busy) onLanguageExpandedChange(it) },
        ) {
            OutlinedTextField(
                value = selectedLanguageName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Transcription language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                supportingText = { Text("Whisper-supported languages; auto-detect if unsure") },
            )
            ExposedDropdownMenu(
                expanded = languageExpanded,
                onDismissRequest = { onLanguageExpandedChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("Auto-detect") },
                    onClick = {
                        onLanguageSelected("")
                        onLanguageExpandedChange(false)
                    },
                )
                languages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.name) },
                        onClick = {
                            onLanguageSelected(lang.code)
                            onLanguageExpandedChange(false)
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = hintLyricsText,
            onValueChange = onHintLyricsTextChange,
            label = { Text("Lyrics hint (optional)") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            enabled = !busy,
            supportingText = { Text("Optional guide for transcription") },
        )

        OutlinedButton(onClick = onPickLyricsFile, enabled = !busy) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Load lyrics hint file")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Vocal blend")
                    Text("${vocalBlendPercent.roundToInt()}%")
                }
                Slider(
                    value = vocalBlendPercent,
                    onValueChange = onVocalBlendPercentChange,
                    valueRange = 0f..100f,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "0% = instrumental only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ColorPickerField(
                label = "Background",
                hexColor = bgColor,
                onHexColorChange = onBgColorChange,
                enabled = !busy,
                modifier = Modifier.width(160.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = outputDir,
                onValueChange = onOutputDirChange,
                label = { Text("Output folder") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedButton(
                onClick = onPickOutputDir,
                modifier = Modifier.padding(top = 8.dp),
                enabled = !busy,
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Browse")
            }
        }

        Button(
            onClick = onPrepare,
            enabled = canPrepare,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Prepare audio & lyrics")
        }
    }
}

private fun pastRunLabel(run: WorkRunSummary): String {
    val title = run.title.ifBlank { run.workId }
    val artist = run.artist?.let { " — $it" }.orEmpty()
    val flags = buildList {
        if (run.lyricsReady) add("lyrics")
        if (run.hasVideo) add("video")
    }.joinToString(", ")
    val suffix = if (flags.isNotBlank()) " [$flags]" else ""
    return "$title$artist$suffix"
}
