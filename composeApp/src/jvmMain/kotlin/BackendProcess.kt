import java.io.File
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

data class BackendProcess(
    val baseUrl: String,
    private val process: Process,
    val projectRoot: Path,
    private val logFile: Path,
) {
    val pythonPath: String = projectRoot.resolve("backend/.venv/bin/python").absolutePathString()
    val backendDir: String = projectRoot.resolve("backend").absolutePathString()

    fun isAlive(): Boolean = process.isAlive()

    fun logFilePath(): String = logFile.absolutePathString()

    fun stop() {
        if (process.isAlive) {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}

private fun findProjectRoot(): Path {
    var current = Path.of(System.getProperty("user.dir"))
    for (i in 0 until 6) {
        if (current.resolve("backend").resolve("main.py").toFile().exists()) {
            return current
        }
        current = current.parent ?: return Path.of(System.getProperty("user.dir"))
    }
    return Path.of(System.getProperty("user.dir"))
}

private fun findFreePort(): Int {
    ServerSocket(0).use { socket ->
        return socket.localPort
    }
}

fun startBackendProcess(): BackendProcess {
    val projectRoot = findProjectRoot()
    val python = projectRoot.resolve("backend/.venv/bin/python")
    if (!python.toFile().exists()) {
        throw IllegalStateException(
            "Python venv not found at ${python.absolutePathString()}. Run ./setup.sh first.",
        )
    }

    val port = findFreePort()
    val backendDir = projectRoot.resolve("backend")
    val cudaShims = projectRoot.resolve("backend/cuda_shims").absolutePathString()
    val cudaLibPaths = listOf(
        cudaShims,
        "/usr/local/cuda/lib64",
        "/usr/local/cuda-13/lib64",
        "/usr/local/cuda-13.2/lib64",
        "/usr/lib/wsl/lib",
    )
    val existingLd = System.getenv("LD_LIBRARY_PATH").orEmpty()
    val ldLibraryPath = (cudaLibPaths + existingLd.split(":").filter { it.isNotBlank() })
        .distinct()
        .joinToString(":")

    val process = ProcessBuilder(
        python.absolutePathString(),
        "-m",
        "uvicorn",
        "karaoke_backend.server:app",
        "--host",
        "127.0.0.1",
        "--port",
        port.toString(),
    )
        .directory(backendDir.toFile())
        .redirectErrorStream(true)
        .apply {
            environment()["LD_LIBRARY_PATH"] = ldLibraryPath
        }
        .start()

    val logDir = projectRoot.resolve("logs")
    logDir.toFile().mkdirs()
    val logFile = logDir.resolve("backend.log")
    logFile.toFile().writeText("")
    Thread {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                logFile.toFile().appendText("$line\n")
            }
        }
    }.apply {
        isDaemon = true
        name = "backend-log-drain"
        start()
    }

    val baseUrl = "http://127.0.0.1:$port"
    waitForBackend(baseUrl)
    return BackendProcess(baseUrl = baseUrl, process = process, projectRoot = projectRoot, logFile = logFile)
}

private fun waitForBackend(baseUrl: String, timeoutSec: Long = 30) {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000
    var lastError: String? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("$baseUrl/health"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(2))
                .build()
            val response = java.net.http.HttpClient.newHttpClient().send(
                request,
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() == 200) {
                return
            }
            lastError = "HTTP ${response.statusCode()}"
        } catch (exc: Exception) {
            lastError = exc.message
        }
        Thread.sleep(300)
    }
    throw IllegalStateException("Backend did not become ready: $lastError")
}

fun defaultOutputDir(projectRoot: Path): String {
    val homeOutput = Path.of(System.getProperty("user.home"), "Videos", "karaoke-output")
    return homeOutput.absolutePathString()
}

fun openInFileManager(path: String) {
    val file = File(path)
    val target = if (file.isFile) file.parentFile ?: file else file
    val desktop = java.awt.Desktop.getDesktop()
    if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
        desktop.open(target)
    }
}
