package ai.forge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Captures d'écran de l'interface (rendu natif Robolectric, format Galaxy S25 ≈ 412 × 892 dp).
 * Sert à relire le design ; les images sont écrites dans app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-night-xxhdpi")
class ScreenshotTest {
    private fun shoot(name: String) {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            repeat(30) { ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
            sc.onActivity { a ->
                val v = a.window.decorView
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                val dir = File("build/screenshots").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun firstLaunchWithoutBrain() = shoot("1-accueil-sans-cerveau")

    @Test fun readyHome() {
        val c = ApplicationProvider.getApplicationContext<ForgeApplication>().container
        c.updateSettings(c.settings.copy(lanServerUrl = "http://192.168.1.10:11434/v1", lanModel = "qwen3:8b"))
        shoot("2-accueil-pret")
    }
}
