package ai.forge.app.settings

import ai.forge.core.model.AutonomyLevel
import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory
import android.content.Context

/** Réglages non secrets (moteurs, autonomie, voix). Les clés API vont dans [SecureKeyStore]. */
data class ForgeSettings(
    val localModelPath: String? = null,
    /** Serveur compatible OpenAI sur le réseau local (ex. Ollama sur un PC : http://192.168.1.10:11434/v1). */
    val lanServerUrl: String? = null,
    val lanModel: String? = null,
    val openAiCompatibleUrl: String? = null,
    val openAiCompatibleModel: String? = null,
    val anthropicModel: String = "claude-sonnet-5-5",
    val localOnly: Boolean = false,
    val autonomyCeiling: AutonomyLevel = AutonomyLevel.L2_REVERSIBLE,
    val voiceName: String? = null,
    val speechRate: Float = 1.0f,
    val showTranscript: Boolean = true,
    /** Ce que les fournisseurs cloud peuvent recevoir (§14) — restrictif par défaut. */
    val cloudMaxDataClass: DataClass = DataClass.GENERAL,
    val cloudTasks: Set<TaskCategory> = setOf(TaskCategory.CONVERSATION, TaskCategory.REASONING, TaskCategory.WRITING),
    val cloudDailyRequestLimit: Int? = 100,
    val cloudMonthlyBudget: Double? = null,
    /** Recherche Web : SearXNG (idéalement auto-hébergé) ; Brave nécessite une clé (stockée chiffrée). */
    val searxngUrl: String? = null,
    /** Mains libres : ne répondre que lorsqu'on l'appelle « Forge ». */
    val requireWakeWord: Boolean = false,
    /** Écouter pendant que Forge parle même sans écouteurs (risque d'écho). */
    val bargeInWithoutHeadset: Boolean = false,
    /** Voix naturelles en ligne autorisées (repli automatique sur une voix locale hors-ligne). */
    val networkVoices: Boolean = true,
) {
    companion object {
        private const val FILE = "forge_settings"
        fun load(context: Context): ForgeSettings {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return ForgeSettings(
                localModelPath = p.getString("localModelPath", null),
                lanServerUrl = p.getString("lanServerUrl", null),
                lanModel = p.getString("lanModel", null),
                openAiCompatibleUrl = p.getString("oaiUrl", null),
                openAiCompatibleModel = p.getString("oaiModel", null),
                anthropicModel = p.getString("anthropicModel", null) ?: "claude-sonnet-5-5",
                localOnly = p.getBoolean("localOnly", false),
                autonomyCeiling = runCatching { AutonomyLevel.valueOf(p.getString("autonomy", null)!!) }.getOrDefault(AutonomyLevel.L2_REVERSIBLE),
                voiceName = p.getString("voiceName", null),
                speechRate = p.getFloat("speechRate", 1.0f),
                showTranscript = p.getBoolean("showTranscript", true),
                cloudMaxDataClass = runCatching { DataClass.valueOf(p.getString("cloudData", null)!!) }.getOrDefault(DataClass.GENERAL),
                cloudTasks = p.getStringSet("cloudTasks", null)?.mapNotNull { runCatching { TaskCategory.valueOf(it) }.getOrNull() }?.toSet()
                    ?: setOf(TaskCategory.CONVERSATION, TaskCategory.REASONING, TaskCategory.WRITING),
                cloudDailyRequestLimit = p.getInt("cloudDaily", 100).takeIf { it > 0 },
                cloudMonthlyBudget = p.getFloat("cloudBudget", -1f).takeIf { it >= 0 }?.toDouble(),
                searxngUrl = p.getString("searxngUrl", null),
                requireWakeWord = p.getBoolean("wakeWord", false),
                bargeInWithoutHeadset = p.getBoolean("bargeIn", false),
                networkVoices = p.getBoolean("networkVoices", true),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("localModelPath", localModelPath)
            .putString("lanServerUrl", lanServerUrl).putString("lanModel", lanModel)
            .putString("oaiUrl", openAiCompatibleUrl).putString("oaiModel", openAiCompatibleModel)
            .putString("anthropicModel", anthropicModel)
            .putBoolean("localOnly", localOnly)
            .putString("autonomy", autonomyCeiling.name)
            .putString("voiceName", voiceName).putFloat("speechRate", speechRate)
            .putBoolean("showTranscript", showTranscript)
            .putString("cloudData", cloudMaxDataClass.name)
            .putStringSet("cloudTasks", cloudTasks.map { it.name }.toSet())
            .putInt("cloudDaily", cloudDailyRequestLimit ?: 0)
            .putFloat("cloudBudget", cloudMonthlyBudget?.toFloat() ?: -1f)
            .putString("searxngUrl", searxngUrl)
            .putBoolean("wakeWord", requireWakeWord).putBoolean("bargeIn", bargeInWithoutHeadset).putBoolean("networkVoices", networkVoices)
            .apply()
    }
}
