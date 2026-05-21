package eu.dns3d.epfcockpit.ui.scan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    vm: ScanViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val permissions = rememberMultiplePermissionsState(bluetoothPermissions())

    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) onConnected()
    }

    LaunchedEffect(permissions.allPermissionsGranted) {
        if (!permissions.allPermissionsGranted) permissions.launchMultiplePermissionRequest()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("EPF Cockpit", style = MaterialTheme.typography.titleLarge)
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.scanning) vm.stopScan() else vm.startScan() },
                icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                text = { Text(if (state.scanning) "Stop" else "Suchen") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scooter suchen",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = "Schalte deinen EPF Pulse Plus ein – er sollte gleich erscheinen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            AnimatedVisibility(visible = state.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Scanne…", style = MaterialTheme.typography.bodyMedium)
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

            Spacer(Modifier.height(12.dp))

            if (state.devices.isEmpty() && !state.scanning) {
                EmptyHint()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp, bottom = 96.dp),
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
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isRemembered)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
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
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unbekannt",
                    style = MaterialTheme.typography.titleMedium,
                )
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
