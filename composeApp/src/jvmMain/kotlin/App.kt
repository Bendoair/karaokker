import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import api.BackendClient
import api.HealthResponse
import api.PrepareJobRequest
import api.RenderWorkRequest
import api.ResyncLyricsRequest
import api.WorkRunSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.SetupTab
import ui.WorkingTab
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class AppTab(val label: String) {
    Setup("Setup"),
    Working("Working"),
}

private enum class UiStep(val label: String) {
    Idle("Ready"),
    Setup("Setting up models"),
    Download("Downloading"),
    Separate("Separating stems"),
    Lyrics("Syncing lyrics"),
    LyricsReady("Lyrics ready for review"),
    Render("Rendering video"),
    Complete("Complete"),
    Error("Error"),
}

private const val AUTO_LANGUAGE_CODE = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(backendProcess: BackendProcess) {
    AppContent(backendProcess)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(backendProcess: BackendProcess) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val client = remember(backendProcess.baseUrl) { BackendClient(backendProcess.baseUrl) }

    var selectedTab by remember { mutableStateOf(AppTab.Setup) }
    var youtubeUrl by remember { mutableStateOf("") }
    var hintLyricsText by remember { mutableStateOf("") }
    var reviewLyricsText by remember { mutableStateOf("") }
    var bgColor by remember { mutableStateOf("#1a1c18") }
    var vocalBlendPercent by remember { mutableStateOf(20f) }
    var selectedLanguageCode by remember { mutableStateOf(AUTO_LANGUAGE_CODE) }
    var languageExpanded by remember { mutableStateOf(false) }
    var pastRunsExpanded by remember { mutableStateOf(false) }
    var selectedPastRunId by remember { mutableStateOf<String?>(null) }
    var languages by remember { mutableStateOf(emptyList<api.LanguageOption>()) }
    var pastRuns by remember { mutableStateOf(emptyList<WorkRunSummary>()) }
    var activeWorkId by remember { mutableStateOf<String?>(null) }
    var workHasOriginal by remember { mutableStateOf(false) }
    var outputDir by remember {
        mutableStateOf(defaultOutputDir(backendProcess.projectRoot))
    }
    var health by remember { mutableStateOf<HealthResponse?>(null) }
    var currentStep by remember { mutableStateOf(UiStep.Idle) }
    var busy by remember { mutableStateOf(false) }
    var outputPath by remember { mutableStateOf<String?>(null) }
    val logs = remember { mutableStateListOf<String>() }
    val logFile = remember(backendProcess.projectRoot) {
        val logDir = backendProcess.projectRoot.resolve("logs").toFile()
        logDir.mkdirs()
        logDir.resolve("gui.log")
    }
    val logTimestampFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    fun appendLog(message: String) {
        val line = "[${LocalDateTime.now().format(logTimestampFormatter)}] $message"
        logs.add(line)
        runCatching {
            logFile.appendText("$line\n")
        }
        if (logs.size > 1000) {
            logs.removeAt(0)
        }
    }

    suspend fun refreshHealth() {
        runCatching { client.health() }
            .onSuccess { health = it }
            .onFailure { appendLog("Health check failed: ${it.message}") }
    }

    suspend fun refreshPastRuns() {
        runCatching { client.listWork() }
            .onSuccess { pastRuns = it }
            .onFailure { appendLog("Failed to list past runs: ${it.message}") }
    }

    suspend fun loadPastRun(workId: String) {
        runCatching { client.getWork(workId) }
            .onSuccess { detail ->
                activeWorkId = detail.workId
                workHasOriginal = detail.files?.get("original") == true
                reviewLyricsText = detail.lyricsText
                selectedPastRunId = workId
                currentStep = if (detail.lyricsText.isNotBlank()) UiStep.LyricsReady else currentStep
                selectedTab = AppTab.Working

                val meta = detail.meta.orEmpty()
                (meta["youtube_url"] as? String)?.let { youtubeUrl = it }
                (meta["bg_color"] as? String)?.let { bgColor = it }
                (meta["language"] as? String)?.let { selectedLanguageCode = it }
                (meta["output_dir"] as? String)?.let { outputDir = it }
                (meta["vocal_blend_percent"] as? Number)?.let {
                    vocalBlendPercent = it.toFloat()
                }
                appendLog("Loaded past run $workId (${detail.lyricsText.lines().size} lyric lines)")
            }
            .onFailure {
                appendLog("Failed to load run: ${it.message}")
                snackbar.showSnackbar(it.message ?: "Failed to load run")
            }
    }

    LaunchedEffect(client) {
        refreshHealth()
        runCatching { client.getLanguages() }
            .onSuccess { languages = it }
            .onFailure { appendLog("Failed to load languages: ${it.message}") }
        refreshPastRuns()
    }

    fun mapBackendStep(step: String?): UiStep = when (step) {
        "download" -> UiStep.Download
        "separate" -> UiStep.Separate
        "lyrics" -> UiStep.Lyrics
        "lyrics_ready" -> UiStep.LyricsReady
        "render" -> UiStep.Render
        "complete" -> UiStep.Complete
        "error" -> UiStep.Error
        "demucs", "whisper", "setup" -> UiStep.Setup
        else -> currentStep
    }

    fun pickLyricsFile() {
        val dialog = FileDialog(null as Frame?, "Select lyrics file", FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file
        val directory = dialog.directory
        if (file != null && directory != null) {
            hintLyricsText = File(directory, file).readText()
            appendLog("Loaded lyrics hint from ${File(directory, file).absolutePath}")
        }
    }

    fun pickOutputDir() {
        val chooser = javax.swing.JFileChooser()
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Select output folder"
        if (outputDir.isNotBlank()) {
            chooser.currentDirectory = File(outputDir)
        }
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            outputDir = chooser.selectedFile.absolutePath
            appendLog("Output folder set to $outputDir")
        }
    }

    fun streamJob(jobId: String, stopOnLyricsReady: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                client.streamJobEvents(
                    jobId = jobId,
                    stopOnLyricsReady = stopOnLyricsReady,
                    onEvent = { event ->
                        scope.launch {
                            appendLog("[${event.step}] ${event.message}")
                            currentStep = mapBackendStep(event.step)
                            if (!event.outputPath.isNullOrBlank()) {
                                outputPath = event.outputPath
                            }
                            if (event.step == "lyrics_ready") {
                                event.workId?.let { activeWorkId = it }
                                event.lyricsText?.let { reviewLyricsText = it }
                                selectedTab = AppTab.Working
                            }
                        }
                    },
                    onComplete = { error ->
                        scope.launch {
                            if (error != null) {
                                appendLog("Job stream lost: ${error.message}")
                                if (!backendProcess.isAlive()) {
                                    appendLog(
                                        "Backend process crashed — restart the app. " +
                                            "See ${backendProcess.logFilePath()}",
                                    )
                                    currentStep = UiStep.Error
                                } else {
                                    val status = runCatching { client.getJob(jobId) }.getOrNull()
                                    when (status?.status) {
                                        "failed" -> {
                                            appendLog("Job failed: ${status.error ?: status.message}")
                                            currentStep = UiStep.Error
                                        }
                                        "lyrics_ready", "completed" -> {
                                            status.workId?.let { activeWorkId = it }
                                            outputPath = status.outputPath ?: outputPath
                                            currentStep = if (status.status == "completed") {
                                                UiStep.Complete
                                            } else {
                                                UiStep.LyricsReady
                                            }
                                            appendLog(status.message)
                                            refreshPastRuns()
                                            selectedTab = AppTab.Working
                                        }
                                        else -> currentStep = UiStep.Error
                                    }
                                }
                            } else {
                                val status = runCatching { client.getJob(jobId) }.getOrNull()
                                status?.workId?.let { activeWorkId = it }
                                outputPath = status?.outputPath ?: outputPath
                                currentStep = when (status?.status) {
                                    "completed" -> UiStep.Complete
                                    "lyrics_ready" -> UiStep.LyricsReady
                                    else -> currentStep
                                }
                                appendLog(status?.message ?: "Job finished")
                                if (status?.status in setOf("lyrics_ready", "completed")) {
                                    refreshPastRuns()
                                    selectedTab = AppTab.Working
                                }
                            }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    fun setupModels() {
        scope.launch {
            busy = true
            currentStep = UiStep.Setup
            selectedTab = AppTab.Working
            appendLog("Starting model setup...")
            withContext(Dispatchers.IO) {
                client.streamSetupModels(
                    onEvent = { event ->
                        scope.launch {
                            appendLog("[${event.step}] ${event.message}")
                            currentStep = mapBackendStep(event.step)
                        }
                    },
                    onComplete = { error ->
                        scope.launch {
                            if (error != null) {
                                appendLog("Model setup failed: ${error.message}")
                                currentStep = UiStep.Error
                            } else {
                                appendLog("Model setup finished")
                            }
                            refreshHealth()
                            busy = false
                        }
                    },
                )
            }
        }
    }

    fun prepare() {
        scope.launch {
            busy = true
            outputPath = null
            activeWorkId = null
            reviewLyricsText = ""
            currentStep = UiStep.Download
            selectedTab = AppTab.Working
            val langLabel = selectedLanguageLabel(selectedLanguageCode, languages)
            appendLog("Preparing audio & lyrics (language: $langLabel, vocal blend ${vocalBlendPercent.roundToInt()}%)...")

            val createResult = runCatching {
                client.prepareJob(
                    PrepareJobRequest(
                        youtubeUrl = youtubeUrl.trim(),
                        lyricsText = hintLyricsText.trim().ifBlank { null },
                        bgColor = bgColor.trim(),
                        outputDir = outputDir.trim(),
                        vocalBlendPercent = vocalBlendPercent,
                        language = selectedLanguageCode.ifBlank { null },
                    ),
                )
            }

            val created = createResult.getOrElse {
                appendLog("Failed to start prepare: ${it.message}")
                currentStep = UiStep.Error
                busy = false
                snackbar.showSnackbar(it.message ?: "Failed to start prepare")
                return@launch
            }

            created.workId?.let { activeWorkId = it }
            workHasOriginal = true
            appendLog("Prepare job ${created.jobId} started (work ${created.workId})")
            streamJob(jobId = created.jobId, stopOnLyricsReady = true)
        }
    }

    fun render() {
        val workId = activeWorkId
        if (workId.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("No work folder — prepare or load a past run first") }
            return
        }
        if (reviewLyricsText.trim().isBlank()) {
            scope.launch { snackbar.showSnackbar("Lyrics are empty") }
            return
        }

        scope.launch {
            busy = true
            outputPath = null
            currentStep = UiStep.Render
            selectedTab = AppTab.Working
            appendLog("Rendering video for work $workId...")

            val createResult = runCatching {
                client.renderWork(
                    workId = workId,
                    requestBody = RenderWorkRequest(
                        lyricsText = reviewLyricsText.trim(),
                        bgColor = bgColor.trim(),
                        outputDir = outputDir.trim(),
                    ),
                )
            }

            val created = createResult.getOrElse {
                appendLog("Failed to start render: ${it.message}")
                currentStep = UiStep.Error
                busy = false
                snackbar.showSnackbar(it.message ?: "Failed to start render")
                return@launch
            }

            appendLog("Render job ${created.jobId} started")
            streamJob(jobId = created.jobId, stopOnLyricsReady = false)
        }
    }

    fun resyncLyrics() {
        val workId = activeWorkId
        if (workId.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("No work folder — load a past run first") }
            return
        }
        if (!workHasOriginal) {
            scope.launch { snackbar.showSnackbar("original.mp3 missing — run full prepare first") }
            return
        }

        scope.launch {
            busy = true
            outputPath = null
            currentStep = UiStep.Lyrics
            selectedTab = AppTab.Working
            appendLog("Re-syncing lyrics for work $workId (skipping download & separation)...")

            val createResult = runCatching {
                client.resyncLyrics(
                    workId = workId,
                    requestBody = ResyncLyricsRequest(
                        lyricsText = reviewLyricsText.trim().ifBlank { null },
                    ),
                )
            }

            val created = createResult.getOrElse {
                appendLog("Failed to start re-sync: ${it.message}")
                currentStep = UiStep.Error
                busy = false
                snackbar.showSnackbar(it.message ?: "Failed to start re-sync")
                return@launch
            }

            appendLog("Re-sync job ${created.jobId} started")
            streamJob(jobId = created.jobId, stopOnLyricsReady = true)
        }
    }

    val modelsReady = health?.modelsReady == true
    val inferenceLabel = run {
        val h = health
        when {
            h == null -> "unknown"
            h.whisperDevice == "cuda" && h.demucsProviders == "cuda" -> "GPU"
            h.whisperDevice == "cuda" -> "GPU (Whisper); demucs CPU"
            h.cudaAvailable -> "GPU"
            else -> "CPU"
        }
    }
    val selectedLanguageName = selectedLanguageLabel(selectedLanguageCode, languages)
    val selectedPastRunLabel = pastRuns.find { it.workId == selectedPastRunId }?.let { run ->
        pastRunLabel(run)
    } ?: "Select a past run…"
    val canRender = !busy && modelsReady && activeWorkId != null && reviewLyricsText.isNotBlank()
    val canResync = !busy && modelsReady && activeWorkId != null && workHasOriginal
    val canPrepare = !busy && modelsReady && youtubeUrl.isNotBlank()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Karaokker") },
                actions = {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                refreshHealth()
                                refreshPastRuns()
                            }
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Refresh", modifier = Modifier.padding(start = 6.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                AppTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }

            when (selectedTab) {
                AppTab.Setup -> SetupTab(
                    modelsReady = modelsReady,
                    inferenceLabel = inferenceLabel,
                    health = health,
                    busy = busy,
                    pastRuns = pastRuns,
                    selectedPastRunLabel = selectedPastRunLabel,
                    pastRunsExpanded = pastRunsExpanded,
                    onPastRunsExpandedChange = { pastRunsExpanded = it },
                    onPastRunSelected = { workId -> scope.launch { loadPastRun(workId) } },
                    youtubeUrl = youtubeUrl,
                    onYoutubeUrlChange = { youtubeUrl = it },
                    selectedLanguageName = selectedLanguageName,
                    languageExpanded = languageExpanded,
                    onLanguageExpandedChange = { languageExpanded = it },
                    languages = languages,
                    selectedLanguageCode = selectedLanguageCode,
                    onLanguageSelected = { selectedLanguageCode = it },
                    hintLyricsText = hintLyricsText,
                    onHintLyricsTextChange = { hintLyricsText = it },
                    onPickLyricsFile = ::pickLyricsFile,
                    vocalBlendPercent = vocalBlendPercent,
                    onVocalBlendPercentChange = { vocalBlendPercent = it },
                    bgColor = bgColor,
                    onBgColorChange = { bgColor = it },
                    outputDir = outputDir,
                    onOutputDirChange = { outputDir = it },
                    onPickOutputDir = ::pickOutputDir,
                    onDownloadModels = ::setupModels,
                    onPrepare = ::prepare,
                    canPrepare = canPrepare,
                )

                AppTab.Working -> WorkingTab(
                    busy = busy,
                    stepLabel = currentStep.label,
                    isComplete = currentStep == UiStep.Complete,
                    activeWorkId = activeWorkId,
                    reviewLyricsText = reviewLyricsText,
                    onReviewLyricsTextChange = { reviewLyricsText = it },
                    logs = logs,
                    logFile = logFile,
                    outputPath = outputPath,
                    canRender = canRender,
                    canResync = canResync,
                    onRender = ::render,
                    onResyncLyrics = ::resyncLyrics,
                    onOpenWorkFolder = {
                        activeWorkId?.let { id ->
                            openInFileManager(
                                backendProcess.projectRoot.resolve("work").resolve(id).toString(),
                            )
                        }
                    },
                    onOpenOutput = { outputPath?.let { openInFileManager(it) } },
                    onOpenLogFile = { openInFileManager(logFile.absolutePath) },
                )
            }
        }
    }
}

private fun selectedLanguageLabel(code: String, languages: List<api.LanguageOption>): String {
    if (code.isBlank()) return "Auto-detect"
    return languages.find { it.code == code }?.name ?: code
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
