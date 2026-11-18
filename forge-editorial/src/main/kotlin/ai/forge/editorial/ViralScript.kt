package ai.forge.editorial

import ai.forge.core.text.TextUtils

/** Plateformes cibles avec leurs règles de rétention (configurables). */
enum class Platform(
    val label: String,
    val defaultSeconds: Int,
    val wordsPerMinute: Int,
    val hookMaxSeconds: Int,
    /** Changement visuel maximal (s) : au-delà, l'attention décroche en vertical. */
    val maxShotSeconds: Int,
    /** Intervalle de relance narrative (s) pour les formats longs ; 0 = sans objet. */
    val rehookEverySeconds: Int,
    val vertical: Boolean,
) {
    TIKTOK("TikTok", 45, 185, 2, 4, 0, true),
    YOUTUBE_SHORTS("YouTube Shorts", 50, 180, 2, 4, 0, true),
    REELS("Instagram Reels", 40, 180, 2, 4, 0, true),
    YOUTUBE_LONG("YouTube", 600, 155, 30, 12, 90, false);

    companion object {
        fun detect(text: String): Platform {
            val t = TextUtils.fold(text)
            return when {
                "tiktok" in t -> TIKTOK
                "short" in t -> YOUTUBE_SHORTS
                "reel" in t || "insta" in t -> REELS
                else -> if (Regex("\\b(\\d{1,2})\\s*(min|minutes)\\b").containsMatchIn(t) || "youtube" in t || "video longue" in t) YOUTUBE_LONG else TIKTOK
            }
        }
    }
}

/** Archétypes d'accroches éprouvés (on varie les mécanismes, pas les formules creuses). */
enum class HookType(val label: String, val pattern: String) {
    CONTRADICTION("Contre-intuitif", "« Tout le monde croit X. C'est faux, et voici la preuve. »"),
    QUESTION("Question choc", "Une question précise à laquelle le spectateur veut la réponse."),
    STAKES("Enjeu / chiffre", "Un fait ou un chiffre surprenant, posé dès la première phrase."),
    WHAT_IF("Et si…", "Une hypothèse qui ouvre une boucle de curiosité."),
    SECRET("Coulisses", "Une information méconnue, présentée comme une révélation sourcée."),
    CHALLENGE("Défi / classement", "« Le personnage le plus sous-estimé de Marvel, et ce n'est pas celui que vous croyez. »"),
}

data class ScriptBeat(val timecode: String?, val seconds: Int?, val visual: String?, val onScreenText: String?, val narration: String)

data class ViralScript(
    val platform: Platform,
    val titles: List<String>,
    val thumbnail: String?,
    val hooks: List<String>,
    val beats: List<ScriptBeat>,
    val sources: List<String>,
) {
    val narration: String get() = beats.joinToString(" ") { it.narration }
    val words: Int get() = TextUtils.wordCount(narration)
    val estimatedSeconds: Int get() = words * 60 / platform.wordsPerMinute
}

data class RetentionCheck(val ok: Boolean, val label: String, val fix: String?)

/**
 * Studio de scripts (§9) : prompt structuré, analyse du résultat, contrôle de rétention.
 * Aucune promesse de viralité : ce sont des règles de métier vérifiables, appliquées à chaque script.
 */
object ScriptStudio {
    fun prompt(topic: String, platform: Platform, seconds: Int = platform.defaultSeconds, marketInsights: String? = null, sourcesContext: String? = null): String = buildString {
        val targetWords = seconds * platform.wordsPerMinute / 60
        appendLine("Tu es le rédacteur en chef et script doctor de Primestone Media (comics, Marvel, DC, pop culture).")
        appendLine(EditorialLine.TEXT)
        appendLine()
        appendLine("Écris un script ${platform.label} sur : « $topic ».")
        appendLine("Durée visée : ${seconds}s (~$targetWords mots dits). Format ${if (platform.vertical) "vertical 9:16" else "horizontal 16:9"}.")
        appendLine("Règles de rétention, toutes obligatoires :")
        appendLine("- Accroche dans les ${platform.hookMaxSeconds} premières secondes : la première phrase contient déjà l'enjeu. Jamais « Salut », « Bienvenue », « Dans cette vidéo ».")
        appendLine("- Ouvre une boucle de curiosité tôt et ne la referme qu'à la fin.")
        if (platform.vertical) {
            appendLine("- Un changement visuel toutes les ${platform.maxShotSeconds}s au plus (nouveau plan, zoom, texte à l'écran).")
            appendLine("- Une seule idée forte. Phrases très courtes. Fin qui relance la boucle (la dernière phrase renvoie à la première).")
            appendLine("- Texte à l'écran lisible sans le son, 6 mots maximum.")
        } else {
            appendLine("- Une relance toutes les ~${platform.rehookEverySeconds}s (« mais ce n'est pas le plus fou », question ouverte, promesse de la suite).")
            appendLine("- Structure : accroche → promesse → contexte bref → 3 à 5 blocs qui montent en intensité → révélation → conclusion → appel à l'action court.")
        }
        appendLine("- Ton oral, naturel, phrases courtes ; pas de tournures d'article académique.")
        appendLine("- Faits : uniquement ceux des sources fournies ; sinon écris [À VÉRIFIER]. N'invente aucun numéro ni aucune page.")
        appendLine("- Contenu original : aucune image, musique ou extrait protégé imposé ; propose des visuels réalisables (texte animé, formes, captures de pages que l'utilisateur possède).")
        marketInsights?.let { appendLine(); appendLine("Ce qui marche actuellement sur ce sujet (données réelles) :"); appendLine(it) }
        sourcesContext?.let { appendLine(); appendLine(it) }
        appendLine()
        appendLine("""Réponds EXACTEMENT dans ce format :
            |### TITRES
            |1. … (3 titres, < 60 caractères, curiosité sans mensonge)
            |### MINIATURE
            |Idée de miniature en une phrase (3 mots de texte maximum).
            |### ACCROCHES
            |1. … (3 accroches alternatives, mécanismes différents : contre-intuitif, question, enjeu…)
            |### SCRIPT
            |[0:00] PLAN: … | ÉCRAN: … | TEXTE: narration dite
            |[0:03] PLAN: … | ÉCRAN: … | TEXTE: …
            |### SOURCES
            |- …""".trimMargin())
    }

    private fun section(raw: String, name: String): String {
        val rx = Regex("(?im)^#{2,3}\\s*${name}\\s*$")
        val m = rx.find(raw) ?: return ""
        val rest = raw.substring(m.range.last + 1)
        val next = Regex("(?m)^#{2,3}\\s*\\S").find(rest)
        return (if (next != null) rest.substring(0, next.range.first) else rest).trim()
    }

    private fun items(block: String) = block.lines().map { it.trim().removePrefix("-").trim().replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotBlank() }

    private val beatLine = Regex("^\\[(\\d{1,2}):(\\d{2})]\\s*(.*)$")

    fun parse(raw: String, platform: Platform): ViralScript {
        val beats = section(raw, "SCRIPT").lines().mapNotNull { line ->
            val m = beatLine.find(line.trim()) ?: return@mapNotNull null
            val secs = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            val parts = m.groupValues[3].split("|").map { it.trim() }
            fun field(key: String) = parts.firstOrNull { TextUtils.fold(it).startsWith(TextUtils.fold(key)) }?.substringAfter(":")?.trim()?.ifBlank { null }
            val text = field("TEXTE") ?: parts.lastOrNull { ':' !in it } ?: return@mapNotNull null
            ScriptBeat("${m.groupValues[1]}:${m.groupValues[2]}", secs, field("PLAN"), field("ÉCRAN") ?: field("ECRAN"), text)
        }
        return ViralScript(platform, items(section(raw, "TITRES")), section(raw, "MINIATURE").ifBlank { null },
            items(section(raw, "ACCROCHES")), beats, items(section(raw, "SOURCES")))
    }

    private val softOpen = Regex("^(salut|bonjour|hello|coucou|bienvenue|aujourd'hui (on|je)|dans cette video|avant de commencer|n'oubliez pas de)")
    private val rehookCue = Regex("(mais (ce n'est pas|attendez|le plus)|attendez|vous allez voir|on y revient|et c'est la que|sauf que|le plus fou|retenez bien)")

    fun check(s: ViralScript, targetSeconds: Int = s.platform.defaultSeconds): List<RetentionCheck> = buildList {
        val p = s.platform
        add(RetentionCheck(s.beats.isNotEmpty(), "Script structuré en plans minutés", "Régénérer au format [m:ss] PLAN | ÉCRAN | TEXTE"))
        if (s.beats.isEmpty()) return@buildList
        val first = s.beats.first().narration
        val firstSentence = TextUtils.sentences(first).firstOrNull().orEmpty()
        val hookWords = maxOf(6, p.hookMaxSeconds * p.wordsPerMinute / 60 + 4)
        add(RetentionCheck(!softOpen.containsMatchIn(TextUtils.fold(first.trim())), "Pas d'introduction molle", "Commencer directement par l'enjeu, sans salutation"))
        add(RetentionCheck(TextUtils.wordCount(firstSentence) <= hookWords, "Accroche en ${p.hookMaxSeconds}s (${TextUtils.wordCount(firstSentence)} mots)", "Raccourcir la première phrase à $hookWords mots ou moins"))
        val delta = s.estimatedSeconds - targetSeconds
        add(RetentionCheck(kotlin.math.abs(delta) <= maxOf(5, targetSeconds / 8), "Durée ≈ ${s.estimatedSeconds}s pour ${targetSeconds}s visées",
            if (delta > 0) "Couper ~${delta * p.wordsPerMinute / 60} mots" else "Ajouter ~${-delta * p.wordsPerMinute / 60} mots"))
        val times = s.beats.mapNotNull { it.seconds } + (s.beats.lastOrNull()?.seconds?.let { listOf(maxOf(it, s.estimatedSeconds)) } ?: emptyList())
        val longest = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
        add(RetentionCheck(longest <= p.maxShotSeconds, "Changement visuel toutes les ${p.maxShotSeconds}s au plus (max actuel : ${longest}s)", "Découper les plans les plus longs"))
        add(RetentionCheck(s.beats.all { it.visual != null }, "Chaque plan a une indication visuelle", "Ajouter PLAN: à chaque ligne"))
        if (p.vertical) {
            add(RetentionCheck(s.beats.count { it.onScreenText != null } >= s.beats.size / 2, "Texte à l'écran (lisible sans le son)", "Ajouter ÉCRAN: sur au moins un plan sur deux"))
            val last = TextUtils.stems(s.beats.last().narration).toSet(); val firstStems = TextUtils.stems(first).toSet()
            add(RetentionCheck(last.intersect(firstStems).isNotEmpty() || s.beats.last().narration.trim().endsWith("?"), "Fin qui relance la boucle", "Faire écho à l'accroche dans la dernière phrase"))
        } else {
            val needed = maxOf(1, s.estimatedSeconds / p.rehookEverySeconds - 1)
            val found = rehookCue.findAll(TextUtils.fold(s.narration)).count()
            add(RetentionCheck(found >= needed, "Relances narratives ($found / $needed attendues)", "Ajouter une relance toutes les ~${p.rehookEverySeconds}s"))
        }
        add(RetentionCheck(s.titles.size >= 2 && s.titles.all { it.length <= 70 }, "Titres (≥ 2, ≤ 70 caractères)", "Proposer des titres plus courts"))
        add(RetentionCheck(s.hooks.size >= 2, "Accroches alternatives pour test A/B", "Demander 3 accroches"))
        val unverified = Regex("\\[À VÉRIFIER]").findAll(s.narration).count()
        add(RetentionCheck(unverified == 0, if (unverified == 0) "Faits sourcés" else "$unverified fait(s) à vérifier", "Vérifier ou retirer les passages [À VÉRIFIER]"))
        OralStyle.check(s.narration).take(3).forEach { add(RetentionCheck(false, "Oral : ${it.kind} « ${it.excerpt.take(40)} »", it.suggestion)) }
    }

    fun report(checks: List<RetentionCheck>): String = buildString {
        val ok = checks.count { it.ok }
        appendLine("**Contrôle de rétention : $ok/${checks.size}**")
        checks.forEach { appendLine(if (it.ok) "- ✅ ${it.label}" else "- ⚠️ ${it.label} → ${it.fix}") }
        append("_Ces règles augmentent les chances de rétention ; aucune ne garantit la viralité._")
    }

    /** Conversion vers le modèle éditorial (storyboard, SRT, Motion Studio). */
    fun toDocument(s: ViralScript, topic: String): ScriptDocument {
        val format = if (s.platform.vertical) FormatSpec.short(s.estimatedSeconds.coerceAtLeast(15), s.platform.wordsPerMinute)
            else FormatSpec.youtubeLong(maxOf(1, s.estimatedSeconds / 60), s.platform.wordsPerMinute)
        return ScriptDocument(s.titles.firstOrNull() ?: topic, topic, "Fans de comics", format, s.hooks.firstOrNull() ?: "",
            s.beats.mapIndexed { i, b -> ScriptSegment(b.timecode ?: "Plan ${i + 1}", b.narration, visuals = listOfNotNull(b.visual)) }, s.sources)
    }
}
