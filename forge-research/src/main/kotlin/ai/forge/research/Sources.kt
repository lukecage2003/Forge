package ai.forge.research

import java.net.URI

/** Nature d'une source (§6). */
enum class SourceType(val label: String, val weight: Int) {
    PRIMARY("Source primaire", 6),
    INSTITUTIONAL("Source institutionnelle", 5),
    SPECIALIZED("Publication spécialisée", 4),
    JOURNALISTIC("Source journalistique", 3),
    OPINION("Opinion", 2),
    COMMUNITY("Contenu communautaire", 1),
    UNVERIFIED("Information non vérifiée", 0),
}

data class Citation(
    val url: String,
    val title: String,
    val type: SourceType,
    val excerpt: String? = null,
    val accessedAtIso: String,
    val publishedAtIso: String? = null,
)

/**
 * Classification initiale par domaine. C'est une heuristique de départ, que l'utilisateur
 * peut corriger (la correction est mémorisée par Forge Brain) — jamais une vérité.
 */
class SourceClassifier(private val overrides: MutableMap<String, SourceType> = mutableMapOf()) {
    private val institutional = listOf(".gouv.fr", ".gov", ".edu", "europa.eu", "who.int", "un.org", "insee.fr", ".ac.uk", "bnf.fr")
    private val journalistic = listOf("lemonde.fr", "lefigaro.fr", "liberation.fr", "francetvinfo.fr", "afp.com", "reuters.com",
        "apnews.com", "bbc.", "nytimes.com", "theguardian.com", "variety.com", "hollywoodreporter.com", "deadline.com")
    private val publisherPrimary = listOf("marvel.com", "dc.com", "dccomics.com")
    private val specialized = listOf("comicbookresources.com", "cbr.com", "comicsbeat.com", "comicbook.com", "comics.org",
        "nature.com", "science.org", "arxiv.org", "jstor.org", "cairn.info", "persee.fr")
    private val community = listOf("fandom.com", "reddit.com", "wikia", "quora.com", "x.com", "twitter.com", "tiktok.com", "facebook.com")
    private val opinion = listOf("medium.com", "substack.com", "blogspot.", "wordpress.com")

    fun learn(domain: String, type: SourceType) { overrides[domain.lowercase()] = type }

    fun classify(url: String): SourceType {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()?.removePrefix("www.") ?: return SourceType.UNVERIFIED
        overrides[host]?.let { return it }
        fun any(list: List<String>) = list.any { host.endsWith(it) || host.contains(it) }
        return when {
            any(institutional) -> SourceType.INSTITUTIONAL
            any(publisherPrimary) -> SourceType.PRIMARY
            host.endsWith("wikipedia.org") -> SourceType.COMMUNITY
            any(community) -> SourceType.COMMUNITY
            any(journalistic) -> SourceType.JOURNALISTIC
            any(specialized) -> SourceType.SPECIALIZED
            any(opinion) -> SourceType.OPINION
            else -> SourceType.UNVERIFIED
        }
    }
}

/** Synthèse de fiabilité d'un ensemble de sources sur une même affirmation. */
object Corroboration {
    data class Assessment(val independentSources: Int, val bestType: SourceType?, val sufficient: Boolean, val note: String)

    fun assess(citations: List<Citation>, requiresPrimary: Boolean, recentTopic: Boolean): Assessment {
        val byDomain = citations.distinctBy { runCatching { URI(it.url).host?.removePrefix("www.") }.getOrNull() ?: it.url }
        val best = byDomain.maxByOrNull { it.type.weight }?.type
        val strong = byDomain.count { it.type.weight >= SourceType.JOURNALISTIC.weight }
        val sufficient = when {
            requiresPrimary -> best == SourceType.PRIMARY || best == SourceType.INSTITUTIONAL
            else -> strong >= 2 || best == SourceType.PRIMARY || best == SourceType.INSTITUTIONAL
        }
        val note = buildString {
            append(if (sufficient) "Corroboration suffisante" else "Corroboration insuffisante")
            append(" (${byDomain.size} domaine(s) distinct(s), meilleure source : ${best?.label ?: "aucune"}).")
            if (requiresPrimary && !sufficient) append(" Une source primaire est nécessaire et n'a pas été trouvée.")
            if (recentTopic) append(" Sujet récent : revérifier avant toute utilisation.")
        }
        return Assessment(byDomain.size, best, sufficient, note)
    }
}
