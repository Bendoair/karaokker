package ui

import androidx.compose.ui.graphics.Color
import java.awt.Color as AwtColor

fun parseHexColor(hex: String, fallback: Color = Color(0xFF1A1C18)): Color {
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6) return fallback
    return runCatching {
        Color(
            red = cleaned.substring(0, 2).toInt(16) / 255f,
            green = cleaned.substring(2, 4).toInt(16) / 255f,
            blue = cleaned.substring(4, 6).toInt(16) / 255f,
        )
    }.getOrDefault(fallback)
}

fun Color.toHexString(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

fun Color.toAwtColor(): AwtColor = AwtColor(red, green, blue)

fun AwtColor.toComposeColor(): Color = Color(red / 255f, green / 255f, blue / 255f)
