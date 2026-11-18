package ai.forge.editorial

import ai.forge.comics.Claim
import ai.forge.core.text.TextUtils
import kotlinx.serialization.Serializable

/** Plan visuel du storyboard. `relevant` = rattaché à ce qui est dit, jamais une image décorative imposée. */
@Serializable
data class StoryboardFrame(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val narration: String,
    val visual: String?,
    /** Si aucun visuel pertinent n'existe, le plan reste sur l'image précédente ou le narrateur. */
    val note: String? = null,
    val animation: String? = null,
)

@Serializable
data class Sequence(val index: Int, val title: String, val startMs: Long, val endMs: Long, val frames: List<StoryboardFrame>, val transitionOut: String?)

@Serializable
data class MediaNeed(val description: String, val kind: String, val source: String?, val authorizationRequired: Boolean)

@Serializable
data class ProductionPlan(
    val title: String,
    val format: FormatSpec,
    val totalMs: Long,
    val narrationStructure: List<String>,
    val sequences: List<Sequence>,
    val illustrations: List<String>,
    val media: List<MediaNeed>,
    val soundSuggestions: List<String>,
    val editingInstructions: List<String>,
    val subtitlesSrt: String,
    /** Plans où la cadence visée n'a pas été atteinte faute de visuel pertinent. */
    val cadenceGaps: List<String>,
)

/**
 * Production audiovisuelle (§9.6). Le minutage est estimé à partir du débit oral du format ;
 * il sera recalé sur le fichier audio réel quand il existera.
 * Pour les formats verticaux : viser une illustration toutes les 3 à 5 s QUAND le contenu le justifie.
 * Les visuels viennent des suggestions du segment et des références de pages vérifiées ; s'il en manque,
 * Forge le signale au lieu d'inventer une image sans rapport.
 */
object ProductionPlanner {
    private const val VERTICAL_MIN_MS = 3_000L
    private const val VERTICAL_MAX_MS = 5_000L

    fun plan(script: ScriptDocument, ledger: Map<String, Claim> = emptyMap()): ProductionPlan {
        val msPerWord = 60_000.0 / script.format.wordsPerMinute
        val vertical = script.format.kind == FormatKind.SHORT_VERTICAL
        var t = 0L
        var frameNo = 0
        val gaps = mutableListOf<String>()
        val media = linkedMapOf<String, MediaNeed>()

        val sequences = script.segments.mapIndexed { si, seg ->
            val segStart = t
            // Visuels pertinents disponibles : suggestions + pages de comics des affirmations citées.
            val pageVisuals = seg.claimIds.mapNotNull { ledger[it]?.primaryReference }.filter { it.page != null }
                .map { "Page : ${it.display()}" }
            val visuals = ArrayDeque(seg.visuals + pageVisuals)
            seg.visuals.forEach { media.putIfAbsent(it, MediaNeed(it, "illustration", null, authorizationRequired = true)) }
            pageVisuals.forEach { media.putIfAbsent(it, MediaNeed(it, "page de comic", "bibliothèque personnelle", authorizationRequired = true)) }

            // Découpe en unités de sens (phrases), regroupées pour viser la cadence verticale.
            val units = TextUtils.sentences(seg.narration).map { it to (TextUtils.wordCount(it) * msPerWord).toLong() }
            val frames = mutableListOf<StoryboardFrame>()
            var bufText = StringBuilder(); var bufMs = 0L
            fun flush() {
                if (bufText.isEmpty()) return
                val visual = visuals.removeFirstOrNull()
                val end = t + bufMs
                frames += StoryboardFrame(++frameNo, t, end, bufText.toString().trim(), visual,
                    note = if (visual == null) "Aucun visuel pertinent : rester sur le plan précédent ou le narrateur" else null,
                    animation = if (visual != null && vertical) "léger zoom avant (Ken Burns), recadrage 9:16" else if (visual != null) "pan lent" else null)
                if (vertical && visual == null && bufMs >= VERTICAL_MIN_MS)
                    gaps += "Plan ${frameNo} (« ${seg.heading} », ${t / 1000}s) : cadence 3–5 s non tenue faute de visuel pertinent."
                t = end; bufText = StringBuilder(); bufMs = 0L
            }
            for ((sentence, ms) in units) {
                if (vertical && bufMs > 0 && bufMs + ms > VERTICAL_MAX_MS) flush()
                bufText.append(sentence).append(' '); bufMs += ms
                if (!vertical) flush()
                else if (bufMs >= VERTICAL_MIN_MS) flush()
            }
            flush()
            Sequence(si + 1, seg.heading, segStart, t, frames, seg.transition)
        }

        val structure = script.segments.mapIndexed { i, s -> "${i + 1}. ${s.heading}" }
        val sounds = buildList {
            add("Ouverture : impact sonore court sur le hook, puis entrée de la musique sous la voix (−18 à −22 dB sous la narration).")
            sequences.filter { it.transitionOut != null }.forEach { add("Transition après « ${it.title} » : effet de passage discret (whoosh) ou respiration musicale.") }
            add("Conclusion : retombée musicale, silence d'une demi-seconde avant la dernière phrase.")
            add("Musique et effets : uniquement sous licence ou libres de droits.")
        }
        val editing = buildList {
            add("Format : ${script.format.label} — ${if (vertical) "1080×1920, 9:16" else "1920×1080, 16:9"}.")
            add("Durée cible : ${script.format.targetSeconds} s ; durée estimée du script : ${script.estimatedSeconds} s.")
            add("Caler chaque plan sur la voix enregistrée ; les minutages ci-dessous sont des estimations.")
            if (vertical) add("Sous-titres incrustés, lisibles sans le son (zone sûre : marges de 10 %).")
            add("Chaque page de comic affichée doit correspondre exactement à la référence citée (édition, page).")
        }
        val illustrations = sequences.flatMap { s -> s.frames.mapNotNull { it.visual } }

        return ProductionPlan(script.title, script.format, t, structure, sequences, illustrations,
            media.values.toList(), sounds, editing, Subtitles.srt(sequences.flatMap { it.frames }), gaps)
    }
}

/** Sous-titres SRT : 2 lignes max, 42 caractères par ligne, minutage réparti selon les mots. */
object Subtitles {
    fun srt(frames: List<StoryboardFrame>, maxLineChars: Int = 42): String {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        for (f in frames) {
            val words = f.narration.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            // Remplissage glouton : lignes de ≤ maxLineChars, regroupées par deux par sous-titre.
            val lines = mutableListOf<MutableList<String>>(mutableListOf())
            for (w in words) {
                val cur = lines.last()
                if (cur.isNotEmpty() && (cur.joinToString(" ") + " " + w).length > maxLineChars) lines += mutableListOf(w) else cur += w
            }
            val chunks = lines.chunked(2)
            val perWord = (f.endMs - f.startMs).toDouble() / words.size
            var start = f.startMs
            for (c in chunks) {
                val n = c.sumOf { it.size }
                val end = if (c === chunks.last()) f.endMs else start + (n * perWord).toLong()
                cues += Triple(start, end, c.joinToString("\n") { it.joinToString(" ") })
                start = end
            }
        }
        return cues.mapIndexed { i, (s, e, text) -> "${i + 1}\n${ts(s)} --> ${ts(e)}\n$text\n" }.joinToString("\n")
    }

    fun ts(ms: Long): String = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
