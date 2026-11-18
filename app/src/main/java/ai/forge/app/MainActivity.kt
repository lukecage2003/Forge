package ai.forge.app

import ai.forge.app.ui.ForgeApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ForgeApplication).container
        // Prépare le modèle local en arrière-plan : la première réponse arrive sans attendre le chargement.
        container.warmUpLocalModel()
        setContent { ForgeApp(container) }
    }
}
