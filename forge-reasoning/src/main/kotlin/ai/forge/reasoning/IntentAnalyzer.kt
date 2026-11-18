package ai.forge.reasoning

import ai.forge.reasoning.agents.SpecialistId
import ai.forge.core.model.ThinkingDepth
import ai.forge.core.text.TextUtils

data class Intent(
    val primary: SpecialistId,
    val supporting: List<SpecialistId>,
    val depth: ThinkingDepth,
    val needsVerification: Boolean,
    val signals: List<String>,
)

/**
 * Analyse d'intention par règles explicables. Rapide, hors-ligne, déterministe.
 * Choix assumé : pas d'appel LLM pour router chaque phrase (latence vocale) ; un classifieur
 * appris pourra remplacer ces règles une fois un jeu d'évaluation constitué (Forge Brain).
 */
class IntentAnalyzer {
    private data class Rule(val id: SpecialistId, val patterns: List<Regex>)

    private fun r(vararg p: String) = p.map { Regex(it) }

    private val rules = listOf(
        Rule(SpecialistId.COMICS, r("\\bcomics?\\b", "\\bmarvel\\b", "\\bdc\\b", "\\bpanel", "\\bvibranium\\b",
            "\\b(black panther|superman|batman|spider[- ]?man|thor|hulk|x[- ]men|avengers|wolverine|flash)\\b",
            "\\bnumero\\s*#?\\d+", "#\\d+")),
        Rule(SpecialistId.EDITORIAL, r("\\bscript", "\\bshorts?\\b", "\\btiktok\\b", "\\breels?\\b", "\\byoutube\\b",
            "\\bdocumentaire\\b", "\\bprimestone\\b", "\\bvideo\\b", "\\bmontage\\b", "\\bhook\\b")),
        Rule(SpecialistId.WRITING, r("\\bcorrig(e|er|es)\\b(?!.*\\bexercice)", "\\breecri", "\\bredige", "\\bemail\\b",
            "\\bmail\\b", "\\bcourrier\\b", "\\borthograph", "\\bgrammaire\\b", "\\breformule")),
        Rule(SpecialistId.STUDY, r("\\bexercice", "\\bcorrige\\b.*\\b(exercice|examen|qcm|td|tp)",
            "\\bqcm\\b", "\\bdissertation\\b", "\\bcommentaire de texte\\b", "\\bcours\\b", "\\breviser\\b",
            "\\bexamen\\b", "\\bpartiel\\b", "\\bbareme\\b")),
        Rule(SpecialistId.RESEARCH, r("\\brecherche", "\\bsources?\\b", "\\bverifie", "\\bactualite", "\\benquete\\b",
            "\\bcompare\\b.*\\bsources?\\b", "\\bderniere", "\\brecent")),
        Rule(SpecialistId.DOCUMENT, r("\\bpdf\\b", "\\bdocument\\b", "\\bfichier\\b", "\\bdrive\\b", "\\bcbz\\b", "\\bimage\\b")),
        Rule(SpecialistId.MEMORY, r("\\btu te souviens\\b", "\\bje t'avais dit\\b", "\\bla derniere fois\\b",
            "\\bqu'est-ce que tu sais sur moi\\b", "\\bmes souvenirs\\b")),
        Rule(SpecialistId.ANDROID_ACTION, r("\\b(mets|programme|regle)\\b.*\\b(alarme|reveil|minuteur|rappel)\\b",
            "\\bouvre l'application\\b", "\\breveille[- ]moi\\b", "\\b(cree|prepare)\\b.*\\b(rappel|evenement)\\b", "\\bajoute\\b.*\\bagenda\\b", "\\benvoie\\b.*\\b(sms|message)\\b", "\\bappelle\\b", "^(partage|envoie)\\b")),
        Rule(SpecialistId.REASONING, r("\\banalyse\\b", "\\bpourquoi\\b.*\\b(exactement|vraiment)\\b",
            "\\bdemontre\\b", "\\bprobleme\\b", "\\bstrategie\\b", "\\bcalcule\\b", "\\braisonne")),
    )

    private val imperativeAction = Regex("^(forge[, ]+)?(mets|programme|regle|reveille|partage|envoie|ouvre|ajoute|cree|prepare|appelle|lance)\\b")
    private val deepCues = Regex("\\b(en profondeur|approfondi|deep research|dossier complet|exhaustif|en detail)\\b")
    private val quickCues = Regex("\\b(rapidement|en bref|vite fait|en une phrase)\\b")

    fun analyze(utterance: String): Intent {
        val t = TextUtils.fold(utterance)
        val scored = rules.map { rule -> rule.id to rule.patterns.count { it.containsMatchIn(t) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val signals = scored.map { "${it.first}:${it.second}" }

        if (scored.isEmpty()) {
            return Intent(SpecialistId.CONVERSATION, emptyList(),
                ThinkingDepth.INSTANT,
                needsVerification = false, signals = listOf("conversation libre"))
        }
        // Une phrase qui commence par un verbe d'action à l'impératif est une demande d'action,
        // même si elle mentionne un sujet éditorial (« partage mon script… »).
        val actionFirst = imperativeAction.containsMatchIn(t) && scored.any { it.first == SpecialistId.ANDROID_ACTION }
        val primary = if (actionFirst) SpecialistId.ANDROID_ACTION else scored.first().first
        val supporting = scored.drop(1).map { it.first }.filter { it != primary }.take(2)
        var depth = when (primary) {
            SpecialistId.COMICS, SpecialistId.RESEARCH -> ThinkingDepth.DEEP_RESEARCH
            SpecialistId.EDITORIAL -> ThinkingDepth.PRODUCTION
            SpecialistId.REASONING, SpecialistId.STUDY -> ThinkingDepth.EXPERT
            else -> ThinkingDepth.STANDARD
        }
        if (deepCues.containsMatchIn(t) && depth < ThinkingDepth.DEEP_RESEARCH) depth = ThinkingDepth.DEEP_RESEARCH
        if (quickCues.containsMatchIn(t)) depth = ThinkingDepth.STANDARD
        return Intent(primary, supporting, depth, needsVerification = depth.verify, signals = signals)
    }
}
