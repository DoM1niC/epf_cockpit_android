package eu.dns3d.epfcockpit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import eu.dns3d.epfcockpit.service.ForegroundController
import javax.inject.Inject

@HiltAndroidApp
class EpfApplication : Application() {

    // Application-scoped statt nur in MainActivity: FGS-Lebenszyklus und
    // Vorder-/Hintergrund-Erkennung müssen auch gelten, wenn der Prozess über
    // Widget, Quick-Tile oder eine Notification-Action kalt startet.
    @Suppress("unused") @Inject lateinit var foregroundController: ForegroundController

    override fun onCreate() {
        createNotificationChannels()
        super.onCreate()  // Hilt injiziert hier → ForegroundController.init läuft
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // LOW = ongoing, no sound; ist eine Status-Notification, kein Ping.
        val channel = NotificationChannel(
            CHANNEL_COCKPIT,
            "Cockpit-Verbindung",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Zeigt Live-Telemetrie während die App im Hintergrund verbunden ist."
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        // DEFAULT, aber still: soll auf dem Sperrbildschirm sichtbar sein, ohne zu klingeln.
        val hints = NotificationChannel(
            CHANNEL_HINTS,
            "Verbindungs-Hinweise",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Hinweis, wenn die automatische Suche nach dem Roller ohne Erfolg endet."
            setSound(null, null)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannels(listOf(channel, hints))
    }

    companion object {
        const val CHANNEL_COCKPIT = "cockpit_connection"
        const val CHANNEL_HINTS = "cockpit_hints"
    }
}
