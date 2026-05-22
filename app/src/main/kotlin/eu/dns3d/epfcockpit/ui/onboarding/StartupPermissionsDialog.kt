package eu.dns3d.epfcockpit.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.dns3d.epfcockpit.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * Einmaliger Onboarding-Dialog beim ersten App-Start (oder solange noch
 * mindestens eine der beiden Voraussetzungen fehlt):
 *
 *   1. POST_NOTIFICATIONS-Permission (API 33+) — sonst zeigt der
 *      Foreground-Service zwar einen Slot, aber keine Notification.
 *   2. Akku-Optimierung aus — sonst friert Doze den Service nach paar
 *      Minuten ein und die BLE-Verbindung stirbt unauffällig.
 *
 * Der User kann mit "Später" wegklicken — der Dialog kommt beim nächsten
 * App-Start wieder solange noch etwas fehlt. Sobald beides erfüllt ist,
 * wird `settings.markStartupPromptDone()` gesetzt und er sieht ihn nicht
 * mehr.
 */
@Composable
fun StartupPermissionsDialog(
    settings: SettingsStore,
    seenBefore: Boolean,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var notifGranted by remember { mutableStateOf(hasNotificationPermission(ctx)) }
    var dozeIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(ctx)) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    // Akku-Opt + Notification-Status neu prüfen wenn wir aus Settings
    // zurückkommen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = hasNotificationPermission(ctx)
                dozeIgnored = isIgnoringBatteryOptimizations(ctx)
            }
        }
        lifecycle.addObserver(observer)
    }

    val allOk = notifGranted && dozeIgnored
    // Wenn schon alles erfüllt ist oder schon einmal gesehen UND nichts
    // mehr fehlt: nicht anzeigen.
    if (allOk) {
        LaunchedEffect(Unit) {
            scope.launch { settings.setStartupPromptDone(true) }
            onDismiss()
        }
        return
    }
    if (seenBefore && !shouldNagAgain(notifGranted, dozeIgnored)) {
        onDismiss(); return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cockpit vorbereiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Damit die Verbindung zum Scooter auch im Hintergrund " +
                        "stabil bleibt, brauchen wir zwei Sachen:",
                    style = MaterialTheme.typography.bodyMedium,
                )

                PermissionRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = "Benachrichtigungen erlauben",
                    body = "Zeigt dir live km/h + Akku im Status-Bar während du fährst.",
                    granted = notifGranted,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // < API 33: kein Runtime-Prompt nötig
                            notifGranted = true
                        }
                    },
                )

                PermissionRow(
                    icon = Icons.Filled.BatteryAlert,
                    title = "Akku-Optimierung aus",
                    body = "Verhindert dass Android die BLE-Verbindung nach " +
                        "ein paar Minuten Doze-Mode kappt.",
                    granted = dozeIgnored,
                    onAction = { openBatteryOptimizationSettings(ctx) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { settings.setStartupPromptDone(true) }
                onDismiss()
            }) { Text(if (allOk) "Weiter" else "Später") }
        },
    )
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon, contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (granted) {
                FilledTonalButton(onClick = {}, enabled = false,
                    modifier = Modifier.fillMaxWidth()) { Text("Erledigt") }
            } else {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text("Erlauben")
                }
            }
        }
    }
}

// --- platform checks --------------------------------------------------------

private fun hasNotificationPermission(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun openBatteryOptimizationSettings(ctx: Context) {
    // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS macht den direkten Dialog
    // ("EPF Cockpit darf im Hintergrund laufen — ja/nein"), aber Google Play
    // verbietet das ohne spezielle Begründung. Für ein selbst gehostetes
    // Sideload-APK ist es ok. Fallback wenn Intent nicht resolved: General
    // Battery-Settings öffnen.
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${ctx.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val resolved = direct.resolveActivity(ctx.packageManager) != null
    if (resolved) {
        ctx.startActivity(direct)
    } else {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

private fun shouldNagAgain(notifGranted: Boolean, dozeIgnored: Boolean): Boolean {
    // Bisher: jedes Mal nagen solange etwas fehlt. Wenn das genervt nervt,
    // kann hier eine "max 1× pro Woche"-Logik rein.
    return !notifGranted || !dozeIgnored
}
