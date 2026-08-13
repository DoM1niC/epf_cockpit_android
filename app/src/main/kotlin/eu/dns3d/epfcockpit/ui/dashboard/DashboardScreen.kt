package eu.dns3d.epfcockpit.ui.dashboard

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.dns3d.epfcockpit.ble.Telemetry
import eu.dns3d.epfcockpit.data.DashboardLayout
import eu.dns3d.epfcockpit.ui.dashboard.components.BatteryRing
import eu.dns3d.epfcockpit.ui.dashboard.components.SpeedGauge
import eu.dns3d.epfcockpit.ui.dashboard.components.StatTile

private const val MAX_SPEED_KMH = 25f

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
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                    IconButton(onClick = { vm.disconnect(); onDisconnect() }) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Trennen / BT-Suche")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controls = DashControls(
            parking = state.parking,
            blinkertonOff = blinkertonOff,
            rideMode = state.rideMode,
            locked = controlsLocked,
            onParkingToggle = vm::toggleParking,
            onBlinkertonToggle = vm::toggleBlinkerton,
            onRideModeChange = vm::setRideMode,
        )
        when (state.dashboardLayout) {
            DashboardLayout.STANDARD ->
                if (landscape) StandardLandscape(padding, telemetry, controls)
                else StandardPortrait(padding, telemetry, controls)
            DashboardLayout.KOMPAKT ->
                if (landscape) KompaktLandscape(padding, telemetry, controls)
                else KompaktPortrait(padding, telemetry, controls)
            DashboardLayout.FAHRT ->
                if (landscape) FahrtLandscape(padding, telemetry, controls)
                else FahrtPortrait(padding, telemetry, controls)
            DashboardLayout.DATEN ->
                if (landscape) DatenLandscape(padding, telemetry, controls)
                else DatenPortrait(padding, telemetry, controls)
        }
    }
}

/**
 * Steuer-Zustand + Callbacks gebündelt, damit die acht Layout-Varianten nicht
 * jeweils sieben Einzelparameter durchreichen müssen.
 */
private class DashControls(
    val parking: Boolean?,
    val blinkertonOff: Boolean?,
    val rideMode: RideMode,
    val locked: Boolean,
    val onParkingToggle: () -> Unit,
    val onBlinkertonToggle: () -> Unit,
    val onRideModeChange: (RideMode) -> Unit,
)

private data class StatSpec(
    val label: String,
    val value: String,
    val unit: String,
    val icon: ImageVector,
    /** true → tertiäre Akzentfarbe (z.B. Leistung/Rekuperation). */
    val accent: Boolean = false,
)

/** Die Standard-Kacheln — Reihenfolge so, dass Strom/Temperaturen gruppiert
 *  liegen und die Leistung als Abschlusszeile steht. */
private fun coreStats(t: Telemetry?): List<StatSpec> = listOf(
    StatSpec("Trip", "%.1f".format(t?.tripKm ?: 0f), "km", Icons.Filled.Route),
    StatSpec("Gesamt", "%.1f".format(t?.totalKm ?: 0f), "km", Icons.Filled.Speed),
    StatSpec("Spannung", "%.1f".format(t?.voltage ?: 0f), "V", Icons.Filled.Bolt),
    StatSpec("Strom", "%.1f".format(t?.currentAmps ?: 0f), "A", Icons.Filled.ElectricalServices),
    StatSpec("Controller", "${t?.controllerTempC ?: 0}", "°C", Icons.Filled.Thermostat),
    StatSpec("Motor", "${t?.motorTempC ?: 0}", "°C", Icons.Filled.Thermostat),
    StatSpec(
        "Leistung",
        "%.0f".format(t?.absWatts ?: 0f),
        if (t?.isRegenerating == true) "W ⏎" else "W",
        Icons.Filled.Bolt,
        accent = true,
    ),
)

/** Vollständiger Satz für die DATEN-Ansicht: Speed/Akku/Gang vorangestellt. */
private fun allStats(t: Telemetry?): List<StatSpec> = listOf(
    StatSpec("Speed", "%.1f".format(t?.speedKmh ?: 0f), "km/h", Icons.Filled.Speed),
    StatSpec("Akku", "${t?.batteryPercent ?: 0}", "%", Icons.Filled.BatteryFull),
    StatSpec("Gang", "${t?.gear ?: 0}", "", Icons.Filled.Tune),
) + coreStats(t)

// ---------------------------------------------------------------------------
// STANDARD — das bisherige Layout, unverändert (Default).
// ---------------------------------------------------------------------------

@Composable
private fun StandardPortrait(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
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
                maxSpeedKmh = MAX_SPEED_KMH,
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
        StatGridLayout(coreStats(telemetry), columns = 2)
        ControlSection(controls)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StandardLandscape(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
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
                maxSpeedKmh = MAX_SPEED_KMH,
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
            StatGridLayout(coreStats(telemetry), columns = 2)
            ControlSection(controls)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// KOMPAKT — alle Werte auf einem Screen ohne Scrollen.
// Kniff: die Gauges werden über die HÖHE dimensioniert (nicht die Breite), damit
// sie auf breiten Phones nicht in die Höhe wachsen und Platz fressen.
// ---------------------------------------------------------------------------

@Composable
private fun KompaktPortrait(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val gaugeH = (maxHeight * 0.30f).coerceIn(120.dp, 200.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SpeedGauge(
                        speedKmh = telemetry?.speedKmh ?: 0f,
                        maxSpeedKmh = MAX_SPEED_KMH,
                        modifier = Modifier.height(gaugeH),
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    BatteryRing(
                        percent = telemetry?.batteryPercent ?: 0,
                        modifier = Modifier.height(gaugeH * 0.82f),
                    )
                }
            }
            StatGridLayout(coreStats(telemetry), columns = 3, spacing = 8.dp, dense = true)
            ControlSection(controls, compact = true)
        }
    }
}

@Composable
private fun KompaktLandscape(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val battDim = (maxHeight * 0.32f).coerceIn(72.dp, 150.dp)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SpeedGauge(
                    speedKmh = telemetry?.speedKmh ?: 0f,
                    maxSpeedKmh = MAX_SPEED_KMH,
                    modifier = Modifier.fillMaxHeight(0.9f),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryRing(
                        percent = telemetry?.batteryPercent ?: 0,
                        modifier = Modifier.size(battDim),
                    )
                }
                StatGridLayout(coreStats(telemetry), columns = 3, spacing = 8.dp, dense = true)
                ControlSection(controls, compact = true)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FAHRT — riesiger Tacho + Akku, nur die wichtigsten Werte.
// ---------------------------------------------------------------------------

@Composable
private fun FahrtPortrait(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val gaugeDim = minOf(maxWidth * 0.92f, maxHeight * 0.52f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpeedGauge(
                    speedKmh = telemetry?.speedKmh ?: 0f,
                    maxSpeedKmh = MAX_SPEED_KMH,
                    modifier = Modifier.size(gaugeDim),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BatteryRing(
                        percent = telemetry?.batteryPercent ?: 0,
                        modifier = Modifier.size(gaugeDim * 0.34f),
                    )
                }
                StatTile(
                    label = "Trip",
                    value = "%.1f".format(telemetry?.tripKm ?: 0f),
                    unit = "km",
                    icon = Icons.Filled.Route,
                    modifier = Modifier.weight(1f),
                    dense = true,
                )
                StatTile(
                    label = "Leistung",
                    value = "%.0f".format(telemetry?.absWatts ?: 0f),
                    unit = if (telemetry?.isRegenerating == true) "W ⏎" else "W",
                    icon = Icons.Filled.Bolt,
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.tertiary,
                    dense = true,
                )
            }
            ControlSection(controls, compact = true)
        }
    }
}

@Composable
private fun FahrtLandscape(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val battDim = (maxHeight * 0.34f).coerceIn(80.dp, 160.dp)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SpeedGauge(
                    speedKmh = telemetry?.speedKmh ?: 0f,
                    maxSpeedKmh = MAX_SPEED_KMH,
                    modifier = Modifier.fillMaxHeight(0.95f),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryRing(
                        percent = telemetry?.batteryPercent ?: 0,
                        modifier = Modifier.size(battDim),
                    )
                }
                StatTile(
                    label = "Trip",
                    value = "%.1f".format(telemetry?.tripKm ?: 0f),
                    unit = "km",
                    icon = Icons.Filled.Route,
                    modifier = Modifier.fillMaxWidth(),
                    dense = true,
                )
                StatTile(
                    label = "Leistung",
                    value = "%.0f".format(telemetry?.absWatts ?: 0f),
                    unit = if (telemetry?.isRegenerating == true) "W ⏎" else "W",
                    icon = Icons.Filled.Bolt,
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.tertiary,
                    dense = true,
                )
                ControlSection(controls, compact = true)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DATEN — nur Zahlen, keine runden Anzeigen (Diagnose/Technik).
// ---------------------------------------------------------------------------

@Composable
private fun DatenPortrait(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatGridLayout(allStats(telemetry), columns = 3, spacing = 8.dp, dense = true)
        ControlSection(controls, compact = true)
    }
}

@Composable
private fun DatenLandscape(
    padding: androidx.compose.foundation.layout.PaddingValues,
    telemetry: Telemetry?,
    controls: DashControls,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            StatGridLayout(allStats(telemetry), columns = 3, spacing = 8.dp, dense = true)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            ControlSection(controls, compact = true)
        }
    }
}

// ---------------------------------------------------------------------------
// Wiederverwendbare Bausteine
// ---------------------------------------------------------------------------

/**
 * Kachel-Raster mit fester Spaltenzahl. [columns] = 2 ergibt die bisherige
 * 3×2-Anordnung; 3 verdichtet auf zwei Reihen für die Kompakt-/Daten-Ansicht.
 */
@Composable
private fun StatGridLayout(
    stats: List<StatSpec>,
    columns: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    dense: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        stats.chunked(columns).forEach { rowStats ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                rowStats.forEach { s ->
                    StatTile(
                        label = s.label,
                        value = s.value,
                        unit = s.unit,
                        icon = s.icon,
                        modifier = Modifier.weight(1f),
                        accent = if (s.accent) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                        dense = dense,
                    )
                }
                // Letzte Reihe auffüllen, damit Kacheln gleich breit bleiben.
                repeat(columns - rowStats.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ControlSection(
    controls: DashControls,
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controls.locked) {
            // Lightweight hint why everything below is disabled. Sits at the
            // top of the section so it's the first thing the rider sees if
            // they reach for the controls mid-ride.
            Text(
                "Während der Fahrt gesperrt",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (compact) {
            // Parking + Blinkerton nebeneinander, um die höchste Block-Höhe zu sparen.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    icon = Icons.Filled.LocalParking,
                    label = "Parking",
                    checked = controls.parking == true,
                    enabled = !controls.locked,
                    onToggle = controls.onParkingToggle,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                SwitchRow(
                    icon = if (controls.blinkertonOff == true) Icons.Filled.NotificationsActive
                    else Icons.Filled.NotificationsOff,
                    label = "Blinkerton",
                    checked = controls.blinkertonOff == true,
                    enabled = controls.blinkertonOff != null && !controls.locked,
                    onToggle = controls.onBlinkertonToggle,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
            RideModeRow(controls)
        } else {
            SwitchRow(
                icon = Icons.Filled.LocalParking,
                label = "Parking",
                checked = controls.parking == true,
                enabled = !controls.locked,
                onToggle = controls.onParkingToggle,
            )
            RideModeRow(controls)
            SwitchRow(
                icon = if (controls.blinkertonOff == true) Icons.Filled.NotificationsActive
                else Icons.Filled.NotificationsOff,
                label = "Blinkerton",
                checked = controls.blinkertonOff == true,
                enabled = controls.blinkertonOff != null && !controls.locked,
                onToggle = controls.onBlinkertonToggle,
            )
        }
    }
}

@Composable
private fun RideModeRow(controls: DashControls) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = controls.rideMode == RideMode.Stand,
            onClick = { controls.onRideModeChange(RideMode.Stand) },
            enabled = !controls.locked,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
        ) { Text("Stand Stop") }
        SegmentedButton(
            selected = controls.rideMode == RideMode.Kickstart,
            onClick = { controls.onRideModeChange(RideMode.Kickstart) },
            enabled = !controls.locked,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null) },
        ) { Text("Kick Start") }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    compact: Boolean = false,
) {
    Card(
        onClick = { if (enabled) onToggle() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(if (compact) 8.dp else 16.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
