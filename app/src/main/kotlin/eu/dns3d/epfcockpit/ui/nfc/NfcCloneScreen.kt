package eu.dns3d.epfcockpit.ui.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import android.util.Log
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val LOG_TAG = "NfcCloneScreen"

/**
 * Klont eine 7-Byte UID auf einen Magic-NTAG215 (Gen2 / UID-changeable).
 *
 * Flow:
 *   1. User tippt die zu schreibende UID ins Feld (optional vorbefüllt durch
 *      Aufrufer, z.B. die zuletzt gelesene Original-UID).
 *   2. User tippt "UID schreiben" — erst dann wird Reader-Mode aktiviert.
 *   3. Magic-Tag wird angehalten, wir schreiben Pages 0..2 und verifizieren.
 *
 * Reader-Mode wird hier nicht direkt beim Screen-Enter aktiviert, sondern erst
 * sobald der User den Schreib-Button drückt. So kann der User sich auf den
 * Klick fokussieren, ohne dass ein versehentlicher Tag-Touch sofort schreibt.
 *
 * Layout siehe [MagicNfcWriter] — Pages 0/1/2 mit BCC0/BCC1, Page 2 wird vorher
 * gelesen damit internal/lock-Bytes erhalten bleiben.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcCloneScreen(
    onBack: () -> Unit,
    preloadUidHex: String? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }

    var uidHex by remember {
        // Eingabe immer ohne Trennzeichen, Großbuchstaben.
        mutableStateOf(preloadUidHex?.let { sanitizeUid(it) } ?: "")
    }
    var phase by remember { mutableStateOf(ClonePhase.Editing) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultSuccess by remember { mutableStateOf(false) }
    // Bumpt sich beim erneuten Schreib-Versuch, retriggert das DisposableEffect.
    var attempt by remember { mutableStateOf(0) }

    val nfcAvailable by remember { derivedStateOf { adapter != null } }
    val nfcEnabled = adapter?.isEnabled == true
    val uidValid = MagicNfcWriter.parseUidHex(uidHex) != null

    // Reader-Mode nur aktiv solange phase == Waiting. Beim Tag-Touch wird die
    // UID geschrieben und phase wechselt auf Writing → Result. enableReaderMode
    // bleibt bis zum nächsten Recompose mit phase != Waiting an.
    DisposableEffect(activity, attempt, nfcEnabled, phase) {
        val act = activity
        val ad = adapter
        if (act != null && ad != null && nfcEnabled && phase == ClonePhase.Waiting) {
            val callback = NfcAdapter.ReaderCallback { tag ->
                // Reader-Callback läuft auf Binder-Thread — MifareUltralight-IO
                // ist blockierend, kein Coroutine-Overhead nötig. State-Writes
                // auf Compose-State sind thread-safe (Snapshot-System).
                phase = ClonePhase.Writing
                val outcome = MagicNfcWriter.writeMagicUid(tag, uidHex)
                Log.d(LOG_TAG, "writeMagicUid outcome=$outcome")
                resultMessage = describe(outcome)
                resultSuccess = outcome is MagicWriteResult.Success
                phase = if (resultSuccess) ClonePhase.Success else ClonePhase.Error
            }
            ad.enableReaderMode(
                act,
                callback,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }
        onDispose {
            if (act != null && ad != null) {
                runCatching { ad.disableReaderMode(act) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UID klonen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück",
                        )
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
            Spacer(Modifier.height(4.dp))
            when {
                !nfcAvailable -> InfoCard(
                    title = "NFC nicht verfügbar",
                    body = "Dieses Gerät hat keine NFC-Hardware. Klonen ist nicht möglich.",
                    leadingIcon = Icons.Filled.Nfc,
                )
                !nfcEnabled -> NfcDisabledCard(onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_NFC_SETTINGS).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    )
                })
                else -> {
                    InfoCard(
                        title = "Magic-NTAG215 klonen",
                        body = "Schreibt eine 7-Byte-UID auf ein Magic-NTAG215 (Gen2 / " +
                            "UID-changeable). Standard-NTAGs sind ab Werk auf Page 0–2 " +
                            "schreibgeschützt und können nicht geklont werden. BCC0/BCC1 " +
                            "werden automatisch berechnet, Lock-Bytes bleiben erhalten.",
                        leadingIcon = Icons.Filled.Nfc,
                    )

                    SectionCard(title = "Ziel-UID", leadingIcon = null) {
                        OutlinedTextField(
                            value = uidHex,
                            onValueChange = { input ->
                                // Nur Hex + Trennzeichen akzeptieren, sofort uppercase.
                                uidHex = input.filter { c ->
                                    c.isDigit() || c in 'A'..'F' || c in 'a'..'f' ||
                                        c == ':' || c == '-' || c == ' '
                                }.uppercase()
                            },
                            label = { Text("UID (14 Hex-Zeichen)") },
                            supportingText = {
                                Text(
                                    "7 Byte, z.B. 04A1B2C3D4E5F6 oder 04:A1:B2:C3:D4:E5:F6",
                                )
                            },
                            isError = uidHex.isNotBlank() && !uidValid,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    InfoCard(
                        title = "Vor dem Schreiben",
                        body = "Halte einen Magic-NTAG215 (Gen2) bereit. Nach Tap auf den " +
                            "Button öffnet sich Reader-Mode — dann den Magic-Tag ruhig an die " +
                            "NFC-Antenne halten. Erfolg wird nach dem Schreiben sofort " +
                            "via Read-Back verifiziert.",
                        leadingIcon = null,
                    )

                    Button(
                        onClick = {
                            resultMessage = null
                            resultSuccess = false
                            attempt++
                            phase = ClonePhase.Waiting
                        },
                        enabled = uidValid && phase == ClonePhase.Editing,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Tag halten und UID schreiben")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    when (phase) {
        ClonePhase.Waiting -> WaitingDialog(
            onCancel = { phase = ClonePhase.Editing },
        )
        ClonePhase.Writing -> WritingDialog()
        ClonePhase.Success -> ResultDialog(
            title = "UID geschrieben",
            message = resultMessage ?: "Die UID wurde erfolgreich geschrieben.",
            onDismiss = { phase = ClonePhase.Editing },
        )
        ClonePhase.Error -> ResultDialog(
            title = "Fehler beim Klonen",
            message = resultMessage ?: "Unbekannter Fehler",
            onDismiss = { phase = ClonePhase.Editing },
        )
        ClonePhase.Editing -> Unit
    }
}

private enum class ClonePhase { Editing, Waiting, Writing, Success, Error }

@Composable
private fun WaitingDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
        title = { Text("Magic-Tag jetzt anhalten") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Halte den Magic-NTAG215 ruhig an die NFC-Antenne.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

@Composable
private fun WritingDialog() {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Schreibe UID…") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        },
    )
}

@Composable
private fun ResultDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text = { Text(message) },
    )
}

@Composable
private fun NfcDisabledCard(onOpenSettings: () -> Unit) {
    SectionCard(title = "NFC ist deaktiviert", leadingIcon = Icons.Filled.Nfc) {
        Text(
            "NFC ist in den System-Einstellungen ausgeschaltet. Bitte einschalten " +
                "und danach zurück zu diesem Screen wechseln.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenSettings,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("NFC-Einstellungen öffnen") }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    leadingIcon: ImageVector?,
) {
    SectionCard(title = title, leadingIcon = leadingIcon) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionCard(
    title: String,
    leadingIcon: ImageVector?,
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

private fun describe(result: MagicWriteResult): String = when (result) {
    is MagicWriteResult.Success ->
        "UID erfolgreich auf das Magic-Tag geschrieben (${result.writtenPages} Pages, Verify OK)."
    is MagicWriteResult.InvalidInput -> result.message
    is MagicWriteResult.NotMifareUltralight ->
        "Tag ist kein MIFARE Ultralight / NTAG. Erkannte Techs: " +
            result.techList.joinToString(", ") { it.substringAfterLast('.') }
                .ifBlank { "(keine)" }
    is MagicWriteResult.NotMagic -> result.message
    is MagicWriteResult.VerifyMismatch ->
        "Verify fehlgeschlagen. Erwartet: ${result.expected}, gelesen: ${result.actual}."
    is MagicWriteResult.Error -> result.message
}

private fun sanitizeUid(input: String): String =
    input.filter { c ->
        c.isDigit() || c in 'A'..'F' || c in 'a'..'f' || c == ':' || c == '-' || c == ' '
    }.uppercase()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
