package eu.dns3d.epfcockpit.ui.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareUltralight
import android.util.Log
import java.io.IOException

/**
 * Schreibt UID auf "Magic NTAG215" / Gen2-Tags (UID-changeable).
 *
 * Im Gegensatz zu Gen1-Magic-Karten brauchen Gen2-Tags keine spezielle
 * Backdoor-Sequenz (z.B. 0x40/0x43 Unlock) — der Controller akzeptiert den
 * Page-0-Write direkt mit Standard-[MifareUltralight.writePage]. Bei normalen
 * NXP-Werks-NTAGs wirft writePage hier eine [IOException], weil Page 0/1/2
 * OTP/UID sind.
 *
 * Page-Layout (4 Byte/Page):
 *   Page 0: uid[0], uid[1], uid[2], BCC0 = 0x88 XOR uid[0..2]
 *   Page 1: uid[3], uid[4], uid[5], uid[6]
 *   Page 2: BCC1 = uid[3] XOR uid[4] XOR uid[5] XOR uid[6], internal, lock0, lock1
 *
 * WICHTIG: In Page 2 sind nur Byte 0 (BCC1) frei — Byte 1 ist internal, Byte 2/3
 * sind Lock-Bytes. Wir lesen Page 2 zuerst, patchen nur BCC1 und schreiben den
 * Rest 1:1 zurück. Sonst zerschiesst man den Lock-State des Tags.
 */
object MagicNfcWriter {

    private const val TAG = "MagicNfcWriter"

    /** Parst Hex-Eingabe (mit oder ohne Trennzeichen) zu N Bytes. */
    fun parseUidHex(input: String, expectedBytes: Int = 7): ByteArray? {
        val clean = input.trim()
            .replace(":", "")
            .replace("-", "")
            .replace(" ", "")
            .replace("\t", "")
        if (clean.length != expectedBytes * 2) return null
        return try {
            ByteArray(expectedBytes) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Schreibt nur die UID (Pages 0–2) auf ein Magic-Tag. */
    fun writeMagicUid(tag: Tag, uidHex: String): MagicWriteResult {
        val uid = parseUidHex(uidHex)
            ?: return MagicWriteResult.InvalidInput(
                "UID muss 7 Byte (14 Hex-Zeichen) lang sein."
            )
        val mu = MifareUltralight.get(tag)
            ?: return MagicWriteResult.NotMifareUltralight(tag.techList.toList())
        return try {
            mu.connect()
            writeUidInternal(mu, uid)
            val verified = runCatching { readUid(mu) }.getOrNull()
            if (verified != null && !verified.contentEquals(uid)) {
                Log.w(TAG, "UID-Verify Mismatch: wrote=${uid.hex()} read=${verified.hex()}")
                MagicWriteResult.VerifyMismatch(expected = uid.hex(), actual = verified.hex())
            } else {
                Log.d(TAG, "Magic-UID-Write OK: ${uid.hex()}")
                MagicWriteResult.Success(writtenPages = 3)
            }
        } catch (e: TagLostException) {
            Log.w(TAG, "TagLost während writeMagicUid", e)
            MagicWriteResult.Error("Tag zu früh entfernt — bitte ruhig anhalten und erneut versuchen.")
        } catch (e: IOException) {
            Log.e(TAG, "IOException während writeMagicUid — Tag ist evtl. nicht Magic", e)
            MagicWriteResult.NotMagic("Schreibfehler — Tag ist evtl. nicht Magic (Gen2). ${e.message ?: ""}".trim())
        } finally {
            runCatching { mu.close() }
        }
    }

    /**
     * Atomarer UID-Write. Setzt voraus, dass [mu] bereits verbunden ist.
     * Wirft [IOException]/[TagLostException] bei Fehlern — Caller fängt sie ab.
     */
    private fun writeUidInternal(mu: MifareUltralight, uid: ByteArray) {
        require(uid.size == 7) { "UID muss 7 Byte sein" }
        val bcc0 = (0x88 xor (uid[0].toInt() and 0xFF) xor
            (uid[1].toInt() and 0xFF) xor (uid[2].toInt() and 0xFF)).toByte()
        val bcc1 = (
            (uid[3].toInt() and 0xFF) xor (uid[4].toInt() and 0xFF) xor
                (uid[5].toInt() and 0xFF) xor (uid[6].toInt() and 0xFF)
        ).toByte()

        // Page 2 zuerst lesen, damit internal/lock-Bytes erhalten bleiben.
        // readPages liefert 16 Byte = 4 Pages ab Page 2; wir nehmen die ersten 4 Byte.
        val origPage2 = mu.readPages(2).copyOfRange(0, 4)
        Log.d(TAG, "Page2 vor Write: ${origPage2.hex()}")

        mu.writePage(0, byteArrayOf(uid[0], uid[1], uid[2], bcc0))
        mu.writePage(1, byteArrayOf(uid[3], uid[4], uid[5], uid[6]))
        // Nur BCC1 (Byte 0) patchen, internal (Byte 1) + lock0/1 (Byte 2/3) behalten.
        mu.writePage(2, byteArrayOf(bcc1, origPage2[1], origPage2[2], origPage2[3]))
    }

    /** Liest die 7-Byte UID aus Pages 0 + 1 zurück, für Verify nach Write. */
    private fun readUid(mu: MifareUltralight): ByteArray {
        val pages = mu.readPages(0) // 16 Byte = Pages 0..3
        return byteArrayOf(
            pages[0], pages[1], pages[2],
            pages[4], pages[5], pages[6], pages[7],
        )
    }

    private fun ByteArray.hex(): String =
        joinToString(separator = "") { "%02X".format(it) }
}

/** Ergebnis eines Magic-UID-Writes. */
sealed interface MagicWriteResult {
    data class Success(val writtenPages: Int) : MagicWriteResult
    data class InvalidInput(val message: String) : MagicWriteResult
    data class NotMifareUltralight(val techList: List<String>) : MagicWriteResult
    data class NotMagic(val message: String) : MagicWriteResult
    data class VerifyMismatch(val expected: String, val actual: String) : MagicWriteResult
    data class Error(val message: String) : MagicWriteResult
}
