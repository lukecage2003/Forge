package ai.forge.app.island

import ai.forge.app.AppContainer
import ai.forge.app.MainActivity
import ai.forge.app.ui.ForgeTheme
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fenêtre flottante de la bulle Forge, par-dessus les autres applications
 * (permission « Afficher par-dessus les autres applications », accordée par l'utilisateur).
 */
class IslandOverlay(private val context: Context, private val owner: LifecycleOwner, private val container: AppContainer) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    /** Vrai pendant un tour de conversation (bulle élargie) ; faux = capsule compacte de veille. */
    val expanded = MutableStateFlow(false)
    var onDismiss: () -> Unit = {}

    private val savedState = object : SavedStateRegistryOwner, LifecycleOwner by owner {
        val controller = SavedStateRegistryController.create(this).apply { performRestore(null) }
        override val savedStateRegistry get() = controller.savedStateRegistry
    }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val params = WindowManager.LayoutParams(
            // Fenêtre à la taille exacte de la bulle : les touchers autour (barre d'état, volet des
            // notifications, haut des applications) passent normalement.
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val geo = geometry()
        params.x = geo.centerOffsetPx
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(savedState)
            setContent {
                ForgeTheme {
                    val presence by container.voice.presence.collectAsState()
                    val level by container.voice.audioLevel.collectAsState()
                    val heard by container.voice.partialTranscript.collectAsState()
                    val reply by container.voice.responseText.collectAsState()
                    val big by expanded.collectAsState()
                    ForgeIsland(IslandState(presence, level, heard, reply, big), compactHeight = geo.compactHeight,
                        onTap = { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        onDismiss = { onDismiss() })
                }
            }
        }
        runCatching { wm.addView(v, params); view = v }
    }

    /** Position de la caméra frontale et hauteur de la barre d'état, pour coller au matériel. */
    private data class Geometry(val centerOffsetPx: Int, val compactHeight: androidx.compose.ui.unit.Dp)

    private fun geometry(): Geometry {
        val density = context.resources.displayMetrics.density
        return runCatching {
            val m = wm.currentWindowMetrics
            val insets = m.windowInsets
            val statusTop = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            val cutout = insets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
            val offset = cutout?.let { it.centerX() - m.bounds.width() / 2 } ?: 0
            // Pastille un peu moins haute que la barre d'état : elle entoure la caméra sans déborder.
            val h = ((statusTop / density) - 6f).coerceIn(26f, 40f)
            Geometry(offset, androidx.compose.ui.unit.Dp(h))
        }.getOrDefault(Geometry(0, androidx.compose.ui.unit.Dp(30f)))
    }

    fun hide() { view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null }
}
