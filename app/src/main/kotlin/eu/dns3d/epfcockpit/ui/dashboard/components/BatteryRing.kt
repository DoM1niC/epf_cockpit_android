package eu.dns3d.epfcockpit.ui.dashboard.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

@Composable
fun BatteryRing(
    percent: Int,
    modifier: Modifier = Modifier,
) {
    val target = (percent / 100f).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(targetValue = target, animationSpec = tween(600), label = "batt")
    val low = Color(0xFFE53935)
    val mid = Color(0xFFFB8C00)
    val high = Color(0xFF43A047)
    val color = when {
        percent < 20 -> low
        percent < 50 -> lerp(low, mid, ((percent - 20) / 30f).coerceIn(0f, 1f))
        else -> lerp(mid, high, ((percent - 50) / 50f).coerceIn(0f, 1f))
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.12f
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val inset = strokeWidth / 2f
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(
                color = track, startAngle = -90f, sweepAngle = 360f,
                useCenter = false, style = stroke, topLeft = topLeft, size = arcSize,
            )
            drawArc(
                color = color, startAngle = -90f, sweepAngle = 360f * fraction,
                useCenter = false, style = stroke, topLeft = topLeft, size = arcSize,
            )
        }
        Text(
            "$percent%",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
