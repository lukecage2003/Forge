package ai.forge.core.writing

import ai.forge.core.text.TextUtils

/** Niveaux de correction (module Writing & Correction, §2). */
enum class CorrectionLevel(val label: String, val instruction: String) {
    MINIMAL("Correction minimale",
        "Corrige uniquement les fautes indiscutables. Conserve chaque mot, la structure et le style de l'auteur."),
    LINGUISTIC("Correction linguistique",
        "Corrige orthographe, grammaire, syntaxe, ponctuation et typographie française. Ne touche pas au style."),
    STYLISTIC("Réécriture stylistique",
        "Corrige puis améliore fluidité, vocabulaire et rythme ; supprime les répétitions. Conserve le plan et les idées."),
    DEEP("Réécriture approfondie",
        "Restructure le texte (plan, enchaînements, argumentation) en conservant le propos et les informations."),
    EDITORIAL("Réécriture éditoriale",
        "Produis une version professionnelle prête à publier, adaptée au support visé. Signale toute information à vérifier."),
}

/** Types de corrigés pédagogiques (§5). */
enum class AnswerKeyMode(val label: String, val instruction: String) {
    FAITHFUL("Corrigé fidèle au cours",
        "Utilise exclusivement les notions, la terminologie et les méthodes du cours fourni. Si le cours ne suffit pas, dis-le."),
    ENRICHED("Corrigé enrichi",
        "Base-toi sur le cours, puis enrichis avec des explications externes en les signalant par [Hors cours]."),
    SHORT("Version courte (révision)", "Réponses essentielles et méthode en quelques lignes par question."),
    DEEP("Version approfondie (compréhension)", "Explique en détail le raisonnement, les pièges et les notions mobilisées."),
}

data class CorrectionRequest(
    val text: String,
    val level: CorrectionLevel,
    val genre: String? = null,
    val audience: String? = null,
)

object WritingPrompts {
    fun correction(req: CorrectionRequest): String = buildString {
        appendLine("Tâche : ${req.level.label}.")
        appendLine(req.level.instruction)
        req.genre?.let { appendLine("Type de texte : $it") }
        req.audience?.let { appendLine("Destinataire / public : $it") }
        appendLine("""
            Rends :
            1. Le texte corrigé complet.
            2. La liste des modifications importantes, chacune classée « objective » (erreur) ou « stylistique » (préférence), avec une brève justification.
            3. Les faits ou chiffres qui mériteraient une vérification (ou « aucun »).
        """.trimIndent())
        val analysis = WritingAnalyzer.report(req.text)
        if (analysis.isNotEmpty()) {
            appendLine("Signalements automatiques (à confirmer, ils peuvent être des faux positifs) :")
            analysis.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Texte :")
        append(req.text)
    }

    fun answerKey(exercise: String, mode: AnswerKeyMode, courseExcerpt: String?, withGrading: Boolean): String = buildString {
        appendLine("Tâche : rédiger un corrigé — ${mode.label}.")
        appendLine(mode.instruction)
        appendLine("""
            Pour chaque question : 1) la question ; 2) la réponse attendue ; 3) le raisonnement détaillé ;
            4) les étapes de résolution ; 5) les notions du cours mobilisées ; 6) les erreurs fréquentes ;
            7) les explications pédagogiques ; 8) les références au cours si disponibles.
            Distingue ce qui est certain, ce qui relève de l'interprétation et ce qui doit être vérifié.
        """.trimIndent())
        if (withGrading) appendLine("Propose un barème INDICATIF, explicitement présenté comme non officiel.")
        if (courseExcerpt.isNullOrBlank()) {
            appendLine("Aucun cours n'est fourni : signale que le corrigé repose sur des connaissances générales et peut diverger des attentes du professeur.")
        } else {
            appendLine("Cours de référence (prioritaire) :")
            appendLine(courseExcerpt)
        }
        appendLine()
        appendLine("Énoncé :")
        append(exercise)
    }

    fun reviewStudentWork(work: String, instructions: String?): String = buildString {
        appendLine("""
            Évalue le travail ci-dessous : qualité du contenu, points forts, erreurs, passages imprécis,
            cohérence des arguments, pistes d'amélioration, puis une version corrigée complète et
            l'explication des changements importants. Distingue corrections objectives et préférences de style.
        """.trimIndent())
        instructions?.let { appendLine("Consignes de l'exercice : $it") }
        appendLine()
        append(work)
    }
}

/** Analyses déterministes rapides, exécutées avant tout appel au modèle. */
object WritingAnalyzer {
    /** Mots porteurs de sens répétés au moins [minCount] fois. */
    fun repetitions(text: String, minCount: Int = 4): List<Pair<String, Int>> =
        TextUtils.contentTokens(text).filter { it.length > 3 }
            .groupingBy { TextUtils.stem(it) }.eachCount()
            .filter { it.value >= minCount }.toList().sortedByDescending { it.second }

    fun longSentences(text: String, maxWords: Int = 35): List<String> =
        TextUtils.sentences(text).filter { TextUtils.wordCount(it) > maxWords }

    /** Typographie française : espace (insécable) avant ; : ! ? et à l'intérieur des guillemets. */
    fun typographyIssues(text: String): List<String> = buildList {
        Regex("\\S[;:!?](?=\\s|$)").findAll(text).filterNot { m ->
            // Exclut les URL et heures (« 10:30 »).
            val ctx = text.substring(maxOf(0, m.range.first - 6), minOf(text.length, m.range.last + 3))
            ctx.contains("://") || Regex("\\d:\\d").containsMatchIn(ctx)
        }.forEach { add("Espace manquante avant « ${it.value.last()} » : « ${it.value} »") }
        if (Regex("\"[^\"]+\"").containsMatchIn(text)) add("Guillemets droits : préférer « … » en français.")
        if (text.contains("  ")) add("Espaces doubles.")
    }

    fun report(text: String): List<String> = buildList {
        repetitions(text).take(5).forEach { add("Répétition : « ${it.first}… » ×${it.second}") }
        longSentences(text).take(3).forEach { add("Phrase longue (${TextUtils.wordCount(it)} mots) : « ${it.take(60)}… »") }
        addAll(typographyIssues(text).take(5))
    }
}
