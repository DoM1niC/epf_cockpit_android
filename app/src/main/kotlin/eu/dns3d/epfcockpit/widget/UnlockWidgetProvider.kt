package eu.dns3d.epfcockpit.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import eu.dns3d.epfcockpit.R
import eu.dns3d.epfcockpit.service.EpfConnectionService

/**
 * Home-Screen-Widget: zwei Buttons — "Entsperren" und "Modus" (Stand-Stop /
 * Kick-Start). Beide tappen direkt in den FGS via foreground-Service-Intent;
 * das eigentliche BLE-Senden + ggf. Auf-Verbindung-Warten passiert dort.
 */
class UnlockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_unlock)
            views.setOnClickPendingIntent(
                R.id.btn_unlock,
                buildPendingIntent(context, EpfConnectionService.ACTION_UNLOCK),
            )
            views.setOnClickPendingIntent(
                R.id.btn_mode,
                buildPendingIntent(context, EpfConnectionService.ACTION_TOGGLE_RIDE_MODE),
            )
            appWidgetManager.updateAppWidget(id, views)
        }
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
