package eu.dns3d.epfcockpit.ui.dashboard.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            ValueWithUnit(
                value = value,
                unit = unit,
                valueStyle = MaterialTheme.typography.headlineLarge,
                unitStyle = MaterialTheme.typography.bodyMedium,
                valueColor = MaterialTheme.colorScheme.onSurface,
                unitColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Big value + small unit on a single line that never wraps. The value font shrinks
 * just enough so value + unit fit the tile width on small screens — so e.g. the
 * "km" unit can't break into "k"/"m" and tiles keep an even height (no grid gaps).
 *
 * Deterministic, flicker-free: measures once with a [androidx.compose.ui.text.TextMeasurer]
 * and scales the font down proportionally (text width ~ linear in font size). The
 * unit's lineHeight is unchanged across tiles, so all tiles stay the same height
 * even when one value is scaled down.
 */
@Composable
private fun ValueWithUnit(
    value: String,
    unit: String,
    valueStyle: TextStyle,
    unitStyle: TextStyle,
    valueColor: Color,
    unitColor: Color,
) {
    val measurer = rememberTextMeasurer()
    val unitText = "  $unit"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val unitWidthPx = measurer.measure(unitText, unitStyle).size.width.toFloat()
        val valueWidthPx = measurer.measure(value, valueStyle).size.width.toFloat()
        val avail = (maxWidthPx - unitWidthPx).coerceAtLeast(1f)

        // Only ever shrink, never enlarge; small safety margin so it never clips.
        // (TextUnit * Float uses the member operator — no import needed, unlike Float * TextUnit.)
        val scale = if (valueWidthPx > avail) (avail / valueWidthPx) * 0.98f else 1f
        val fittedValueStyle =
            if (scale < 1f) valueStyle.copy(fontSize = valueStyle.fontSize * scale) else valueStyle

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = fittedValueStyle,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                unitText,
                style = unitStyle,
                color = unitColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
