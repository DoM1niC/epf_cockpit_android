package eu.dns3d.epfcockpit.ble

/**
 * Sichtbarer Zustand einer Reconnect-Episode (nach unerwartetem Link-Abriss
 * oder bewusster Widget-/Tile-/Notification-Aktion). Solange nicht [Idle],
 * hält der ForegroundController den Foreground-Service am Leben.
 * Zeitangaben sind Wall-Clock-Millis (nur für die Anzeige).
 */
sealed interface ReconnectStatus {
    data object Idle : ReconnectStatus

    /** Aktive Versuche (2-Phasen-Loop). [nextAttemptWallMs] != null → Backoff läuft. */
    data class Searching(val attempt: Int, val max: Int, val nextAttemptWallMs: Long?) : ReconnectStatus

    /** Handy gesperrt: Sentinel wartet auf den Roller, bis spätestens [untilWallMs]. */
    data class Waiting(val untilWallMs: Long) : ReconnectStatus

    /**
     * Funk bewusst frei gelassen — entsperrt + andere App vorne (Handover) oder
     * der Roller hat schon einen fremden GATT-Link. Setzt fort, sobald das Handy
     * gesperrt oder die App geöffnet wird; spätestens [untilWallMs] endet die Episode.
     */
    data class Paused(val untilWallMs: Long) : ReconnectStatus

    /**
     * App vorne, aber die aktiven Versuche sind aufgebraucht (Cap): Funk frei,
     * keine weiteren Versuche. Sperren → Sentinel-Warten, App erneut öffnen oder
     * „Verbinden“ → frische Runde; spätestens [untilWallMs] endet die Episode.
     */
    data class Exhausted(val untilWallMs: Long) : ReconnectStatus

    /** Der Roller hält schon einen Link zu einer anderen App — wir warten, bis er frei ist. */
    data class ForeignLink(val untilWallMs: Long) : ReconnectStatus

    /** Bluetooth ist aus (Schalter, Flugmodus, Stack-Neustart) — warten, bis es wieder an ist. */
    data class BluetoothOff(val untilWallMs: Long) : ReconnectStatus
}

/** Grund für die Hinweis-Notification, wenn eine Episode im Hintergrund endet. */
enum class ReconnectHint { NOT_FOUND, FLAPPING }

/**
 * Reine Entscheidungsregel, wie eine laufende Episode gerade weitermacht.
 * Als Wahrheitstabelle getestet (ReconnectPolicyTest) — das ist die
 * empfindlichste Logik (Single-Link-Handover aus Commit 360c8d4).
 */
internal object ReconnectPolicy {

    enum class Mode {
        /** Bisheriger 2-Phasen-Loop mit Backoff und Cap. */
        ACTIVE,
        /** Sentinel-Warten — niemand kann den Roller gerade anderweitig wollen. */
        WAIT,
        /** Handover-Fenster: Funk frei lassen, bis gesperrt / App geöffnet (Episoden-Deadline). */
        PAUSE,
        /** Episode beenden, beim nächsten App-Start fortsetzen (Verhalten von 360c8d4). */
        DEFER,
        /** Bluetooth aus: funkstill warten, bis es wieder an ist (zeitlich begrenzt). */
        BLUETOOTH_OFF,
    }

    fun decide(
        bluetoothOn: Boolean,
        force: Boolean,
        appForeground: Boolean,
        reconnectWhileLocked: Boolean,
        backgroundEligible: Boolean,
        flapping: Boolean,
        deviceLocked: Boolean,
        ridingRecently: Boolean,
    ): Mode = when {
        force || appForeground -> if (bluetoothOn) Mode.ACTIVE else Mode.BLUETOOTH_OFF
        // Passiv im Hintergrund: was ohnehin nicht neu verbinden dürfte, wartet
        // auch nicht auf Bluetooth (sonst verspräche die Notification zu viel).
        !reconnectWhileLocked || !backgroundEligible || flapping -> Mode.DEFER
        !bluetoothOn -> Mode.BLUETOOTH_OFF
        deviceLocked || ridingRecently -> Mode.WAIT
        else -> Mode.PAUSE
    }

    /** Wartezeit nach dem n-ten fehlgeschlagenen Versuch (1-basiert). */
    fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 10_000L
        2 -> 20_000L
        3 -> 30_000L
        else -> 60_000L
    }
}
