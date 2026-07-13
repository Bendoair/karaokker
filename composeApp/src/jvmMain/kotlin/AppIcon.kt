import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private const val ICON_RESOURCE = "icon.png"
private val iconLoader = object {}.javaClass

fun loadAppIconBitmap(): ImageBitmap? {
    val bytes = iconLoader.classLoader.getResourceAsStream(ICON_RESOURCE)?.use { it.readBytes() }
        ?: return null
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

@Composable
fun rememberAppIconPainter(): Painter? = remember {
    loadAppIconBitmap()?.let { BitmapPainter(it) }
}
