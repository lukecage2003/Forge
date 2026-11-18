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

/** Ouvre une application par son nom courant (« galerie », « appareil photo », « Spotify »…). */
class OpenAppByNameTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.open_app", "Ouvre une application installée à partir de son nom (argument name).",
        AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val name = arguments.str("name") ?: return ToolResult.Error("Nom d'application manquant.")
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { ai.forge.actions.AppNameMatcher.App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
        val app = ai.forge.actions.AppNameMatcher.best(name, apps) ?: return ToolResult.Error("je ne trouve pas « $name » parmi vos applications.")
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return ToolResult.Error("« ${app.label} » ne peut pas être ouverte.")
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            ToolResult.Success("J'ouvre ${app.label}.")
        } catch (e: Exception) { ToolResult.Error("Android a refusé d'ouvrir ${app.label} (${e.message}).") }
    }
}

/** Minuteur (application Horloge, sans écran intermédiaire). */
class TimerTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.timer", "Lance un minuteur (argument seconds).", AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val s = arguments.int("seconds")?.takeIf { it in 1..86_400 } ?: return ToolResult.Error("Durée invalide.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); ToolResult.Success("Minuteur de ${human(s)} lancé.") }
        catch (e: ActivityNotFoundException) { ToolResult.Error("Aucune application d'horloge ne gère les minuteurs.") }
    }

    private fun human(s: Int) = when { s % 3600 == 0 -> "${s / 3600} h"; s % 60 == 0 -> "${s / 60} min"; s > 60 -> "${s / 60} min ${s % 60} s"; else -> "$s s" }
}

/** Lampe torche (flash de l'appareil photo). */
class TorchTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.torch", "Allume ou éteint la lampe torche (argument on).", AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val on = arguments["on"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val cm = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ToolResult.Error("aucun flash disponible.")
        return runCatching { cm.setTorchMode(id, on) }.fold({ ToolResult.Success(if (on) "Lampe allumée." else "Lampe éteinte.") },
            { ToolResult.Error("la lampe est occupée (${it.message}).") })
    }
}

/** Musique : recherche et lecture dans Spotify (ou l'application musicale par défaut). */
class PlayMusicTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.play_music", "Lance de la musique (argument query : artiste, titre, playlist).", AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val q = arguments.str("query").orEmpty()
        val base = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, q).putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val spotify = Intent(base).setPackage("com.spotify.music")
        for (i in listOf(spotify, base)) {
            try { context.startActivity(i); return ToolResult.Success(if (q.isBlank()) "Musique lancée." else "Je lance « $q ».") } catch (_: ActivityNotFoundException) {}
        }
        return ToolResult.Error("aucune application musicale ne répond.")
    }
}

/** Itinéraire vers une destination (Google Maps ou toute application de cartes). */
class NavigateTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android.navigate", "Ouvre l'itinéraire vers une destination (argument destination).", AutonomyLevel.L2_REVERSIBLE, reversible = true)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val d = arguments.str("destination") ?: return ToolResult.Error("Destination manquante.")
        val enc = android.net.Uri.encode(d)
        for (uri in listOf("google.navigation:q=$enc", "geo:0,0?q=$enc")) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return ToolResult.Success("Itinéraire vers $d.") }
            catch (_: ActivityNotFoundException) {}
        }
        return ToolResult.Error("aucune application de cartes installée.")
    }
}
