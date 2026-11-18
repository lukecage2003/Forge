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
    /** Débit conversationnel : 1,0 (défaut d'Android) sonne traînant en français. */
    val speechRate: Float = 1.25f,
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
    /** Notification de briefing chaque matin. */
    val morningBriefing: Boolean = false,
    /** Chemin de la voix neuronale hors-ligne installée (null = voix du système). */
    val neuralVoicePath: String? = null,
    /** Bulle animée par-dessus les applications pendant que Forge écoute ou parle. */
    val islandEnabled: Boolean = true,
    /** Veille : Forge se réveille quand on dit « Forge ». */
    val wakeStandby: Boolean = false,
    /** Exigence du détecteur (0,4 sensible … 0,8 strict). */
    val wakeStrictness: Double = 0.55,
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
                // Migration v0.6 : l'ancien débit par défaut (1,0) était trop lent → 1,25.
                speechRate = p.getFloat("speechRate", 1.25f).let { if (!p.getBoolean("rateMigrated", false) && it == 1.0f) 1.25f else it },
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
                morningBriefing = p.getBoolean("briefing", false),
                neuralVoicePath = p.getString("neuralVoice", null),
                islandEnabled = p.getBoolean("island", true),
                wakeStandby = p.getBoolean("wakeStandby", false),
                wakeStrictness = p.getFloat("wakeStrictness", 0.55f).toDouble(),
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
            .putBoolean("wakeWord", requireWakeWord).putBoolean("bargeIn", bargeInWithoutHeadset).putBoolean("networkVoices", networkVoices).putBoolean("rateMigrated", true).putBoolean("briefing", morningBriefing).putString("neuralVoice", neuralVoicePath)
            .putBoolean("island", islandEnabled).putBoolean("wakeStandby", wakeStandby).putFloat("wakeStrictness", wakeStrictness.toFloat())
            .apply()
    }
}
