package eu.dns3d.epfcockpit.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import eu.dns3d.epfcockpit.data.DashboardLayout
import eu.dns3d.epfcockpit.data.ThemeMode
import eu.dns3d.epfcockpit.streaming.TelemetryStreamer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onForget: () -> Unit,
    onOpenNfc: () -> Unit = {},
    onOpenNfcClone: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pinStatus by vm.pinStatus.collectAsStateWithLifecycle()
    val streamingStatus by vm.streamingStatus.collectAsStateWithLifecycle()
    val nameSyncStatus by vm.scooterNameSyncStatus.collectAsStateWithLifecycle()
    var newPin by remember { mutableStateOf("") }
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    // Lokaler Buffer für das URL-Feld, damit der User tippen kann ohne dass jede
    // Tastatureingabe sofort durch den DataStore zurückgepushed wird.
    var urlDraft by remember(state.streamingUrl) { mutableStateOf(state.streamingUrl) }
    LaunchedEffect(state.streamingUrl) {
        if (urlDraft.isBlank()) urlDraft = state.streamingUrl
    }
    var tokenDraft by remember(state.streamingToken) { mutableStateOf(state.streamingToken) }
    LaunchedEffect(state.streamingToken) {
        if (tokenDraft.isBlank()) tokenDraft = state.streamingToken
    }
    var tokenVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.scooterName?.let { name ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        state.scooterAddress?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            SectionCard("Verbindung") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatisch verbinden", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Beim Start und nach einem Verbindungsabbruch zum letzten Scooter",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.autoConnect, onCheckedChange = vm::setAutoConnect)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bei gesperrtem Handy neu verbinden", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Reißt die Verbindung ab, während das Display aus oder das Handy " +
                                "gesperrt ist, wartet die App bis zu 2 Stunden auf den Roller " +
                                "und verbindet sich selbst neu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.reconnectWhileLocked,
                        enabled = state.autoConnect,
                        onCheckedChange = vm::setReconnectWhileLocked,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hinweis: Solange diese App verbunden ist, kann sich keine andere " +
                        "App (z.B. die Hersteller-App) mit dem Roller verbinden — er " +
                        "advertised dann nicht mehr. Tippe auf „Trennen“ (Dashboard oder " +
                        "Notification), bevor du eine andere App nutzt. Bist du entsperrt " +
                        "in einer anderen App, pausiert der Auto-Reconnect. Bei gesperrtem " +
                        "Handy verbindet die App selbst neu – schalte das oben aus, wenn " +
                        "die Hersteller-App Vorrang haben soll.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Lokaler Buffer + 400ms-Debounce. Direktes commit-on-keystroke
                // wuerde durch den DataStore-Roundtrip die Anzeige hinterhinken
                // lassen (User sieht Zeichen verschwinden + wiederkommen).
                // nameDraft = null heisst "noch nicht getippt" — wir zeigen
                // den State-Wert, ohne ihn in den Buffer zu kopieren. Sobald
                // der User tippt, uebernimmt der Buffer.
                var nameDraft by remember { mutableStateOf<String?>(null) }
                val nameShown = nameDraft ?: state.scooterFriendlyName
                LaunchedEffect(nameDraft) {
                    val cur = nameDraft ?: return@LaunchedEffect
                    if (cur == state.scooterFriendlyName) return@LaunchedEffect
                    kotlinx.coroutines.delay(400)
                    vm.setScooterFriendlyName(cur)
                }
                OutlinedTextField(
                    value = nameShown,
                    onValueChange = { nameDraft = it },
                    label = { Text("Anzeigename") },
                    placeholder = { Text("z.B. Pulse Plus von Tom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wird im Dashboard als Scooter-Label gezeigt. Leer = Fallback \"E-Scooter\". " +
                        "Bei aktivem Streaming wird der Name mit dem Server synchronisiert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                nameSyncStatus?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            SectionCard("Darstellung", leadingIcon = Icons.Filled.DarkMode) {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                val options = listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dynamische Farben (Material You / Monet) bleiben aktiv. " +
                        "System folgt der Geräteeinstellung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Dashboard-Layout", leadingIcon = Icons.Filled.Dashboard) {
                val layoutOptions = listOf(
                    DashboardLayout.STANDARD to "Standard",
                    DashboardLayout.KOMPAKT to "Kompakt",
                    DashboardLayout.FAHRT to "Fahrt",
                    DashboardLayout.DATEN to "Daten",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    layoutOptions.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = state.dashboardLayout == mode,
                            onClick = { vm.setDashboardLayout(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, layoutOptions.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    layoutHint(state.dashboardLayout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Live-Streaming an Dashboard", leadingIcon = Icons.Filled.Bolt) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Telemetry an Server pushen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            streamingStatusText(streamingStatus, state.streamingEnabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = streamingStatusColor(streamingStatus, state.streamingEnabled),
                        )
                    }
                    Switch(
                        checked = state.streamingEnabled,
                        onCheckedChange = vm::setStreamingEnabled,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = { Text("WebSocket-URL") },
                    placeholder = { Text("wss://cockpit.3dns.eu/ws/ingest") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    label = { Text("Token (optional)") },
                    placeholder = { Text("INGEST_TOKEN des Servers") },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) androidx.compose.ui.text.input.VisualTransformation.None
                                            else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (tokenVisible) "Token verbergen" else "Token zeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            vm.setStreamingUrl(urlDraft)
                            vm.setStreamingToken(tokenDraft)
                        },
                        enabled = urlDraft != state.streamingUrl || tokenDraft != state.streamingToken,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("Speichern") }
                    FilledTonalButton(
                        onClick = {
                            urlDraft = ""; tokenDraft = ""
                            vm.setStreamingUrl(""); vm.setStreamingToken("")
                        },
                        enabled = state.streamingUrl.isNotEmpty() || state.streamingToken.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("Leeren") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Endpoint des Node-Dashboards (siehe web/ im Repo). Token wird als " +
                        "Authorization-Bearer-Header übertragen — leer lassen wenn der " +
                        "Server ohne INGEST_TOKEN läuft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Standortverfolgung", leadingIcon = Icons.Filled.LocationOn) {
                val granted = locationPermission.status.isGranted
                val needsPermission = state.trackLocation && !granted
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPS-Waypoints pro Trip", style = MaterialTheme.typography.titleMedium)
                        Text(
                            locationStatusText(
                                enabled = state.trackLocation,
                                granted = granted,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (needsPermission)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.trackLocation,
                        onCheckedChange = { wanted ->
                            vm.setTrackLocation(wanted)
                            // Erst beim Einschalten den Runtime-Prompt feuern —
                            // beim Ausschalten nicht, sonst nervt es bei jedem
                            // Toggle. Wenn schon granted, kein Prompt nötig.
                            if (wanted && !granted) {
                                locationPermission.launchPermissionRequest()
                            }
                        },
                    )
                }
                if (needsPermission) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { locationPermission.launchPermissionRequest() },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Berechtigung erteilen") }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auch im Idle senden", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sendet GPS auch wenn keine Fahrt läuft (geparkt / stehend). " +
                                "Nur live ans Dashboard, wird nicht als Trip gespeichert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.trackLocationIdle,
                        enabled = state.trackLocation && granted,
                        onCheckedChange = { vm.setTrackLocationIdle(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wenn aktiv, zeichnet die App jede Sekunde einen GPS-Punkt auf " +
                        "— während einer Fahrt persistent (pro Trip), sonst optional " +
                        "nur live ans Dashboard. Ohne Google-Dienste, direkt über " +
                        "Android-LocationManager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Roller", leadingIcon = Icons.Filled.ElectricScooter) {
                ScooterSection(
                    canResetTrip = state.authenticated && state.parkingKnown && state.standing,
                    authenticated = state.authenticated,
                    tripKm = state.tripKm,
                    onResetTrip = vm::resetTrip,
                )
            }

            SectionCard("Max-Geschwindigkeit", leadingIcon = Icons.Filled.Speed) {
                MaxSpeedSection(
                    savedKmh = state.gear3,
                    authenticated = state.authenticated,
                    parking = state.parking,
                    onApply = vm::setMaxSpeed,
                )
            }

            SectionCard("PIN ändern") {
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) newPin = it },
                    label = { Text("Neue PIN (6-stellig)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.changePin(newPin); newPin = "" },
                    enabled = newPin.length == 6,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("PIN ändern") }
                pinStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }

            SectionCard("NFC-Diagnose", leadingIcon = Icons.Filled.Nfc) {
                Text(
                    "Liest NTAG2xx / Mifare-Ultralight-Tags aus und zeigt UID, " +
                        "unterstützte Technologien und kompletten Speicher-Dump. " +
                        "Nützlich um die Original-Roller-NFC-Karte zu analysieren " +
                        "bevor ein Klon erstellt wird.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onOpenNfc,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tag scannen") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Mit einem Magic-NTAG215 (Gen2 / UID-changeable) kann die UID einer " +
                        "Original-Karte 1:1 geklont werden. Standard-NXP-NTAGs sind ab Werk " +
                        "auf Page 0–2 schreibgeschützt und funktionieren nicht.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onOpenNfcClone,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tag klonen (Magic NTAG215)") }
            }

            SectionCard("Über die App") {
                AboutContent()
            }

            SectionCard("Gefahrenzone") {
                FilledTonalButton(
                    onClick = { vm.forgetScooter(); onForget() },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scooter vergessen") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ScooterSection(
    canResetTrip: Boolean,
    authenticated: Boolean,
    tripKm: Float?,
    onResetTrip: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Tageskilometer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tripKm?.let { "%.1f km".format(it) } ?: "–",
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(
        onClick = { confirmReset = true },
        enabled = canResetTrip,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Tageskilometer zurücksetzen") }
    val hint = when {
        !authenticated -> "Nicht verbunden — verbinde dich zuerst über das Dashboard"
        !canResetTrip -> "Nur im Stand möglich"
        else -> null
    }
    if (hint != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Tageskilometer zurücksetzen?") },
            text = {
                Text(
                    "Der Trip-Zähler am Roller wird auf 0 gesetzt. Gesamtkilometer " +
                        "und die in der App gespeicherten Fahrten bleiben erhalten.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onResetTrip() }) { Text("Zurücksetzen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun MaxSpeedSection(
    savedKmh: Int,
    authenticated: Boolean,
    parking: Boolean,
    onApply: (Int) -> Unit,
) {
    // Der SliderState hält den Entwurf und hängt am gespeicherten Wert — wenn
    // der sich nach einem erfolgreichen Apply ändert, snapped der Slider mit.
    // remember(savedKmh) re-initialisiert das State-Object bei jedem
    // savedKmh-Update (rememberSliderState hat keine Keys, taugt dafür nicht).
    val sliderState = remember(savedKmh) {
        SliderState(
            value = savedKmh.toFloat(),
            steps = 28, // (30 - 1 - 1) = 28 Zwischenstops → 1 km/h-Granularität
            trackRange = 1f..30f,
        )
    }
    // roundToInt statt toInt: M3-Slider snapt intern auf Float-Werte mit
    // leichten Rundungsfehlern (21.999... → toInt = 21). Mit roundToInt
    // treffen wir jede Integer-Stufe sauber.
    val draftKmh = sliderState.value.roundToInt().coerceIn(1, 30)
    val isExperimental = draftKmh > 20

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Aktuell gespeichert",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$savedKmh km/h", style = MaterialTheme.typography.titleLarge)
        }
        if (isExperimental) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    "EXPERIMENTELL",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Neu: $draftKmh km/h",
        style = MaterialTheme.typography.titleMedium,
        color = if (isExperimental) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
    )
    Slider(
        state = sliderState,
        // Seit material3 1.5.0-beta01 Pflicht: Mit Callback setzt der Slider den
        // State nicht mehr selbst, das Zurückschreiben übernimmt der Aufrufer.
        onValueChange = { sliderState.value = it },
        colors = if (isExperimental) SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.tertiary,
            activeTrackColor = MaterialTheme.colorScheme.tertiary,
        ) else SliderDefaults.colors(),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("1 km/h", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("30 km/h", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    val canApply = authenticated && parking && draftKmh != savedKmh
    Button(
        onClick = { onApply(draftKmh) },
        enabled = canApply,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("An Roller senden") }
    val hint = when {
        !authenticated -> "Nicht verbunden — verbinde dich zuerst über das Dashboard"
        !parking -> "Roller muss geparkt sein (P-Modus aktiv), bevor du sendest"
        draftKmh == savedKmh -> "Wähle einen anderen Wert als $savedKmh km/h"
        isExperimental -> "Über 20 km/h ist experimentell — Hersteller-Limit überschritten, Controller kann den Wert ablehnen oder clampen."
        else -> null
    }
    if (hint != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (isExperimental && hint.startsWith("Über"))
                MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun streamingStatusText(status: TelemetryStreamer.Status, enabled: Boolean): String =
    when {
        !enabled -> "Aus"
        status == TelemetryStreamer.Status.Connected -> "Verbunden – sendet Live-Daten"
        status == TelemetryStreamer.Status.Connecting -> "Verbinde…"
        status == TelemetryStreamer.Status.Failed -> "Fehlgeschlagen – retry läuft"
        else -> "Wartet auf Konfiguration"
    }

@Composable
private fun streamingStatusColor(status: TelemetryStreamer.Status, enabled: Boolean): Color =
    when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        status == TelemetryStreamer.Status.Connected -> MaterialTheme.colorScheme.primary
        status == TelemetryStreamer.Status.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun locationStatusText(enabled: Boolean, granted: Boolean): String = when {
    !enabled -> "Aus – keine GPS-Aufzeichnung"
    !granted -> "Berechtigung fehlt – tippe auf den Button unten"
    else -> "Aktiv – läuft während Trips, wenn nicht geparkt"
}

private fun layoutHint(layout: DashboardLayout): String = when (layout) {
    DashboardLayout.STANDARD -> "Standard: großer Tacho + Akku, alle Kacheln, scrollbar."
    DashboardLayout.KOMPAKT -> "Kompakt zeigt alle Werte ohne Scrollen auf einem Bildschirm."
    DashboardLayout.FAHRT -> "Fahrt: riesiger Tacho + Akku, nur die wichtigsten Werte."
    DashboardLayout.DATEN -> "Daten: nur Zahlen, keine runden Anzeigen – Diagnose/Technik."
}

@Composable
private fun AboutContent() {
    val ctx = LocalContext.current
    fun open(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Open-Source-App für ePowerFun-Scooter (verifiziert auf EPF Pulse Plus).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Autor: DoM!niC", style = MaterialTheme.typography.bodyMedium)
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
    }
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

@Composable
private fun SectionCard(
    title: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
