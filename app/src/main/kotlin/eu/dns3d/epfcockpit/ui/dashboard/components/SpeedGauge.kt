package eu.dns3d.epfcockpit.ui.dashboard.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SpeedGauge(
    speedKmh: Float,
    maxSpeedKmh: Float = 25f,
    modifier: Modifier = Modifier,
) {
    val targetFraction = (speedKmh / maxSpeedKmh).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "speedFrac",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val gradient = Brush.sweepGradient(
        0.00f to MaterialTheme.colorScheme.tertiary,
        0.50f to MaterialTheme.colorScheme.primary,
        1.00f to MaterialTheme.colorScheme.secondary,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val stroke = Stroke(width = size.minDimension * 0.10f, cap = StrokeCap.Round)
            val arcSweep = 270f
            val startAngle = 135f
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = arcSweep,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                brush = gradient,
                startAngle = startAngle,
                sweepAngle = arcSweep * animatedFraction,
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%.1f".format(speedKmh),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("unused")
private val DebugAccent = Color.Magenta
