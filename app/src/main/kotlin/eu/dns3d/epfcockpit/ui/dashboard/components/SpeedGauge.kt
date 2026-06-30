package eu.dns3d.epfcockpit.ui.dashboard.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    // Square aspect so the arc stays circular regardless of which axis the
    // caller constrains (fillMaxWidth in portrait, fillMaxHeight in landscape).
    BoxWithConstraints(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // Schriftgröße sanft an den Ring-Durchmesser gekoppelt, aber gedeckelt:
        // große Ringe (Standard/Fahrt) bleiben bei displayLarge/titleMedium,
        // nur der kleine Kompakt-Ring wird etwas kleiner — der Floor (42sp) hält
        // die Zahl bewusst eine Spur größer als die Akku-Prozentzahl (≈36sp).
        val diameter = minOf(maxWidth, maxHeight)
        val baseValue = MaterialTheme.typography.displayLarge.fontSize.value
        val baseUnit = MaterialTheme.typography.titleMedium.fontSize.value
        val valueSize = (diameter.value * 0.22f).coerceIn(42f, baseValue).sp
        val unitSize = (diameter.value * 0.075f).coerceAtMost(baseUnit).sp
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.10f
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            // drawArc's stroke is centered on the arc path, so half the
            // stroke would otherwise spill outside the Canvas (the arc rect
            // defaults to the full Canvas). Inset by stroke/2 on every side
            // so the rounded caps stay fully visible.
            val inset = strokeWidth / 2f
            val arcSweep = 270f
            val startAngle = 135f
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = arcSweep,
                useCenter = false,
                style = stroke,
                topLeft = topLeft,
                size = arcSize,
            )
            drawArc(
                brush = gradient,
                startAngle = startAngle,
                sweepAngle = arcSweep * animatedFraction,
                useCenter = false,
                style = stroke,
                topLeft = topLeft,
                size = arcSize,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%.1f".format(speedKmh),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = valueSize),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = unitSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("unused")
private val DebugAccent = Color.Magenta
