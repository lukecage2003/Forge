package ai.forge.app.voice

import ai.forge.app.ForgeApplication
import ai.forge.app.MainActivity
import ai.forge.app.R
import ai.forge.app.island.IslandOverlay
import ai.forge.voice.WakeWordSpotter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Service de premier plan « microphone », deux modes :
 * - conversation mains libres (comme avant) ;
 * - veille « Forge » : détecteur de mot d'appel hors-ligne → bulle animée → question → réponse →
 *   retour en veille. La notification permanente rappelle que le micro est actif (exigence Android).
 */
class VoiceSessionService : LifecycleService() {
    private val container get() = (application as ForgeApplication).container
    private var overlay: IslandOverlay? = null
    private var wake: VoskWakeWord? = null
    private var standby = false
    private var inTurn = false
    private var appVisible = true
    private var lingering = false
    private var lingerJob: kotlinx.coroutines.Job? = null

    private val visibility = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appVisible = true; updateIsland() }
        override fun onStop(owner: LifecycleOwner) { appVisible = false; updateIsland() }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibility)
        overlay = IslandOverlay(this, this, container).also { it.onDismiss = { endTurnEarly() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Le cerveau est chargé dès la veille : au mot « Forge », il est déjà prêt.
        if (intent?.action != ACTION_STOP) container.warmUpLocalModel()
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_STANDBY -> {
                startForeground(NOTIF_ID, notification("Dites « Forge » pour me parler."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                standby = true
                if (!inTurn) { container.voice.stop(); startWake() }
            }
            else -> {
                startForeground(NOTIF_ID, notification(getString(R.string.voice_notification)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                wake?.stop()
                inTurn = false
                container.voice.start(handsFree = true)
            }
        }
        updateIsland()
        return START_STICKY
    }

    private fun startWake() {
        val dir = container.wakeModelDir() ?: run { container.voice.lastError.value = "Installez le mot d'appel dans Réglages."; return }
        val w = wake ?: VoskWakeWord(dir, WakeWordSpotter(container.settings.wakeStrictness), onWake = { onWake() },
            onError = { container.voice.lastError.value = it }).also { wake = it }
        w.start()
    }

    private fun onWake() {
        lingerJob?.cancel(); lingering = false
        inTurn = true
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35).startTone(ToneGenerator.TONE_PROP_ACK, 120) }
        overlay?.expanded?.value = true
        updateIsland()
        container.voice.startTurn(onEnded = {
            inTurn = false
            if (standby) startWake() else { shutdown(); return@startTurn }
            // La fin de la réponse reste lisible un instant, puis la bulle se retire.
            lingerJob?.cancel()
            lingerJob = lifecycleScope.launch {
                lingering = true; updateIsland()
                kotlinx.coroutines.delay(2_500)
                lingering = false; overlay?.expanded?.value = false; updateIsland()
            }
        })
    }

    private fun endTurnEarly() {
        lingerJob?.cancel(); lingering = false
        if (!inTurn) { updateIsland(); return }
        container.voice.stop(); inTurn = false
        overlay?.expanded?.value = false
        if (standby) startWake()
        updateIsland()
    }

    /** Bulle visible hors de l'application (dans Forge, l'écran affiche déjà l'orbe). */
    private fun updateIsland() {
        // Hors conversation, la veille reste discrète : rien au-dessus de la barre d'état, sauf si
        // l'utilisateur a choisi la pastille permanente.
        val talking = inTurn || lingering || container.voice.state.value != ai.forge.voice.VoiceState.IDLE
        val wanted = container.settings.islandEnabled && !appVisible && (talking || (standby && container.settings.islandIdleVisible))
        overlay?.expanded?.value = talking
        if (wanted) overlay?.show() else overlay?.hide()
    }

    private fun shutdown() {
        standby = false; inTurn = false
        wake?.release(); wake = null
        container.voice.stop()
        overlay?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibility)
        wake?.release(); overlay?.hide()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "forge_voice"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "ai.forge.app.STOP_VOICE"
        const val ACTION_STANDBY = "ai.forge.app.STANDBY"
        fun start(context: Context) = context.startForegroundService(Intent(context, VoiceSessionService::class.java))
        fun standby(context: Context) = context.startForegroundService(Intent(context, VoiceSessionService::class.java).setAction(ACTION_STANDBY))
        fun stop(context: Context) = context.startService(Intent(context, VoiceSessionService::class.java).setAction(ACTION_STOP))
    }
}
