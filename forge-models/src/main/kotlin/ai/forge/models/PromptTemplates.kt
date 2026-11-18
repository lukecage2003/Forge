package ai.forge.models

import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role

/**
 * Gabarits de conversation des modèles locaux : chaque famille attend un balisage précis.
 * Un mauvais gabarit dégrade fortement les réponses, d'où la détection par nom de fichier.
 */
enum class PromptTemplate {
    GEMMA, CHATML;

    fun render(messages: List<ChatMessage>): String = when (this) {
        GEMMA -> buildString {
            val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
            var first = true
            messages.filter { it.role != Role.SYSTEM }.forEach { m ->
                val role = if (m.role == Role.ASSISTANT) "model" else "user"
                append("<start_of_turn>").append(role).append('\n')
                if (first && role == "user" && system.isNotBlank()) { append(system).append("\n\n"); first = false }
                append(m.content).append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }
        CHATML -> buildString {
            val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
            if (system.isNotBlank()) append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            messages.filter { it.role != Role.SYSTEM }.forEach { m ->
                append("<|im_start|>").append(if (m.role == Role.ASSISTANT) "assistant" else "user").append('\n')
                append(m.content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    companion object {
        /** Qwen, SmolLM, Phi récents → ChatML ; Gemma → Gemma ; par défaut ChatML (le plus répandu). */
        fun forModelFile(name: String): PromptTemplate = if (name.lowercase().contains("gemma")) GEMMA else CHATML
    }
}

/** Modèle local proposé au téléchargement : libre de droits, sans compte, empreinte connue. */
data class DownloadableModel(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val ramMb: Int,
) {
    companion object {
        /**
         * Choix par défaut : Qwen 2.5 1,5B Instruct (Alibaba), licence Apache 2.0, format MediaPipe,
         * quantifié 8 bits, contexte 4096. Bon compromis taille/qualité/français pour un S25.
         */
        val RECOMMENDED = DownloadableModel(
            id = "qwen2.5-1.5b-q8",
            displayName = "Qwen 2.5 — 1,5 milliard de paramètres",
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            sizeBytes = 1_598_556_720,
            sha256 = "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
            license = "Apache 2.0",
            ramMb = 2_200,
        )
    }
}
