package ai.forge.app.shortcut

import ai.forge.app.MainActivity
import ai.forge.app.R
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Intention commune : ouvrir Forge directement en écoute. */
internal fun voiceIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 7,
    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_VOICE, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** Tuile des réglages rapides (volet du haut) : un toucher, Forge écoute. */
class ForgeTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Forge"; if (Build.VERSION.SDK_INT >= 29) subtitle = "Parler"; updateTile() }
    }

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(voiceIntent(this))
        else @Suppress("DEPRECATION") startActivityAndCollapse(
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Widget d'écran d'accueil : la capsule « Parler à Forge ». */
class ForgeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_forge).apply { setOnClickPendingIntent(R.id.widget_root, voiceIntent(context)) }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
