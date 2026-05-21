package eu.dns3d.epfcockpit.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.ui.dashboard.components.BatteryRing
import eu.dns3d.epfcockpit.ui.dashboard.components.GearSelector
import eu.dns3d.epfcockpit.ui.dashboard.components.SpeedGauge
import eu.dns3d.epfcockpit.ui.dashboard.components.StatTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onDisconnect: () -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val telemetry = state.telemetry

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EPF Cockpit", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.Route, contentDescription = "Fahrten")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.ElectricalServices, contentDescription = "Einstellungen")
                    }
                    IconButton(onClick = { vm.disconnect(); onDisconnect() }) {
                        Icon(Icons.Filled.Power, contentDescription = "Trennen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val maxKmh = when (telemetry?.gear) {
                1 -> state.gear1MaxKmh
                3 -> state.gear3MaxKmh
                else -> state.gear2MaxKmh
            }.coerceAtLeast(6)

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpeedGauge(
                    speedKmh = telemetry?.speedKmh ?: 0f,
                    maxSpeedKmh = maxKmh.toFloat(),
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BatteryRing(
                    percent = telemetry?.batteryPercent ?: 0,
                    modifier = Modifier.weight(1f),
                )
                Column(Modifier.weight(1.4f)) {
                    StatusRow(telemetry, state.control.locked, state.control.lightOn)
                }
            }

            GearSelector(
                current = state.control.gear,
                onSelect = vm::setGear,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = "Leistung",
                    value = "%.0f".format(telemetry?.absWatts ?: 0f),
                    unit = if (telemetry?.isRegenerating == true) "W ⏎" else "W",
                    icon = Icons.Filled.Bolt,
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.tertiary,
                )
                StatTile(
                    label = "Trip",
                    value = "%.1f".format(telemetry?.tripKm ?: 0f),
                    unit = "km",
                    icon = Icons.Filled.Route,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = "Spannung",
                    value = "%.1f".format(telemetry?.voltage ?: 0f),
                    unit = "V",
                    icon = Icons.Filled.Bolt,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Strom",
                    value = "%.1f".format(telemetry?.currentAmps ?: 0f),
                    unit = "A",
                    icon = Icons.Filled.ElectricalServices,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = "Controller",
                    value = "${telemetry?.controllerTempC ?: 0}",
                    unit = "°C",
                    icon = Icons.Filled.Thermostat,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Gesamt",
                    value = "%.1f".format(telemetry?.totalKm ?: 0f),
                    unit = "km",
                    icon = Icons.Filled.Speed,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalIconToggleButton(
                    checked = state.control.lightOn,
                    onCheckedChange = { vm.toggleLight() },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Filled.Highlight, contentDescription = "Licht")
                }
                FilledTonalIconToggleButton(
                    checked = state.control.locked,
                    onCheckedChange = { vm.toggleLock() },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(
                        if (state.control.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = "Sperren",
                    )
                }
                FilledTonalIconToggleButton(
                    checked = false,
                    onCheckedChange = { vm.resetTrip() },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "Trip-Reset")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusRow(t: Telemetry?, locked: Boolean, lightOn: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {},
            label = { Text("Gang ${t?.gear ?: '-'}") },
            leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )
        if (locked) {
            AssistChip(
                onClick = {},
                label = { Text("Gesperrt") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
        }
        if (lightOn) {
            AssistChip(
                onClick = {},
                label = { Text("Licht an") },
                leadingIcon = { Icon(Icons.Filled.Highlight, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (t?.isRegenerating == true) {
            AssistChip(
                onClick = {},
                label = { Text("Rekuperation") },
                leadingIcon = { Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
    }
}
