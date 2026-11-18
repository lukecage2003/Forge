package ai.forge.core.editorial

import kotlinx.serialization.Serializable

/** Plateformes et formats (§9.3). Durées et débits sont configurables, jamais figés. */
@Serializable
enum class FormatKind { YOUTUBE_LONG, DOCUMENTARY, SHORT_VERTICAL }

@Serializable
data class FormatSpec(
    val kind: FormatKind,
    val label: String,
    val targetSeconds: Int,
    /** Débit oral en mots par minute (français posé ≈ 140-160, Shorts ≈ 170-190). */
    val wordsPerMinute: Int,
    val hookMaxSeconds: Int,
    val beats: List<String>,
    val principles: List<String>,
) {
    val targetWords: Int get() = targetSeconds * wordsPerMinute / 60
    val toleranceWords: Int get() = (targetWords * 0.12).toInt().coerceAtLeast(10)

    companion object {
        fun youtubeLong(minutes: Int = 20, wpm: Int = 150) = FormatSpec(
            FormatKind.YOUTUBE_LONG, "YouTube long format", minutes * 60, wpm, hookMaxSeconds = 30,
            beats = listOf("Accroche", "Promesse", "Mise en contexte", "Développement progressif (3 à 5 blocs)",
                "Analyse", "Retournement ou approfondissement", "Conclusion", "Sources"),
            principles = listOf("Introduction forte", "Transitions explicites entre blocs",
                "Illustrations documentaires (pages, cases) suggérées pour chaque affirmation clé",
                "Aucun remplissage"),
        )

        fun documentary(minutes: Int = 90, wpm: Int = 140) = FormatSpec(
            FormatKind.DOCUMENTARY, "Documentaire", minutes * 60, wpm, hookMaxSeconds = 120,
            beats = listOf("Ouverture (cold open)", "Enjeux et personnages", "Chapitres chronologiques",
                "Témoignages et sources", "Point culminant", "Résolution", "Épilogue", "Générique des sources"),
            principles = listOf("Recherche approfondie avant écriture", "Progression émotionnelle",
                "Chapitrage clair", "Traitement audiovisuel décrit pour chaque séquence"),
        )

        fun short(seconds: Int = 60, wpm: Int = 180) = FormatSpec(
            FormatKind.SHORT_VERTICAL, "Short / TikTok / Reels", seconds, wpm, hookMaxSeconds = 2,
            beats = listOf("Hook immédiat (≤ 2 s)", "Une seule idée", "Preuve / exemple", "Chute mémorable"),
            principles = listOf("Une idée autonome, pas un extrait coupé d'un long format",
                "Aucun détour", "Cadrage vertical 9:16", "Texte à l'écran lisible sans le son"),
        )
    }
}
