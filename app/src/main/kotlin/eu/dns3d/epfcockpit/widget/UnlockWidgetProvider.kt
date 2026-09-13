package eu.dns3d.epfcockpit.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import eu.dns3d.epfcockpit.R
import eu.dns3d.epfcockpit.service.EpfConnectionService

/**
 * Home-Screen-Widget: zwei Buttons — "Entsperren/Sperren" + Mode-Toggle.
 * Beide Buttons reflektieren den aktuellen Roller-Zustand: parking=true →
 * Button zeigt "Entsperren" und sendet beim Klick ACTION_TOGGLE_PARK, das
 * im FGS auf setParking(false) gemappt wird. Analog fuer Mode "Stand"/"Kick".
 *
 * Updates kommen vom [EpfConnectionService] via [pushState] — wir registrieren
 * keinen eigenen telemetry-Collector, weil der FGS sowieso schon einen hat.
 */
class UnlockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Erst-Render direkt nach Widget-Add: neutral, zeigt "Cockpit?" bis
        // der FGS einen echten State pushed.
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, State.UNKNOWN))
        }
    }

    companion object {
        /** Vom [EpfConnectionService] aufgerufen sobald sich telemetry/connection/mode aendert. */
        fun pushState(
            context: Context,
            connected: Boolean,
            authenticated: Boolean,
            parking: Boolean?,
            rideModeStand: Boolean,
        ) {
            val state = when {
                !connected || !authenticated -> State.DISCONNECTED
                parking == true -> State.PARKED
                parking == false -> State.UNPARKED
                else -> State.UNKNOWN
            }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, UnlockWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildViews(context, state, rideModeStand)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private enum class State { UNKNOWN, DISCONNECTED, PARKED, UNPARKED }

        private fun buildViews(
            context: Context,
            state: State,
            rideModeStand: Boolean = true,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_unlock)
            val parkLabel = when (state) {
                State.DISCONNECTED -> "Verbinden…"
                State.PARKED -> "Entsperren"
                State.UNPARKED -> "Sperren"
                State.UNKNOWN -> "Cockpit?"
            }
            val modeLabel = "Modus: " + if (rideModeStand) "Stand" else "Kick"
            views.setTextViewText(R.id.btn_unlock, parkLabel)
            views.setTextViewText(R.id.btn_mode, modeLabel)
            // Getrennt → nur verbinden. ACTION_TOGGLE_PARK würde bei unbekanntem
            // Park-Zustand nach dem Connect parken statt zu entsperren.
            val parkAction = if (state == State.DISCONNECTED) {
                EpfConnectionService.ACTION_CONNECT
            } else {
                EpfConnectionService.ACTION_TOGGLE_PARK
            }
            views.setOnClickPendingIntent(
                R.id.btn_unlock,
                buildPendingIntent(context, parkAction),
            )
            views.setOnClickPendingIntent(
                R.id.btn_mode,
                buildPendingIntent(context, EpfConnectionService.ACTION_TOGGLE_RIDE_MODE),
            )
            return views
        }

        private fun buildPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, EpfConnectionService::class.java).apply {
                this.action = action
            }
            return PendingIntent.getForegroundService(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
