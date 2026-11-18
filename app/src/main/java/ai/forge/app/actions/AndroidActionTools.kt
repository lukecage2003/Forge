package ai.forge.app.actions

import ai.forge.core.model.AutonomyLevel
import ai.forge.security.Tool
import ai.forge.security.ToolResult
import ai.forge.security.ToolSpec
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Actions Android (§1.3). Chaque action passe par des Intents publics : l'application cible
 * affiche le résultat et l'utilisateur garde la main. Aucune action n'est simulée : le résultat
 * renvoyé décrit exactement ce qui a été demandé au système.
 */
private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

class SetAlarmTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.alarm", "Programme un réveil (heure, minute, libellé).",
        AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val hour = arguments.int("hour") ?: return ToolResult.Error("Heure manquante.")
        val minute = arguments.int("minute") ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, arguments.str("label") ?: "Forge")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Demande de réveil à %02dh%02d transmise à l'application Horloge (à confirmer à l'écran).".format(hour, minute))
    }

    private fun launch(intent: Intent, ok: String) = try {
        context.startActivity(intent); ToolResult.Success(ok)
    } catch (e: ActivityNotFoundException) { ToolResult.Error("Aucune application ne gère cette action.") }
}

class CalendarDraftTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.calendar", "Prépare un événement d'agenda (l'utilisateur valide dans l'agenda).",
        AutonomyLevel.L1_PROPOSE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments.str("title") ?: return ToolResult.Error("Titre manquant.")
        val begin = arguments["beginMs"]?.jsonPrimitive?.longOrNull
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .apply { begin?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult.Success("Brouillon d'événement « $title » ouvert dans l'agenda — rien n'est enregistré sans votre validation.")
        } catch (e: ActivityNotFoundException) { ToolResult.Error("Aucune application d'agenda.") }
    }
}

class ShareTextTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.share", "Ouvre la feuille de partage avec un texte.",
        AutonomyLevel.L1_PROPOSE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments.str("text") ?: return ToolResult.Error("Texte manquant.")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.Success("Feuille de partage ouverte ; l'envoi reste à votre initiative.")
    }
}

class OpenAppTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.intent", "Ouvre une application installée par son nom de package.",
        AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val pkg = arguments.str("package") ?: return ToolResult.Error("Package manquant.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.Error("Application « $pkg » introuvable.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.Success("Application $pkg ouverte.")
    }
}
