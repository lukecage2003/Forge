package ai.forge.evaluation

import ai.forge.comics.ClaimLedger
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import ai.forge.memory.MemoryCandidate
import ai.forge.memory.MemoryKind
import ai.forge.memory.MemoryManager
import ai.forge.memory.MemoryQuery
import ai.forge.memory.Provenance
import ai.forge.memory.RememberOutcome
import ai.forge.models.ChatRequest
import ai.forge.models.LlmEvent
import ai.forge.models.LlmProvider
import kotlinx.coroutines.flow.collect

/**
 * Bancs d'essai reproductibles. Ils mesurent des comportements, pas des impressions :
 * chaque métrique a un seuil et, si elle protège l'utilisateur, un caractère critique.
 */
object MemoryBenchmark {
    data class Fixture(val fact: String, val query: String)

    val defaultFixtures = listOf(
        Fixture("Ma chaîne YouTube s'appelle Primestone Media", "Comment s'appelle ma chaîne ?"),
        Fixture("Mon examen de droit des contrats a lieu le 3 juin", "Quand est mon examen de droit ?"),
        Fixture("Le prochain script porte sur le vibranium", "Sur quoi porte le prochain script ?"),
        Fixture("Je préfère des réponses courtes le matin", "Quelles réponses je préfère le matin ?"),
        Fixture("Le projet Forge doit fonctionner sur un Galaxy S25", "Sur quel téléphone doit fonctionner Forge ?"),
    )

    suspend fun run(newManager: () -> MemoryManager, fixtures: List<Fixture> = defaultFixtures, k: Int = 3): List<Metric> {
        val m = newManager()
        val ids = fixtures.map { f ->
            (m.remember(MemoryCandidate(MemoryKind.PERSONAL, f.fact, Provenance(Provenance.Type.USER_EXPLICIT, "bench"), needsConfirmation = false))
                as RememberOutcome.Stored).item.id
        }
        val hits = fixtures.count { f -> m.recall(MemoryQuery(f.query, limit = k)).any { it.item.text == f.fact } }

        // Respect des suppressions : un souvenir oublié ne doit plus JAMAIS ressortir.
        m.forget(ids[0])
        val leaked = m.recall(MemoryQuery(fixtures[0].query, limit = 10)).any { it.item.id == ids[0] }

        // Contradiction : doit être soumise à l'utilisateur, pas écrasée silencieusement.
        val c = newManager()
        c.remember(MemoryCandidate(MemoryKind.PERSONAL, "Vitesse de voix : lente", Provenance(Provenance.Type.USER_EXPLICIT, "b"), key = "voix", needsConfirmation = false))
        val conflict = c.remember(MemoryCandidate(MemoryKind.PERSONAL, "Vitesse de voix : rapide", Provenance(Provenance.Type.USER_EXPLICIT, "b"), key = "voix", needsConfirmation = false))

        return listOf(
            Metric(BenchDomain.MEMORY, "Rappel@$k", hits.toDouble() / fixtures.size, 0.8),
            Metric(BenchDomain.MEMORY, "Suppression respectée", if (leaked) 0.0 else 1.0, 1.0, critical = true),
            Metric(BenchDomain.MEMORY, "Contradiction signalée", if (conflict is RememberOutcome.Conflict) 1.0 else 0.0, 1.0, critical = true),
        )
    }
}

/** Détection d'invention de références comics dans un texte produit par un modèle. */
object ReferenceGuard {
    private val issueRef = Regex("([A-Z][\\p{L}'’ -]{2,40}?)\\s*#\\s*(\\d{1,4})")
    private val pageRef = Regex("\\b(?:p\\.|page)\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)

    /** Références (titre #numéro) et pages mentionnées qui n'existent pas dans le registre. */
    fun inventedReferences(text: String, ledger: ClaimLedger): List<String> {
        val known = ledger.all().flatMap { c -> c.sources.mapNotNull { it.reference } }
        val knownIssues = known.mapNotNull { r -> r.issue?.let { r.title.lowercase().trim() to it } }
        val knownPages = known.mapNotNull { it.page }.toSet()
        val invented = mutableListOf<String>()
        issueRef.findAll(text).forEach { m ->
            // Le titre capturé peut inclure les mots qui le précèdent : on compare par suffixe.
            val captured = m.groupValues[1].lowercase().trim()
            val ok = knownIssues.any { (title, issue) -> issue == m.groupValues[2] && captured.endsWith(title) }
            if (!ok) invented += m.value.trim()
        }
        pageRef.findAll(text).forEach { m -> if (m.groupValues[1].toInt() !in knownPages) invented += m.value }
        return invented
    }

    fun metric(outputs: List<String>, ledger: ClaimLedger): Metric {
        val bad = outputs.count { inventedReferences(it, ledger).isNotEmpty() }
        return Metric(BenchDomain.COMICS, "Sorties sans référence inventée", 1.0 - bad.toDouble() / outputs.size.coerceAtLeast(1), 1.0, critical = true)
    }
}

/** Mesure de latence d'un moteur : à exécuter SUR LE TÉLÉPHONE pour obtenir des chiffres réels. */
object LatencyProbe {
    data class Result(val firstTokenMs: Long?, val totalMs: Long, val chars: Int, val failed: String?) {
        val charsPerSecond: Double get() = if (totalMs == 0L) 0.0 else chars * 1000.0 / totalMs
    }

    suspend fun measure(provider: LlmProvider, prompt: String = "Présente-toi en une phrase.", clock: () -> Long = System::nanoTime): Result {
        val start = clock()
        var first: Long? = null; var chars = 0; var failed: String? = null
        provider.stream(ChatRequest(listOf(ChatMessage(Role.USER, prompt)), maxTokens = 128)).collect { e ->
            when (e) {
                is LlmEvent.Token -> { if (first == null) first = (clock() - start) / 1_000_000; chars += e.text.length }
                is LlmEvent.Failure -> failed = e.message
                is LlmEvent.Done -> Unit
            }
        }
        return Result(first, (clock() - start) / 1_000_000, chars, failed)
    }

    /** Pour la voix, le premier jeton doit arriver vite : seuil indicatif de 1,2 s, à affiner sur appareil. */
    fun metrics(r: Result, firstTokenBudgetMs: Long = 1_200): List<Metric> = listOf(
        Metric(BenchDomain.PERFORMANCE, "Premier jeton", (r.firstTokenMs ?: Long.MAX_VALUE).toDouble(), firstTokenBudgetMs.toDouble(), higherIsBetter = false, unit = " ms"),
        Metric(BenchDomain.PERFORMANCE, "Génération sans échec", if (r.failed == null) 1.0 else 0.0, 1.0, critical = true),
    )
}
