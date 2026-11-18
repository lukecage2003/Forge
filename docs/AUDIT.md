# Audit technique — état au commit `b52a050`

Audit réalisé avant toute modification, conformément au §17 (étape 1).

## 1. Inventaire

| Élément | Constat |
|---|---|
| Langage | Kotlin 2.1.21 (JVM 17), Gradle 8.14.3 avec catalogue de versions |
| Modules | 2 : `forge-core` (JVM pur, ~2 400 lignes) et `app` (Android, ~1 100 lignes) |
| Dépendances cœur | kotlinx-coroutines, kotlinx-serialization, OkHttp |
| Dépendances app | Compose (BOM 2025.05), Room 2.7, MediaPipe tasks-genai 0.10.24, play-services-auth 21.3 |
| Tests | 45 tests unitaires JVM, tous verts ; aucun test d'intégration, de performance, de sécurité ni sur appareil |
| Build Android | **Jamais compilé** (SDK non installé : licences à accepter par le propriétaire) |

## 2. Cartographie des fonctionnalités existantes

| Domaine (cahier des charges) | Existant | Niveau de maturité |
|---|---|---|
| §1.3 Autonomie 0–4 | `PermissionGate`, audit, approbations à durée limitée | Fondation testée |
| §2 Voix | Logique : fin de tour, interruptions, découpage, contrôleur. Android : STT/TTS système, service premier plan | Logique testée ; Android non compilé |
| §3 Forge Core | 12 spécialistes, analyse d'intention par règles, orchestrateur avec contrôle qualité | Testé avec moteur scripté |
| §3.3 Profondeurs | 5 niveaux, choisis par règles | Testé |
| §4 Mémoire | Hybride BM25 + vecteurs, doublons, contradictions, rétention, export | Testé ; embedder seulement lexical |
| §5 Forge Brain | Retours, leçons, versions d'instructions, évaluations | Testé ; pas d'interface utilisateur |
| §6 Browser | Classification de sources, corroboration | Partiel : **aucune recherche ni navigation réelles** |
| §7 Google Drive | Connecteur REST v3, OAuth `drive.file`, cache md5, reprise | Non compilé, non testé |
| §8 Comic Intelligence | Claim Ledger P1–P5 avec invariants, analyse de noms de fichiers | Testé ; pas d'OCR ni de lecture de pages |
| §9 Éditorial | Formats, 10 rôles, contrôle qualité de script, export Markdown | Testé ; §9.5–9.7 absents |
| Writing & Correction | 5 niveaux, 4 types de corrigés, typographie | Testé (prompts + analyses déterministes) |
| §10 Academy | — | Absent |
| §11 Action Engine | 4 outils Intents | Non compilé ; pas de journal utilisateur ni d'arrêt global |
| §12 Interface | Chat + réglages, indicateur 4 états | Non compilé ; charte or/noir partielle, 3 états visuels manquants |
| §13.1 Modules | Monolithe `forge-core` découpé en paquets | **Non conforme** |
| §13.2 Ressources | — | Absent |
| §14 Fournisseurs | Clés chiffrées, choix d'URL/modèle | Partiel : pas de test de connexion, limites, coûts ni tâches autorisées |
| §15 Sécurité | Keystore, exclusion des sauvegardes | Partiel : **aucune protection contre l'injection de prompt** |
| §16 Évaluation | Évaluateur par cas | Partiel : pas de bancs par domaine ni de seuils critiques |

## 3. Problèmes identifiés

### Critiques (à corriger avant toute nouvelle fonctionnalité)
1. **Fuite possible de données privées vers le cloud.** `RoutingNeeds.sensitiveData` existe mais
   l'orchestrateur ne le renseigne jamais. Une requête comics en Deep Research peut être routée vers
   un moteur cloud avec des extraits de documents en mémoire. Contraire au §14.
2. **Injection de prompt.** Les souvenirs (dont ceux issus de documents ou du Web) sont injectés tels
   quels dans le prompt système. Un document contenant « ignore tes règles » serait lu comme une
   consigne. Contraire au §15.
3. **Pas d'arrêt global des actions.** L'arrêt d'urgence coupe la voix mais pas les actions en cours.

### Majeurs
4. Monolithe : les 17 modules du §13.1 n'existent pas ; risque de dépendances croisées.
5. Le contrôle qualité éditorial ne vérifie ni les dates, ni les citations, ni les confusions de
   versions de personnages, ni le statut « vérifié » d'un script (§9.5).
6. L'évaluateur produit un taux global qui pourrait masquer une erreur critique (§16).
7. Aucune gestion des ressources du modèle local (chargement/déchargement, thermique, batterie).
8. Aucun mode hors-ligne explicite : les fonctions réseau ne sont pas signalées comme indisponibles.

### Mineurs
9. `MediaPipeLocalProvider` : l'annulation n'arrête pas le calcul ; API à confirmer à la compilation.
10. Les vecteurs sont stockés en JSON (volumineux) ; pas de FTS5 côté SQLite.
11. `VoiceCoordinator.start(handsFree)` ignore son paramètre.
12. Réglages en SharedPreferences : acceptable, DataStore non nécessaire à ce stade.

## 4. Compatibilité Samsung Galaxy S25

| Ressource | S25 (référence) | Conséquence pour Forge |
|---|---|---|
| SoC | Snapdragon 8 Elite for Galaxy (CPU Oryon, GPU Adreno, NPU Hexagon) | GPU exploitable par LiteRT/MediaPipe ; NPU via Qualcomm QNN (accès plus complexe) |
| RAM | 12 Go, partagés avec le système et les apps | Budget réaliste pour un LLM local : **3 à 4 Go**, un seul modèle chargé à la fois |
| Stockage | 128 Go minimum | Quelques modèles de 1 à 4 Go acceptables ; bibliothèque comics à mettre en cache partiellement |
| OS | Android 15 (One UI 7), mis à jour depuis | Service premier plan « microphone » démarrable seulement depuis l'app visible ; AppFunctions à vérifier selon version |
| Thermique | Boîtier compact | Inférence prolongée → baisse de fréquence ; à mesurer, à limiter |

Les chiffres de performance des modèles ci-dessous sont des **ordres de grandeur à mesurer sur l'appareil**, pas des résultats.

| Modèle local (int4) | Taille | Français | Usage visé |
|---|---|---|---|
| Gemma 3n E2B / E4B (LiteRT/MediaPipe) | ~2–4 Go | correct à bon | conversation vocale rapide (défaut) |
| Qwen3 4B (GGUF, llama.cpp) | ~2,5 Go | bon | raisonnement, appels d'outils |
| Llama 3.2 3B / Phi-4-mini | ~2–2,5 Go | moyen | alternatives |
| 7B et plus | ≥ 4,5 Go | bon | **déconseillé sur le téléphone** → serveur domestique |

## 5. Faisabilité par module

| Module | Faisabilité sur S25 | Remarque |
|---|---|---|
| Conversation écrite | Élevée | Local pour l'instantané, LAN/cloud pour l'expert |
| Voix temps réel | Élevée avec réserves | Duplex sans écouteurs dépend de l'annulation d'écho |
| Mémoire / RAG | Élevée | Embedder local léger (~100 Mo) nécessaire |
| Recherche Web | Élevée | Nécessite un fournisseur de recherche (clé ou SearXNG) |
| Drive + indexation | Élevée | Indexation progressive, jamais tout télécharger |
| OCR des bulles de comics | Moyenne | ML Kit lit bien le latin imprimé, moins les lettrages stylisés |
| Détection des cases | Moyenne | Heuristiques de gouttières fiables sur les mises en page classiques |
| Reconnaissance des personnages | **Faible** | Pas de modèle fiable embarqué ; signaler comme expérimental |
| Éditorial / scripts | Élevée | Qualité dépend du moteur : préférer LAN/cloud autorisé |
| Academy | Élevée | Révision espacée et quiz entièrement locaux |
| Actions Android | Moyenne | Limitée aux Intents / API publiques ; pas de contrôle illimité |
| Plusieurs modèles simultanés | **Non réaliste** | Un LLM + STT + TTS au maximum ; le reste à la demande |

## 6. Risques techniques

| Risque | Probabilité | Impact | Atténuation |
|---|---|---|---|
| Compilation Android révélant des écarts d'API | Élevée | Moyen | Compiler dès que le SDK est disponible (J1) |
| Latence vocale trop élevée en local | Moyenne | Élevé | Modèle 2B pour l'oral, réponses courtes, streaming par phrase |
| Écho micro/haut-parleur | Élevée | Moyen | Écouteurs, bouton « Interrompre », AEC (J2) |
| Hallucination de références comics | Élevée (modèles) | Critique | Invariants du Claim Ledger + blocage au contrôle qualité |
| Fuite de données privées | Faible après correctif | Critique | Politique de sortie des données (ce lot) |
| Injection via documents/Web | Moyenne | Élevé | Encapsulation des contenus non fiables (ce lot) |
| Throttling thermique | Moyenne | Moyen | Gestionnaire de ressources (ce lot) |
| Quotas/coûts API | Moyenne | Moyen | Limites d'usage par fournisseur (ce lot) |

## 7. Décision

La base est saine (cœur testé, séparation Android/logique déjà en place) : **pas de réécriture**.
Ce lot corrige les défauts critiques, découpe le monolithe en modules conformes au §13.1 sans perte
de fonctionnalité, puis ajoute les fondations manquantes. Voir `ROADMAP.md`.
