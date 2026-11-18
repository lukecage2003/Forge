package ai.forge.app.voice

import ai.forge.app.ForgeApplication
import ai.forge.app.MainActivity
import ai.forge.app.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.LifecycleService

/**
 * Service de premier plan « microphone » pour le mode mains libres.
 * Android exige une notification visible : l'utilisateur sait en permanence que le micro est actif
 * et peut tout arrêter depuis la notification (arrêt d'urgence).
 * Contrainte Android 14+ : ce service doit être démarré pendant que l'application est visible.
 */
class VoiceSessionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            (application as ForgeApplication).container.voice.stop()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        (application as ForgeApplication).container.voice.start(handsFree = true)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.voice_notification))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "forge_voice"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "ai.forge.app.STOP_VOICE"
        fun start(context: Context) = context.startForegroundService(Intent(context, VoiceSessionService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, VoiceSessionService::class.java).setAction(ACTION_STOP))
    }
}
