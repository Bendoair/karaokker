package ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun KaraokeLoadingAnimation(
    stepLabel: String,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "karaoke-loading")
    val bounce by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bounce",
    )
    val glow by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PulsingDot(color = MaterialTheme.colorScheme.secondary.copy(alpha = glow))
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            translationY = -bounce * 6f
                        },
                )
            }
            PulsingDot(color = MaterialTheme.colorScheme.tertiary.copy(alpha = glow))
        }

        EqualizerBars(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 24.dp),
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.primary,
            ),
        )

        Text(
            text = stepLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Warming up the stage…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulsingDot(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

@Composable
private fun EqualizerBars(
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "eq")
    val bar0 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b0",
    )
    val bar1 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(
            tween(620, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(80),
        ),
        label = "b1",
    )
    val bar2 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(
            tween(480, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(160),
        ),
        label = "b2",
    )
    val bar3 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(
            tween(700, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(40),
        ),
        label = "b3",
    )
    val bar4 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(
            tween(560, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(120),
        ),
        label = "b4",
    )
    val bar5 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(
            tween(640, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(200),
        ),
        label = "b5",
    )
    val bar6 by infinite.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(
            tween(500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(240),
        ),
        label = "b6",
    )
    val heights = listOf(bar0, bar1, bar2, bar3, bar4, bar5, bar6)

    Canvas(modifier = modifier) {
        val barCount = heights.size
        val gap = size.width * 0.03f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        heights.forEachIndexed { index, heightFactor ->
            val barHeight = size.height * (0.2f + heightFactor * 0.8f)
            val left = index * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                color = colors[index % colors.size],
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
