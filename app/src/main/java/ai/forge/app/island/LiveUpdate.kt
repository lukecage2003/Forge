package ai.forge.app.island

import ai.forge.app.AppContainer
import ai.forge.app.MainActivity
import ai.forge.app.R
import ai.forge.voice.PresenceState
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Intégration système (Android 16, « Live Updates ») : pendant une conversation, Forge publie une
 * notification « en cours » promue. Le système l'affiche à sa place — bannière en haut de l'écran,
 * puce dans la barre d'état, Now Bar de Samsung — sans rien dessiner par-dessus la barre d'état.
 */
class LiveUpdate(private val context: Context, private val container: AppContainer, private val endTurn: PendingIntent) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var job: Job? = null

    companion object {
        const val CHANNEL = "forge_live"
        const val ID = 4242
        /** Clé du drapeau « promouvoir en Live Update » (Android 16). */
        private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"

        fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= 36 &&
            runCatching { context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(false)

        /** Écran système où l'utilisateur autorise les Live Updates de Forge. */
        fun settingsIntent(context: Context) = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
            .putExtra("android.provider.extra.APP_PACKAGE", context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Forge en direct", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ce que Forge entend et répond, pendant une conversation."
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val v = container.voice
        job = scope.launch {
            combine(v.presence, v.partialTranscript, v.responseText) { p, heard, reply -> Triple(p, heard, reply) }
                .sample(350)
                .collect { (p, heard, reply) -> runCatching { nm.notify(ID, build(p, heard, reply)) } }
        }
    }

    fun stop() { job?.cancel(); job = null; runCatching { nm.cancel(ID) } }

    private fun build(p: PresenceState, heard: String, reply: String): Notification {
        val speaking = p == PresenceState.RESPONDING && reply.isNotBlank()
        val title = when (p) {
            PresenceState.THINKING -> "Je réfléchis…"
            PresenceState.EXECUTING -> "J'exécute…"
            PresenceState.RESPONDING -> "Forge"
            PresenceState.ERROR -> "Un problème est survenu"
            else -> "Je vous écoute"
        }
        val body = when {
            speaking -> reply.takeLast(300)
            heard.isNotBlank() -> "« ${heard.takeLast(160)} »"
            else -> "Parlez, je vous écoute."
        }
        val open = PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_forge_status)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Terminer", endTurn).build())
        if (Build.VERSION.SDK_INT >= 36) {
            b.setShortCriticalText(when (p) { PresenceState.THINKING -> "…"; PresenceState.RESPONDING -> "Forge"; else -> "Écoute" })
            b.extras.putBoolean(EXTRA_REQUEST_PROMOTED, true)
        }
        return b.build()
    }
}
