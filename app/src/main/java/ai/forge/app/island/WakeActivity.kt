package ai.forge.app.island

import ai.forge.app.ForgeApplication
import ai.forge.app.ui.ForgeTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Écran affiché quand « Forge » est entendu écran éteint ou verrouillé : il allume l'écran
 * par-dessus le verrouillage, montre la bulle (écoute, réflexion, réponse) puis se ferme seul.
 * Il n'ouvre rien d'autre : pour accéder à l'application, il faut déverrouiller.
 */
class WakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val c = (application as ForgeApplication).container
        setContent {
            ForgeTheme {
                val presence by c.voice.presence.collectAsState()
                val level by c.voice.audioLevel.collectAsState()
                val heard by c.voice.partialTranscript.collectAsState()
                val reply by c.voice.responseText.collectAsState()
                val active by c.wakeTurnActive.collectAsState()
                LaunchedEffect(active) { if (!active) finish() }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
                    ForgeIsland(IslandState(presence, level, heard, reply, expanded = true), onTap = {}, onDismiss = { c.endWakeTurn() })
                }
            }
        }
    }
}
