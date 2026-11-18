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

/** Runtime capable d'exécuter un fichier de modèle. */
enum class LocalRuntime { LITERT_LM, MEDIAPIPE;
    companion object { fun forFile(name: String) = if (name.endsWith(".litertlm", true)) LITERT_LM else MEDIAPIPE }
}

/** Modèle local proposé au téléchargement : libre de droits, sans compte, empreinte connue. */
data class DownloadableModel(
    val id: String,
    val displayName: String,
    val tagline: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val ramMb: Int,
    /** Qualité de raisonnement estimée (1–5), utilisée par le routeur. */
    val quality: Int,
    val contextTokens: Int,
) {
    val runtime: LocalRuntime get() = LocalRuntime.forFile(fileName)

    companion object {
        private const val HF = "https://huggingface.co/litert-community"

        /** Gemma 4 E4B (Google, Apache 2.0), version GPU : le plus capable qui tienne confortablement sur un S25. */
        val GEMMA4_E4B = DownloadableModel(
            id = "gemma4-e4b-gpu", displayName = "Gemma 4 · E4B", tagline = "Le plus intelligent — recommandé pour le Galaxy S25",
            url = "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm",
            fileName = "gemma-4-E4B-it-gpu.litertlm", sizeBytes = 3_310_938_740,
            sha256 = "ea168f5a601b543e02712f9381f26b06ddc7d4cbe2406410387c82d69b995a60",
            license = "Apache 2.0", ramMb = 4_600, quality = 4, contextTokens = 8_192,
        )

        /** Gemma 4 E2B, version GPU : plus léger et plus rapide. */
        val GEMMA4_E2B = DownloadableModel(
            id = "gemma4-e2b-gpu", displayName = "Gemma 4 · E2B", tagline = "Le plus rapide",
            url = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm",
            fileName = "gemma-4-E2B-it-gpu.litertlm", sizeBytes = 2_340_870_772,
            sha256 = "d3cfec6ae2e027a994e51366d44dbf522ec7531a4a35fbbfcd3e9cc9b927afc1",
            license = "Apache 2.0", ramMb = 3_300, quality = 3, contextTokens = 8_192,
        )

        /** Qwen 2.5 1,5B (MediaPipe) : très léger, gardé pour les appareils modestes. */
        val QWEN25_1_5B = DownloadableModel(
            id = "qwen2.5-1.5b-q8", displayName = "Qwen 2.5 · 1,5B", tagline = "Léger",
            url = "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task", sizeBytes = 1_598_556_720,
            sha256 = "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3",
            license = "Apache 2.0", ramMb = 2_200, quality = 2, contextTokens = 4_096,
        )

        /** Voix neuronale française hors-ligne : Kyutai Pocket TTS (voix « Estelle »), licence CC-BY 4.0. */
        val POCKET_TTS_FR = DownloadableModel(
            id = "pocket-tts-fr", displayName = "Voix Estelle (Pocket TTS)", tagline = "Voix neuronale naturelle, hors-ligne",
            url = "$HF/pocket-tts/resolve/main/pocket_tts_fr_int8.litertlm", fileName = "pocket_tts_fr_int8.litertlm",
            sizeBytes = 127_090_696, sha256 = "6885614825d59fa5b48eb32d5f79ee11b4ea2b0a603d2508354ba88005b200eb",
            license = "CC-BY 4.0 (Kyutai)", ramMb = 400, quality = 0, contextTokens = 0,
        )

        /** Détecteur du mot d'appel « Forge » : Vosk, petit modèle français (Apache 2.0), 42 Mo. */
        val VOSK_FR = DownloadableModel(
            id = "vosk-fr-small", displayName = "Mot d'appel « Forge »", tagline = "Écoute légère, hors-ligne",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", fileName = "vosk-model-small-fr-0.22.zip",
            sizeBytes = 42_233_323, sha256 = "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad",
            license = "Apache 2.0 (Alpha Cephei)", ramMb = 150, quality = 0, contextTokens = 0,
        )

        /**
         * Reconnaissance vocale Parakeet TDT 0.6B v3 (NVIDIA, CC-BY 4.0), export sherpa-onnx int8 :
         * français parmi 25 langues, ponctuation et majuscules incluses. Quatre fichiers, 670 Mo.
         */
        private const val PARAKEET_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
        private fun parakeet(file: String, size: Long, sha: String) = DownloadableModel(
            id = "parakeet-v3-$file", displayName = "Reconnaissance Parakeet", tagline = "Comprend mieux, hors-ligne",
            url = PARAKEET_BASE + file, fileName = file, sizeBytes = size, sha256 = sha,
            license = "CC-BY 4.0 (NVIDIA) · sherpa-onnx Apache 2.0", ramMb = 900, quality = 0, contextTokens = 0,
        )
        val PARAKEET_FILES = listOf(
            parakeet("encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            parakeet("decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            parakeet("joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
            parakeet("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )

        val RECOMMENDED = GEMMA4_E4B
        val CATALOG = listOf(GEMMA4_E4B, GEMMA4_E2B, QWEN25_1_5B)
        fun byFileName(name: String) = CATALOG.firstOrNull { it.fileName == name }
    }
}
