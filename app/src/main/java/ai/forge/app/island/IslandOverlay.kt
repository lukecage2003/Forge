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
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
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
                    ForgeIsland(IslandState(presence, level, heard, reply, big),
                        onTap = { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        onDismiss = { onDismiss() })
                }
            }
        }
        runCatching { wm.addView(v, params); view = v }
    }

    fun hide() { view?.let { runCatching { wm.removeViewImmediate(it) } }; view = null }
}
