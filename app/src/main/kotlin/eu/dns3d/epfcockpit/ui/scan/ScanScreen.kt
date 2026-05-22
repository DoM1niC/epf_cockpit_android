package eu.dns3d.epfcockpit.ui.scan

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import eu.dns3d.epfcockpit.ble.ConnectionState
import eu.dns3d.epfcockpit.ble.DiscoveredScooter
import eu.dns3d.epfcockpit.util.bluetoothPermissions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    onConnected: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ScanViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val permissions = rememberMultiplePermissionsState(bluetoothPermissions())
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshEnvironment() }

    // Push the granted-state into the ViewModel so the scan only kicks off when
    // permissions are actually in place. We re-emit on every recomposition where
    // the value changed; cheap because it's a primitive boolean.
    LaunchedEffect(permissions.allPermissionsGranted) {
        vm.onPermissionsResult(permissions.allPermissionsGranted)
    }

    // The BT-enabled flag isn't observable — re-check whenever we come back to
    // the foreground (after the user toggled BT in quick settings, after the
    // enable-dialog closed, etc.).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshEnvironment()
        }
        lifecycle.addObserver(observer)
    }

    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) onConnected()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EPF Cockpit", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { vm.toggleShowAll() }) {
                        Icon(
                            Icons.Filled.FilterAlt,
                            contentDescription = if (state.showAllDevices) "Nur EPF zeigen" else "Alle zeigen",
                            tint = if (state.showAllDevices) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.HelpOutline, contentDescription = "Info")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.permissionsGranted && state.bluetoothEnabled) {
                ExtendedFloatingActionButton(
                    onClick = { if (state.scanning) vm.stopScan() else vm.startScan() },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    text = { Text(if (state.scanning) "Stop" else "Suchen") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Scooter suchen", style = MaterialTheme.typography.displaySmall)
            Text(
                text = if (state.showAllDevices)
                    "Filter aus – jedes BLE-Gerät mit Namen wird angezeigt."
                else "Schalte deinen EPF Pulse Plus ein – er sollte gleich erscheinen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            if (!state.permissionsGranted) {
                BlockerCard(
                    icon = Icons.Filled.Lock,
                    title = "Bluetooth-Berechtigung fehlt",
                    body = "Die App braucht 'Geräte in der Nähe', um den Scooter zu finden. " +
                            "Standort wird nicht abgefragt (neverForLocation-Flag).",
                    buttonText = "Berechtigung erteilen",
                    onClick = { permissions.launchMultiplePermissionRequest() },
                )
                return@Column
            }
            if (!state.bluetoothEnabled) {
                BlockerCard(
                    icon = Icons.Filled.BluetoothDisabled,
                    title = "Bluetooth ist aus",
                    body = "Aktiviere Bluetooth, damit der Scan starten kann.",
                    buttonText = "Bluetooth einschalten",
                    onClick = {
                        btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                )
                return@Column
            }

            AnimatedVisibility(visible = state.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        if (state.showAllDevices) "Scanne alle BLE-Geräte…" else "Scanne nach EPF…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            AnimatedVisibility(visible = connection is ConnectionState.Connecting) {
                val target = (connection as? ConnectionState.Connecting)?.address ?: ""
                Text("Verbinde mit $target …", style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedVisibility(visible = connection is ConnectionState.Failed) {
                val reason = (connection as? ConnectionState.Failed)?.reason ?: ""
                Text(
                    "Verbindung fehlgeschlagen: $reason",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error?.let {
                Text(
                    "Scan-Fehler: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = state.showAllDevices,
                onClick = { vm.toggleShowAll() },
                label = {
                    Text(if (state.showAllDevices) "Filter aus" else "Nur EPF/ePF/ZYD")
                },
                leadingIcon = { Icon(Icons.Filled.FilterAlt, contentDescription = null) },
            )
            Spacer(Modifier.height(8.dp))

            if (state.devices.isEmpty() && !state.scanning) {
                EmptyHint()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                ) {
                    items(state.devices, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            isRemembered = device.address == state.rememberedAddress,
                            onClick = { vm.connect(device) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockerCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onClick,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(buttonText) }
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            "Noch nichts gefunden…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredScooter,
    isRemembered: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        isRemembered -> MaterialTheme.colorScheme.primaryContainer
        device.likelyScooter -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (device.likelyScooter) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name ?: "Unbekannt",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (device.likelyScooter) {
                        Spacer(Modifier.size(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("EPF", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                labelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun open(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.HelpOutline, contentDescription = null) },
        title = { Text("EPF Cockpit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Open-Source-App für ePowerFun-Scooter (verifiziert auf EPF Pulse Plus).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Autor: DoM!niC",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AboutLink(label = "Repo: git.3dns.eu/dom1nic/epf_cockpit_android") {
                    open("https://git.3dns.eu/dom1nic/epf_cockpit_android")
                }
                Spacer(Modifier.size(4.dp))
                Text("Quellen:", style = MaterialTheme.typography.labelLarge)
                AboutLink(label = "ZydDash BLE-Telemetry-Doku (ePF-1)") {
                    open("https://github.com/Ennar1991/ZydDash/blob/main/BLE_Telemetry.md")
                }
                AboutLink(label = "irmo.de — E-Scooter Bluetooth Hacking") {
                    open("https://www.irmo.de/2023/11/08/e-scooter-bluetooth-hacking/")
                }
                AboutLink(label = "escooter-treff.de Forenthread") {
                    open("https://www.escooter-treff.de/threads/zydtech-controller-bluetooth-protokoll-reverse-engineering.12207/")
                }
                AboutLink(label = "Offizielle App (für Sniff): com.zydtech.epowerfun") {
                    open("https://play.google.com/store/apps/details?id=com.zydtech.epowerfun")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
    )
}

@Composable
private fun AboutLink(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
