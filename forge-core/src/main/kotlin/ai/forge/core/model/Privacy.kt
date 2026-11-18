package ai.forge.core.model

import kotlinx.serialization.Serializable

/**
 * Sensibilité des données contenues dans une requête (§14, §15).
 * L'ordre est significatif : plus l'ordinal est élevé, plus la donnée est sensible.
 */
@Serializable
enum class DataClass(val label: String) {
    /** Le seul texte de la demande, sans mémoire ni document. */
    GENERAL("Demande seule"),
    /** Contient des souvenirs personnels (préférences, projets, épisodes). */
    PERSONAL("Données personnelles"),
    /** Contient des extraits de documents privés : bibliothèque comics, Drive, cours. */
    PRIVATE_DOCUMENTS("Documents privés"),
    /** Secrets (clés, mots de passe) : ne sortent jamais de l'appareil. */
    SECRET("Secrets"),
}

/** Catégories de tâches, pour autoriser un fournisseur tâche par tâche (§14). */
@Serializable
enum class TaskCategory(val label: String) {
    CONVERSATION("Conversation"),
    REASONING("Raisonnement"),
    RESEARCH("Recherche"),
    DOCUMENTS("Documents et comics"),
    EDITORIAL("Éditorial"),
    STUDY("Études"),
    WRITING("Rédaction et correction"),
    ACTIONS("Actions Android"),
}
