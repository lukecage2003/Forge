package ai.forge.app.assist

import ai.forge.app.MainActivity
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * Forge comme « assistant numérique » du téléphone (Paramètres → Applications par défaut →
 * Assistant numérique). Un appui long sur le bouton latéral ouvre Forge directement en écoute.
 */
class ForgeVoiceInteractionService : VoiceInteractionService()

class ForgeVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            hide()
        }
    }
}

/**
 * Exigé par Android pour un assistant : Forge utilise la reconnaissance vocale du système,
 * ce service ne fait donc que signaler qu'il ne traite pas lui-même les requêtes externes.
 */
class ForgeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
