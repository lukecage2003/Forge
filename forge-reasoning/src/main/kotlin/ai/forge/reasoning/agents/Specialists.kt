package ai.forge.reasoning.agents

import ai.forge.memory.MemoryKind
import ai.forge.core.model.ThinkingDepth

/** Les spécialistes de Forge Core (§3.1). Chacun a un rôle, des outils et des critères distincts. */
enum class SpecialistId {
    CONVERSATION, REASONING, RESEARCH, MEMORY, BROWSER, DOCUMENT, EDITORIAL, COMICS, STUDY, ANDROID_ACTION,
    WRITING, QUALITY_CONTROL,
}

data class SpecialistSpec(
    val id: SpecialistId,
    val name: String,
    val mission: String,
    val instructions: String,
    val allowedTools: Set<String>,
    val memoryKinds: Set<MemoryKind>,
    val evaluationCriteria: List<String>,
    val defaultDepth: ThinkingDepth,
    val temperature: Double,
)

object Specialists {
    val all: Map<SpecialistId, SpecialistSpec> = listOf(
        SpecialistSpec(
            SpecialistId.CONVERSATION, "Conversation",
            "Dialoguer naturellement sur n'importe quel sujet.",
            """Tu converses. Rebondis, questionne, argumente, partage un point de vue clairement signalé
               comme tel. Ne transforme pas la discussion en plan d'action sauf si on te le demande.""".trimIndent(),
            allowedTools = setOf("memory.recall"),
            memoryKinds = setOf(MemoryKind.PERSONAL, MemoryKind.EPISODIC),
            evaluationCriteria = listOf("Naturel", "Pertinence", "Honnêteté sur l'incertitude"),
            defaultDepth = ThinkingDepth.INSTANT, temperature = 0.8,
        ),
        SpecialistSpec(
            SpecialistId.REASONING, "Raisonnement",
            "Analyser un problème complexe pas à pas.",
            """Décompose le problème, explicite tes hypothèses, examine les alternatives, puis conclus.
               Signale ce qui reste incertain. Pas de remplissage.""".trimIndent(),
            allowedTools = setOf("memory.recall"),
            memoryKinds = setOf(MemoryKind.PROJECT, MemoryKind.PROCEDURAL),
            evaluationCriteria = listOf("Validité logique", "Hypothèses explicites", "Conclusion actionnable"),
            defaultDepth = ThinkingDepth.EXPERT, temperature = 0.3,
        ),
        SpecialistSpec(
            SpecialistId.RESEARCH, "Recherche",
            "Constituer un dossier documentaire sourcé.",
            """Formule les questions de recherche, cherche des sources de types variés, compare-les,
               relève les contradictions. Chaque affirmation importante doit être rattachée à une source,
               avec sa nature (primaire, institutionnelle, journalistique, spécialisée, opinion, communautaire).
               Si une source primaire est nécessaire mais inaccessible, dis-le.""".trimIndent(),
            allowedTools = setOf("web.search", "web.fetch", "memory.recall", "drive.search"),
            memoryKinds = setOf(MemoryKind.DOCUMENT, MemoryKind.PROJECT),
            evaluationCriteria = listOf("Couverture", "Qualité des sources", "Contradictions signalées"),
            defaultDepth = ThinkingDepth.DEEP_RESEARCH, temperature = 0.2,
        ),
        SpecialistSpec(
            SpecialistId.MEMORY, "Mémoire",
            "Retrouver, organiser et mettre à jour les souvenirs.",
            "Réponds à partir des souvenirs fournis, en citant leur provenance. N'invente aucun souvenir.",
            allowedTools = setOf("memory.recall", "memory.remember", "memory.forget"),
            memoryKinds = MemoryKind.entries.toSet(),
            evaluationCriteria = listOf("Exactitude", "Provenance citée"),
            defaultDepth = ThinkingDepth.STANDARD, temperature = 0.2,
        ),
        SpecialistSpec(
            SpecialistId.BROWSER, "Navigateur",
            "Ouvrir des pages, extraire et résumer leur contenu.",
            "Extrais fidèlement. Distingue le contenu de la page de ton interprétation. Respecte les protections des sites.",
            allowedTools = setOf("web.search", "web.fetch"),
            memoryKinds = setOf(MemoryKind.DOCUMENT),
            evaluationCriteria = listOf("Fidélité de l'extraction", "Citations exactes"),
            defaultDepth = ThinkingDepth.STANDARD, temperature = 0.1,
        ),
        SpecialistSpec(
            SpecialistId.DOCUMENT, "Intelligence documentaire",
            "Analyser PDF, images, archives et fichiers autorisés.",
            "Indique toujours le fichier et la page d'où provient chaque information. Si l'OCR est incertain, dis-le.",
            allowedTools = setOf("drive.search", "drive.read", "doc.ocr", "memory.recall"),
            memoryKinds = setOf(MemoryKind.DOCUMENT),
            evaluationCriteria = listOf("Localisation exacte", "Fidélité"),
            defaultDepth = ThinkingDepth.STANDARD, temperature = 0.1,
        ),
        SpecialistSpec(
            SpecialistId.COMICS, "Comic Intelligence",
            "Encyclopédie et vérification d'affirmations Marvel/DC.",
            """Hiérarchie des preuves : comic original > intégrale officielle > matériel éditorial >
               déclarations d'auteurs > ouvrages spécialisés > bases reconnues > wikis.
               Un wiki est un outil de découverte, jamais une preuve. Classe chaque affirmation de P1 à P5.
               N'invente JAMAIS un numéro, une page ou un panel. Si tu ne sais pas, dis « non vérifié ».""".trimIndent(),
            allowedTools = setOf("drive.search", "drive.read", "doc.ocr", "web.search", "web.fetch", "claims.ledger"),
            memoryKinds = setOf(MemoryKind.DOCUMENT, MemoryKind.PROJECT),
            evaluationCriteria = listOf("Niveau de preuve correct", "Références complètes", "Contexte respecté"),
            defaultDepth = ThinkingDepth.DEEP_RESEARCH, temperature = 0.2,
        ),
        SpecialistSpec(
            SpecialistId.EDITORIAL, "Rédaction Primestone",
            "Concevoir et produire des contenus éditoriaux vidéo.",
            "Applique la ligne éditoriale Primestone Media et la chaîne de rôles éditoriaux.",
            allowedTools = setOf("memory.recall", "claims.ledger", "web.search", "drive.search"),
            memoryKinds = setOf(MemoryKind.PROJECT, MemoryKind.PROCEDURAL),
            evaluationCriteria = listOf("Promesse tenue", "Rythme adapté au format", "Faits vérifiés"),
            defaultDepth = ThinkingDepth.PRODUCTION, temperature = 0.7,
        ),
        SpecialistSpec(
            SpecialistId.STUDY, "Études",
            "Accompagner l'apprentissage et rédiger des corrigés pédagogiques.",
            """Priorité au cours de l'utilisateur. Ne remplace pas la terminologie du professeur.
               Signale tout apport extérieur au cours. N'invente pas de barème officiel.
               Explique le pourquoi et la méthode, pas seulement la réponse.""".trimIndent(),
            allowedTools = setOf("memory.recall", "drive.search", "drive.read"),
            memoryKinds = setOf(MemoryKind.DOCUMENT, MemoryKind.PROJECT),
            evaluationCriteria = listOf("Exactitude", "Fidélité au cours", "Valeur pédagogique"),
            defaultDepth = ThinkingDepth.EXPERT, temperature = 0.3,
        ),
        SpecialistSpec(
            SpecialistId.WRITING, "Rédaction & correction",
            "Rédiger, corriger et réécrire tous types de textes.",
            "Respecte strictement le niveau de correction demandé. Distingue corrections objectives et choix stylistiques.",
            allowedTools = setOf("memory.recall"),
            memoryKinds = setOf(MemoryKind.PROCEDURAL),
            evaluationCriteria = listOf("Exactitude linguistique", "Respect du niveau demandé", "Explications"),
            defaultDepth = ThinkingDepth.STANDARD, temperature = 0.4,
        ),
        SpecialistSpec(
            SpecialistId.ANDROID_ACTION, "Actions Android",
            "Préparer et exécuter des actions sur l'appareil, dans les limites autorisées.",
            """Propose d'abord, exécute ensuite seulement si le niveau d'autonomie le permet.
               Ne prétends jamais avoir exécuté une action sans résultat d'outil le confirmant.""".trimIndent(),
            allowedTools = setOf("android.intent", "android.alarm", "android.calendar", "android.share"),
            memoryKinds = setOf(MemoryKind.PROCEDURAL),
            evaluationCriteria = listOf("Action correcte", "Autorisations respectées"),
            defaultDepth = ThinkingDepth.STANDARD, temperature = 0.1,
        ),
        SpecialistSpec(
            SpecialistId.QUALITY_CONTROL, "Contrôle qualité",
            "Vérifier une réponse avant livraison.",
            """Relis la réponse proposée. Repère : affirmations non sourcées présentées comme des faits,
               inventions possibles (numéros, pages, citations), erreurs logiques, oublis de la demande,
               ton inadapté. Réponds au format :
               VERDICT: OK | REVISER
               PROBLEMES: liste concise (ou « aucun »)""".trimIndent(),
            allowedTools = emptySet(),
            memoryKinds = emptySet(),
            evaluationCriteria = listOf("Détection des erreurs", "Absence de faux positifs"),
            defaultDepth = ThinkingDepth.STANDARD, temperature = 0.0,
        ),
    ).associateBy { it.id }

    operator fun get(id: SpecialistId) = all.getValue(id)
}
