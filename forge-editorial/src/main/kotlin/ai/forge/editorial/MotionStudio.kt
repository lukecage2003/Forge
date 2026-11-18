package ai.forge.editorial

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Paquet de transfert vers Primestone Motion Studio (§9.7).
 * Le format d'échange de Motion Studio n'étant pas encore spécifié, Forge produit un manifeste JSON
 * versionné (schemaVersion) et documenté dans docs/MOTION_STUDIO.md. Un adaptateur pourra le
 * convertir dès que l'API de Motion Studio sera connue.
 */
@Serializable
data class AuthorizedImage(
    val id: String,
    val description: String,
    /** Chemin local ou URI du fichier. */
    val location: String,
    /** Base de l'autorisation : « bibliothèque personnelle, usage citation », « licence X », « création originale »… */
    val authorization: String?,
    val authorized: Boolean,
)

@Serializable
data class VisualIdentity(
    val primaryColor: String = "#0A0A0A",
    val accentColor: String = "#D4A017",
    val fontTitle: String = "à définir",
    val fontBody: String = "à définir",
    val logo: String? = null,
)

@Serializable
data class MotionStudioPackage(
    val schemaVersion: Int = 1,
    val title: String,
    val scriptStatus: ScriptStatus,
    val script: ScriptDocument,
    val audioFile: String?,
    val storyboard: List<Sequence>,
    val images: List<AuthorizedImage>,
    val editingInstructions: List<String>,
    val targetFormat: String,
    val resolution: String,
    val durationSeconds: Int,
    val subtitlesSrt: String,
    val identity: VisualIdentity,
    val qualityPriorities: List<String>,
)

object MotionStudioExporter {
    sealed interface Result {
        data class Ready(val pkg: MotionStudioPackage, val json: String) : Result
        data class Blocked(val reasons: List<String>) : Result
    }

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** N'exporte qu'un dossier VALIDÉ et des images autorisées : sinon, explique ce qui bloque. */
    fun export(
        script: ScriptDocument,
        report: VerificationReport,
        plan: ProductionPlan,
        images: List<AuthorizedImage>,
        audioFile: String?,
        identity: VisualIdentity = VisualIdentity(),
        qualityPriorities: List<String> = listOf("Exactitude des pages affichées", "Lisibilité des sous-titres", "Synchronisation voix/image", "Rythme"),
    ): Result {
        val reasons = buildList {
            if (report.status != ScriptStatus.VERIFIED) add("Le script n'est pas vérifié (statut : ${report.status.label}).")
            images.filter { !it.authorized || it.authorization.isNullOrBlank() }
                .forEach { add("Image « ${it.description} » sans autorisation documentée.") }
            if (audioFile == null) add("Aucun fichier audio de narration fourni.")
        }
        if (reasons.isNotEmpty()) return Result.Blocked(reasons)
        val vertical = script.format.kind == FormatKind.SHORT_VERTICAL
        val pkg = MotionStudioPackage(
            title = script.title, scriptStatus = report.status, script = script, audioFile = audioFile,
            storyboard = plan.sequences, images = images, editingInstructions = plan.editingInstructions,
            targetFormat = if (vertical) "9:16" else "16:9", resolution = if (vertical) "1080x1920" else "1920x1080",
            durationSeconds = script.format.targetSeconds, subtitlesSrt = plan.subtitlesSrt,
            identity = identity, qualityPriorities = qualityPriorities,
        )
        return Result.Ready(pkg, json.encodeToString(pkg))
    }
}
