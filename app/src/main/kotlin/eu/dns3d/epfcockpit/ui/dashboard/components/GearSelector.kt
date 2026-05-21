package eu.dns3d.epfcockpit.ui.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.dns3d.epfcockpit.ui.theme.GearFast
import eu.dns3d.epfcockpit.ui.theme.GearMid
import eu.dns3d.epfcockpit.ui.theme.GearSlow

@Composable
fun GearSelector(
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = listOf(GearSlow, GearMid, GearFast)
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        (1..3).forEach { gear ->
            val accent = accents[gear - 1]
            FilledIconToggleButton(
                checked = current == gear,
                onCheckedChange = { if (it) onSelect(gear) },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    checkedContainerColor = accent,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    "$gear",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
