package eu.dns3d.epfcockpit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EpfApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_COCKPIT = "cockpit_connection"
    }
}
