package ai.forge.app

import ai.forge.app.island.ForgeIsland
import ai.forge.app.island.IslandState
import ai.forge.app.ui.ForgeTheme
import ai.forge.voice.PresenceState
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-night-xxhdpi")
class IslandScreenshotTest {
    @Test fun islandStates() {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        a.setContent {
            ForgeTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFF8A8F99)).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    ForgeIsland(IslandState(PresenceState.STANDBY, 0f, "", "", expanded = false), {}, {})
                    ForgeIsland(IslandState(PresenceState.LISTENING, 0.8f, "Forge, rappelle-moi l'origine du vibranium", "", expanded = true), {}, {})
                    ForgeIsland(IslandState(PresenceState.THINKING, 0f, "rappelle-moi l'origine du vibranium", "", expanded = true), {}, {})
                    ForgeIsland(IslandState(PresenceState.RESPONDING, 0.5f, "", "Le vibranium vient d'une météorite tombée sur le Wakanda il y a des millénaires. C'est elle qui a façonné toute la civilisation wakandaise.", expanded = true), {}, {})
                }
            }
        }
        repeat(20) { ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/3-bulle-forge.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
