package ai.forge.app

import ai.forge.app.ui.ForgeApp
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_START_VOICE = "ai.forge.app.START_VOICE"; const val EXTRA_PROMPT = "ai.forge.app.PROMPT" }

    /** Lancé comme assistant (bouton latéral) : demande d'écoute immédiate. */
    private fun wantsVoice(i: Intent?) = i?.getBooleanExtra(EXTRA_START_VOICE, false) == true ||
        i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (wantsVoice(intent)) (application as ForgeApplication).container.voiceRequested.value = true
        intent.getStringExtra(EXTRA_PROMPT)?.let { (application as ForgeApplication).container.prefill.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ForgeApplication).container
        // Prépare le modèle local en arrière-plan : la première réponse arrive sans attendre le chargement.
        container.warmUpLocalModel()
        // Veille « Forge » : relancée à l'ouverture (Android n'autorise son démarrage qu'app visible).
        if (container.settings.wakeStandby && container.wakeModelDir() != null &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ai.forge.app.voice.VoiceSessionService.standby(this)
        if (wantsVoice(intent)) container.voiceRequested.value = true
        intent?.getStringExtra(EXTRA_PROMPT)?.let { container.prefill.value = it }
        setContent { ForgeApp(container) }
    }
}
