package eu.dns3d.epfcockpit.ui.dashboard

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.ui.dashboard.components.BatteryRing
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
    val blinkertonOff by vm.blinkertonOff.collectAsStateWithLifecycle()
    val controlsLocked by vm.controlsLocked.collectAsStateWithLifecycle()
    val telemetry = state.telemetry
    val parking = state.parking

    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

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
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            LandscapeLayout(
                padding = padding,
                telemetry = telemetry,
                parking = parking,
                blinkertonOff = blinkertonOff,
                rideMode = state.rideMode,
                controlsLocked = controlsLocked,
                onParkingToggle = vm::toggleParking,
                onBlinkertonToggle = vm::toggleBlinkerton,
                onRideModeChange = vm::setRideMode,
            )
        } else {
            PortraitLayout(
                padding = padding,
                telemetry = telemetry,
                parking = parking,
                blinkertonOff = blinkertonOff,
                rideMode = state.rideMode,
                controlsLocked = controlsLocked,
                onParkingToggle = vm::toggleParking,
                onBlinkertonToggle = vm::toggleBlinkerton,
                onRideModeChange = vm::setRideMode,
            )
        }
    }
}

@Composable
private fun PortraitLayout(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    parking: Boolean,
    blinkertonOff: Boolean?,
    rideMode: RideMode,
    controlsLocked: Boolean,
    onParkingToggle: () -> Unit,
    onBlinkertonToggle: () -> Unit,
    onRideModeChange: (RideMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SpeedGauge(
                speedKmh = telemetry?.speedKmh ?: 0f,
                maxSpeedKmh = 25f,
                modifier = Modifier.fillMaxWidth(0.9f),
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BatteryRing(
                percent = telemetry?.batteryPercent ?: 0,
                modifier = Modifier.fillMaxWidth(0.55f),
            )
        }
        if (telemetry?.isRegenerating == true) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StatusRow(telemetry)
            }
        }
        StatGrid(telemetry)
        ControlSection(
            parking = parking,
            blinkertonOff = blinkertonOff,
            rideMode = rideMode,
            locked = controlsLocked,
            onParkingToggle = onParkingToggle,
            onBlinkertonToggle = onBlinkertonToggle,
            onRideModeChange = onRideModeChange,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LandscapeLayout(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    parking: Boolean,
    blinkertonOff: Boolean?,
    rideMode: RideMode,
    controlsLocked: Boolean,
    onParkingToggle: () -> Unit,
    onBlinkertonToggle: () -> Unit,
    onRideModeChange: (RideMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left: big speed gauge dominates the road-relevant info
        Box(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            SpeedGauge(
                speedKmh = telemetry?.speedKmh ?: 0f,
                maxSpeedKmh = 25f,
                modifier = Modifier.fillMaxHeight(0.95f),
            )
        }
        // Right: battery + stats + controls in a scrollable column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BatteryRing(
                    percent = telemetry?.batteryPercent ?: 0,
                    modifier = Modifier.fillMaxWidth(0.40f),
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (telemetry?.isRegenerating == true) StatusRow(telemetry)
                    StatTile(
                        label = "Leistung",
                        value = "%.0f".format(telemetry?.absWatts ?: 0f),
                        unit = if (telemetry?.isRegenerating == true) "W ⏎" else "W",
                        icon = Icons.Filled.Bolt,
                        modifier = Modifier.fillMaxWidth(),
                        accent = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            StatGrid(telemetry)
            ControlSection(
                parking = parking,
                blinkertonOff = blinkertonOff,
                rideMode = rideMode,
                locked = controlsLocked,
                onParkingToggle = onParkingToggle,
                onBlinkertonToggle = onBlinkertonToggle,
                onRideModeChange = onRideModeChange,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Six stat tiles in three rows of two — same in portrait and landscape. */
@Composable
private fun StatGrid(telemetry: Telemetry?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Trip",
                value = "%.1f".format(telemetry?.tripKm ?: 0f),
                unit = "km",
                icon = Icons.Filled.Route,
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
                label = "Leistung",
                value = "%.0f".format(telemetry?.absWatts ?: 0f),
                unit = if (telemetry?.isRegenerating == true) "W ⏎" else "W",
                icon = Icons.Filled.Bolt,
                modifier = Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ControlSection(
    parking: Boolean,
    blinkertonOff: Boolean?,
    rideMode: RideMode,
    locked: Boolean,
    onParkingToggle: () -> Unit,
    onBlinkertonToggle: () -> Unit,
    onRideModeChange: (RideMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (locked) {
            // Lightweight hint why everything below is disabled. Sits at the
            // top of the section so it's the first thing the rider sees if
            // they reach for the controls mid-ride.
            Text(
                "Während der Fahrt gesperrt",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchRow(
            icon = Icons.Filled.LocalParking,
            label = "Parking",
            checked = parking,
            enabled = !locked,
            onToggle = onParkingToggle,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = rideMode == RideMode.Stand,
                onClick = { onRideModeChange(RideMode.Stand) },
                enabled = !locked,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
            ) { Text("Stand Stop") }
            SegmentedButton(
                selected = rideMode == RideMode.Kickstart,
                onClick = { onRideModeChange(RideMode.Kickstart) },
                enabled = !locked,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { Icon(Icons.Filled.DirectionsRun, contentDescription = null) },
            ) { Text("Kick Start") }
        }
        SwitchRow(
            icon = if (blinkertonOff == true) Icons.Filled.NotificationsActive
            else Icons.Filled.NotificationsOff,
            label = "Blinkerton",
            checked = blinkertonOff == true,
            enabled = blinkertonOff != null && !locked,
            onToggle = onBlinkertonToggle,
        )
    }
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = { if (enabled) onToggle() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                // Toggling is owned by the Card's onClick — having BOTH
                // the Card and the Switch react to taps causes onToggle()
                // to fire twice on a single tap, which immediately reverts
                // the new state.
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun StatusRow(t: Telemetry?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t?.isRegenerating == true) {
            AssistChip(
                onClick = {},
                label = { Text("Rekuperation") },
                leadingIcon = {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
    }
}
