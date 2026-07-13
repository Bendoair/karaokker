import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import theme.KaraokkerTheme

fun main() = application {
    val windowIcon = rememberAppIconPainter()

    var backendProcess by remember { mutableStateOf<BackendProcess?>(null) }

    LaunchedEffect(Unit) {
        backendProcess = startBackendProcess()
    }

    val process = backendProcess
    Window(
        onCloseRequest = {
            process?.stop()
            exitApplication()
        },
        title = "Karaokker",
        icon = windowIcon,
        state = rememberWindowState(width = 1200.dp, height = 920.dp),
    ) {
        KaraokkerTheme {
            if (process == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text("Starting backend…", modifier = Modifier.padding(top = 16.dp))
                }
            } else {
                App(backendProcess = process)
            }
        }
    }
}
