package eu.dns3d.epfcockpit.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.dns3d.epfcockpit.EpfApplication
import eu.dns3d.epfcockpit.MainActivity
import eu.dns3d.epfcockpit.R
import eu.dns3d.epfcockpit.ble.ReconnectHint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wegwischbarer Hinweis, wenn eine Reconnect-Episode im Hintergrund ohne Erfolg
 * endet. Die „Verbinden“-Action funktioniert direkt vom Sperrbildschirm (eine
 * Notification-Action darf einen FGS aus dem Hintergrund starten), ohne App-Öffnen.
 *
 * Bewusst NICHT ongoing und nicht aus dem Service gepostet — kann also nicht mit
 * stopService um die Wette laufen und als verwaiste Notification stehen bleiben.
 */
@Singleton
class ReconnectHintNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun show(hint: ReconnectHint) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val (title, text) = when (hint) {
            ReconnectHint.NOT_FOUND ->
                "Roller nicht erreichbar" to "Automatische Suche beendet. Tippe auf „Verbinden“."
            ReconnectHint.FLAPPING ->
                "Verbindung bricht wiederholt ab" to "Automatisches Verbinden pausiert. Tippe auf „Verbinden“."
        }
        val connect = PendingIntent.getForegroundService(
            context, HINT_ID,
            Intent(context, EpfConnectionService::class.java).setAction(EpfConnectionService.ACTION_CONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, HINT_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, EpfApplication.CHANNEL_HINTS)
            .setSmallIcon(R.drawable.ic_notif_scooter)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .addAction(0, "Verbinden", connect)
            .build()
        try {
            nm.notify(HINT_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS zwischenzeitlich entzogen — Hinweis entfällt.
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(HINT_ID)
    }

    companion object {
        const val HINT_ID = 4712
    }
}
