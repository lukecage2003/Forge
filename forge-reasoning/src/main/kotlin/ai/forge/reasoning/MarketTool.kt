package ai.forge.reasoning

import ai.forge.editorial.Platform

/** Analyse du marché vidéo (implémentée par forge-market avec l'API YouTube Data). */
fun interface MarketTool {
    /** Résumé de ce qui fonctionne sur ce sujet, ou null si indisponible. */
    suspend fun insights(topic: String, platform: Platform): String?
}
