package eu.dns3d.epfcockpit.ui.nfc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * NFC-Diagnose-Screen: NTAG2xx (Mifare Ultralight kompatibel) auslesen
 * und UID + Tech-Liste + kompletten Page-Dump anzeigen.
 *
 * Zweck: herausfinden was die Original-Roller-Karte enthaelt, bevor
 * der User entscheidet ob ein UID-Klon auf eine Magic-NTAG215 reicht
 * oder ob auch der Speicherinhalt relevant ist.
 *
 * KEIN Schreibcode — die Hardware-NFC-API ist hier rein read-only
 * angebunden (`MifareUltralight.readPages`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcReadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }

    var result by remember { mutableStateOf<NfcReadResult?>(null) }
    var scanning by remember { mutableStateOf(true) }
    // Bumpt sich bei "Neuen Tag lesen" — re-triggert das DisposableEffect, das
    // Reader-Mode neu enabled. Sonst muesste der User raus und wieder rein.
    var attempt by remember { mutableStateOf(0) }

    val nfcAvailable by remember {
        derivedStateOf { adapter != null }
    }
    // adapter.isEnabled liest den System-Toggle, das ist nicht Compose-State —
    // wir checken es bei jedem attempt (Recompose) erneut. Reicht, weil der
    // User nach Aenderung der NFC-Settings sowieso ueber das Hinweis-Banner
    // hier rein navigieren und auf "Neuen Tag lesen" tippen wuerde.
    val nfcEnabled = adapter?.isEnabled == true

    DisposableEffect(activity, attempt, nfcEnabled) {
        val act = activity
        val ad = adapter
        if (act != null && ad != null && nfcEnabled && scanning) {
            val callback = NfcAdapter.ReaderCallback { tag ->
                // Reader-Callback laeuft auf einem Binder-Thread — IO + UI-State
                // muessen getrennt sein. State-Writes auf Compose-MutableStates
                // sind thread-safe (snapshot system), Recompose wird automatisch
                // auf den Main-Thread getriggert.
                val read = readTagBlocking(tag)
                result = read
                scanning = false
                // Reader-Mode laeuft weiter bis disableReaderMode() — wir lassen
                // ihn an, falls der User mehrmals tippt, aber zeigen ab jetzt
                // das Ergebnis. Beim Klick auf "Neuen Tag lesen" bumpen wir
                // attempt und das DisposableEffect re-enabled sauber.
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
                title = { Text("NFC-Diagnose") },
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
                !nfcAvailable -> NfcUnavailableCard()
                !nfcEnabled -> NfcDisabledCard(onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_NFC_SETTINGS).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                        ),
                    )
                })
                result == null -> WaitingForTagCard()
                else -> ReadResultView(
                    result = result!!,
                    onScanAgain = {
                        result = null
                        scanning = true
                        attempt++
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/* ---------- UI States ---------- */

@Composable
private fun WaitingForTagCard() {
    SectionCard(title = "Bereit zum Scannen", leadingIcon = Icons.Filled.Nfc) {
        Text(
            "Halte einen NFC-Tag an die Rückseite des Telefons (NFC-Antenne " +
                "sitzt meist mittig oder im oberen Drittel). Unterstützte " +
                "Tags: NTAG2xx / Mifare Ultralight.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tipp: Schutzhülle abnehmen, der Empfang ist sonst oft zu schwach.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NfcUnavailableCard() {
    SectionCard(title = "NFC nicht verfügbar", leadingIcon = Icons.Filled.Nfc) {
        Text(
            "Dieses Gerät hat keine NFC-Hardware. Der Diagnose-Screen ist auf " +
                "diesem Telefon nicht nutzbar.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
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
private fun ReadResultView(result: NfcReadResult, onScanAgain: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard(title = "UID", leadingIcon = Icons.Filled.Nfc) {
        Text(
            result.uidHex.ifBlank { "-" },
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${result.uidBytes} Byte UID",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            FilledTonalButton(
                onClick = {
                    copyToClipboard(ctx, "NFC UID", result.uidHex)
                    Toast.makeText(ctx, "UID kopiert", Toast.LENGTH_SHORT).show()
                },
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("UID kopieren")
            }
        }
    }

    SectionCard(title = "Technologien", leadingIcon = null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            result.techList.forEach { tech ->
                AssistChip(
                    onClick = {},
                    label = { Text(tech.substringAfterLast('.')) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Voller Name: " + result.techList.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(title = "Speicher", leadingIcon = null) {
        when {
            result.error != null -> {
                Text(
                    result.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            result.pages.isEmpty() -> {
                Text(
                    "Tag-Typ nicht unterstützt — nur NTAG2xx / Mifare Ultralight " +
                        "können ausgelesen werden. Andere Tag-Typen (z.B. " +
                        "Mifare Classic, FeliCa, DESFire) sind hier nicht " +
                        "implementiert. UID + Tech-Liste oben sind trotzdem gültig.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                val total = result.pages.size
                Text(
                    "$total Pages à 4 Bytes = ${total * 4} Bytes gelesen. " +
                        guessTagType(total),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        formatHexDump(result.pages),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        val dump = buildString {
                            appendLine("UID: ${result.uidHex}")
                            appendLine("Tech: ${result.techList.joinToString(", ")}")
                            appendLine()
                            append(formatHexDump(result.pages))
                        }
                        copyToClipboard(ctx, "NFC Hex Dump", dump)
                        Toast.makeText(ctx, "Hex-Dump kopiert", Toast.LENGTH_SHORT).show()
                    },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Gesamten Dump kopieren")
                }
            }
        }
    }

    Button(
        onClick = onScanAgain,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.size(6.dp))
        Text("Neuen Tag lesen")
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

/* ---------- Tag-IO ---------- */

private data class NfcReadResult(
    val uidHex: String,
    val uidBytes: Int,
    val techList: List<String>,
    val pages: ByteArray,   // alle gelesenen Pages konkateniert, 4 Byte/Page
    val error: String?,
)

/**
 * Synchron auf dem NFC-Binder-Thread. `connect()` und `readPages()`
 * sind blockierende Operationen, der Callback laeuft sowieso nicht
 * auf dem Main-Thread — kein Coroutine-Overhead noetig.
 */
private fun readTagBlocking(tag: Tag): NfcReadResult {
    val uidHex = tag.id.toHex()
    val techList = tag.techList.toList()

    val mfu = MifareUltralight.get(tag)
    if (mfu == null) {
        // Kein Ultralight-kompatibler Tag — wir liefern trotzdem UID + Tech-Liste
        return NfcReadResult(
            uidHex = uidHex,
            uidBytes = tag.id.size,
            techList = techList,
            pages = ByteArray(0),
            error = null,
        )
    }

    return try {
        mfu.connect()
        // Wir wissen die Page-Count nicht zuverlaessig vorher (NTAG213/215/216
        // unterscheiden sich) — lesen blockweise (readPages liefert immer 16
        // Bytes = 4 Pages) bis es einen Fehler gibt. Hard-Cap auf 256 Pages
        // (1024 Bytes), genug fuer alle NTAG-Varianten inkl. NTAG216 (231 Pages).
        val out = ArrayList<Byte>(540)
        var page = 0
        val maxPage = 256
        while (page < maxPage) {
            val chunk: ByteArray = try {
                mfu.readPages(page)
            } catch (t: Throwable) {
                // Erreicht das Ende des Speichers oder eine geschuetzte Page.
                // Bei NTAG215 endet das normalerweise an Page 135 (Config-Pages
                // ab 130, danach faengt der Controller an zu meckern).
                break
            }
            out.addAll(chunk.toList())
            page += 4
        }
        NfcReadResult(
            uidHex = uidHex,
            uidBytes = tag.id.size,
            techList = techList,
            pages = out.toByteArray(),
            error = null,
        )
    } catch (t: Throwable) {
        NfcReadResult(
            uidHex = uidHex,
            uidBytes = tag.id.size,
            techList = techList,
            pages = ByteArray(0),
            error = "Lesen fehlgeschlagen: ${t.javaClass.simpleName} — ${t.message ?: "kein Detail"}",
        )
    } finally {
        runCatching { mfu.close() }
    }
}

/* ---------- Helpers ---------- */

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }

private fun formatHexDump(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder(bytes.size * 4)
    var page = 0
    var i = 0
    while (i < bytes.size) {
        val end = (i + 4).coerceAtMost(bytes.size)
        sb.append("Page ").append("%03d".format(page)).append(": ")
        for (j in i until end) {
            sb.append("%02X".format(bytes[j]))
            if (j < end - 1) sb.append(' ')
        }
        sb.append('\n')
        page++
        i = end
    }
    return sb.toString().trimEnd()
}

private fun guessTagType(pageCount: Int): String = when {
    pageCount >= 230 -> "Sieht nach NTAG216 aus (924 Byte User-Memory)."
    pageCount >= 134 -> "Sieht nach NTAG215 aus (504 Byte User-Memory) — passt zum Pulse-Plus-Original."
    pageCount >= 44 -> "Sieht nach NTAG213 aus (144 Byte User-Memory)."
    pageCount >= 16 -> "Sieht nach Mifare Ultralight C / kleinerem NTAG aus."
    else -> "Nur wenige Pages lesbar — Tag-Typ unklar."
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
