# Architecture de FORGE

## 1. Vue d'ensemble et modules (§13.1)

Toute l'intelligence (décisions, garde-fous, mémoire, preuves) vit dans des modules Kotlin/JVM purs,
testables sans Android et réutilisables plus tard sur un PC compagnon. L'application Android ne
fournit que les « capteurs et effecteurs » : micro, haut-parleur, stockage, Drive, Intents, interface.

```
                                 ┌──────────── app (Android) ────────────┐
                                 │ UI Compose · voix · Room · MediaPipe  │
                                 │ Drive · Intents · Keystore · réseau   │
                                 └───────────────────┬───────────────────┘
        ┌──────────────┬──────────────┬──────────────┼──────────────┬──────────────┬─────────────┐
 forge-reasoning  forge-voice   forge-editorial  forge-academy  forge-actions  forge-evaluation  forge-research
   │   │   │                      │      │          │    │          │            │  │  │  │
   │   │   └── forge-security ◄───┼──────┼──────────┼────┼──────────┘            │  │  │  │
   │   └────── forge-memory ◄─────┼──────┼──────────┘    │                       │  │  │  │
   └────────── forge-models ──────┼──────┼───────────────┼───────────────────────┘  │  │  │
                (→ security)      │      │               │                          │  │  │
              forge-comic-intelligence ◄─┘  forge-writing ◄┘ (aussi ← editorial)     ┘  ┘  │
                                         tous ──► forge-core (modèle, texte, persona) ◄────┘
```

| Module | Rôle | Dépend de |
|---|---|---|
| `forge-core` | Types partagés (rôles, autonomie, profondeur, classes de données, tâches), texte FR, personnalité | — |
| `forge-security` | Portail d'autorisation 0–4, outils, politique de sortie des données, protection anti-injection | core |
| `forge-models` | Moteurs LLM (streaming annulable), routeur gouverné, usage/coûts, ressources, registre de moteurs | core, security |
| `forge-memory` | Mémoire 5 niveaux, recherche hybride, doublons, contradictions, rétention, export | core |
| `forge-voice` | Fin de tour, interruptions, découpage oral, session vocale, présence visuelle | core |
| `forge-reasoning` | Spécialistes, analyse d'intention, orchestrateur (+ classification des données) | core, models, memory, security |
| `forge-research` | Classification et corroboration des sources | core |
| `forge-comic-intelligence` | Claim Ledger P1–P5, références, noms de fichiers | core |
| `forge-writing` | Niveaux de correction, corrigés, analyses typographiques | core |
| `forge-editorial` | Formats, rôles Primestone, style oral, vérification, production, SRT, Motion Studio | core, comics, writing |
| `forge-academy` | Cours, explications, fiches, SM-2, quiz, lacunes, planning, examens | core, memory, writing |
| `forge-actions` | Moteur d'actions, journal, arrêt d'urgence, catalogue des mécanismes Android | core, security |
| `forge-browser` | Recherche Web interchangeable, récupération respectueuse (robots.txt), extraction lisible, dossier de sources | core, security, research |
| `forge-documents` | CBZ sécurisé, index de bibliothèque par page, OCR interchangeable, brouillons de preuves | core, memory, comics |
| `forge-evaluation` | Retours, leçons, versions d'instructions, bancs, bilans à erreurs critiques | core, memory, models, comics |

Aucune dépendance circulaire (graphe vérifié par Gradle, qui refuse les cycles entre projets).

**Modules du §13.1 non encore créés, et pourquoi :** `forge-conversation` (aujourd'hui dans
`forge-reasoning` : `ConversationContext` ; à extraire avec la persistance de l'historique),
`forge-drive` (le connecteur REST vit dans `app/` car il dépend de l'authentification Google ;
sa logique d'indexation est dans `forge-documents`), `forge-ui` (sera un module Android séparé quand `app/` compilera). Créer des
modules vides n'apporterait rien (§20 : « ne pas générer artificiellement des fichiers »).

### Contrats principaux entre modules

| Contrat | Fournisseur | Consommateurs |
|---|---|---|
| `LlmProvider.stream(ChatRequest): Flow<LlmEvent>` — annulable | models / app | reasoning, evaluation |
| `ProviderRouter.select(RoutingNeeds)` — `task` + `dataClass` obligatoires pour le cloud | models | reasoning |
| `EgressPolicy.check(provider, task, data)` | security | models |
| `UntrustedContent.wrap(source, text)` — tout contenu externe | security | reasoning (puis browser, documents) |
| `PermissionGate.evaluate` / `ToolRegistry.executeGuarded` | security | actions, reasoning |
| `MemoryStore` (persistance) / `MemoryManager` (logique) | memory / app (Room) | reasoning, academy, evaluation |
| `SpeechOutput`, `ResponseSource` | app / reasoning | voice |
| `ClaimLedger.record` (invariants P1–P5) | comics | editorial, evaluation |
| `EditorialVerifier.verify → ScriptStatus` | editorial | MotionStudioExporter, app |
| `LoadableModel` + `ModelResourceManager.acquire` | app / models | app |

### Modèles de données clés
`MemoryItem` (texte, type, clé sémantique, provenance, rétention, `supersededBy`) ·
`Claim` (affirmation, sources hiérarchisées et localisables, niveau P, citation exacte, continuité,
contradictions, statut) · `ScriptDocument` / `ScriptSegment` (narration, visuels, affirmations,
continuité) · `ProductionPlan` (séquences, plans minutés, médias, SRT) · `MotionStudioPackage`
(manifeste v1) · `Flashcard` (SM-2) · `ActionLogEntry` · `Metric` / `Scorecard`.

## 2. Décisions techniques et justifications

### 2.1 Langage et UI — Kotlin + Jetpack Compose
Kotlin est le langage de référence d'Android, les coroutines/Flow modélisent naturellement le
streaming annulable dont la voix a besoin. Compose : UI déclarative, état réactif (indicateurs
d'écoute/réflexion/parole). Alternatives écartées : Flutter/React Native (couche supplémentaire
entre l'app et les API audio/IA natives, intégration MediaPipe/LiteRT moins directe).

### 2.2 Moteurs de langage — abstraction + routage local-first
| Option | Qualité | Latence S25 | Coût | Confidentialité | Rôle dans Forge |
|---|---|---|---|---|---|
| MediaPipe LLM Inference (Gemma 3 1B–4B int4, GPU) | moyenne | bonne (premier jeton < 1 s visé) | gratuit | totale | défaut hors-ligne, mode Instant |
| Serveur LAN compatible OpenAI (Ollama, llama.cpp sur PC) | bonne à très bonne (7B–70B) | bonne en Wi-Fi | gratuit | totale (réseau domestique) | Standard / Expert sans cloud |
| API cloud (Anthropic, OpenAI, Mistral…) | excellente | variable | payant | dépend du fournisseur | facultatif, Deep Research / Production |

`ProviderRouter` filtre par contraintes dures (contexte, vision, réseau, politique « local uniquement »,
autorisation par fournisseur, tâche et classe de données, limites d'usage) puis classe : qualité suffisante → **local d'abord** → qualité → coût.
Par défaut, le cloud n'est autorisé que pour les profondeurs Deep Research / Expert / Production,
uniquement pour les tâches cochées, et uniquement pour des demandes sans mémoire ni document
(`DataClass.GENERAL`). L'orchestrateur classe chaque requête AVANT le choix du moteur. Une seule interface `LlmProvider` (flux annulable)
couvre tous les moteurs ; `OpenAiCompatibleProvider` couvre à lui seul llama.cpp, Ollama,
LM Studio, vLLM, Mistral, OpenRouter et OpenAI.

### 2.3 Voix
| Brique | Choix initial | Pourquoi | Évolution prévue |
|---|---|---|---|
| Reconnaissance | `SpeechRecognizer` on-device (Android 13+) | Gratuit, local, qualité FR correcte, zéro modèle à embarquer | Whisper (whisper.cpp) ou Sherpa-ONNX streaming pour la ponctuation et le contrôle fin |
| Fin de tour | `Endpointer` (cœur) + endpointing natif | Le français hésite (« euh », « et… ») : délai adaptatif | VAD Silero en local |
| Interruption | `BargeInClassifier` + `VoiceSessionController` | « Attends », « changeons de sujet », acquiescements ignorés | Annulation d'écho (AEC) pour le duplex sans écouteurs |
| Synthèse | `TextToSpeech` système (meilleure voix FR locale) | Disponible immédiatement, latence faible | Piper / Sherpa-ONNX (voix neuronale locale propre à Forge), cloud facultatif |
| Streaming | `SpeechChunker` | Forge parle dès la 1re phrase complète, nettoie Markdown et URL | — |

**Identité vocale.** La personnalité est définie dans `ForgePersona` indépendamment du moteur.
La voix propre à Forge sera obtenue en entraînant/affinant une voix Piper à partir d'un
locuteur **consentant** ou d'une voix libre de droits, sans imiter un acteur ou un personnage.

### 2.4 Orchestration et spécialistes
`IntentAnalyzer` route par règles explicables (rapide, hors-ligne, sans appel LLM — essentiel
pour la latence vocale). Douze spécialistes ont chacun mission, instructions, outils autorisés,
types de mémoire et critères d'évaluation (`Specialists.kt`). L'orchestrateur :
1. extrait les souvenirs explicites ; 2. analyse l'intention et la profondeur ; 3. choisit le moteur ;
4. rappelle la mémoire pertinente ; 5. fait travailler les spécialistes de soutien (mode approfondi) ;
6. diffuse directement (Instant/Standard) **ou** produit un brouillon → contrôle qualité → révision ;
7. met à jour la mémoire immédiate (y compris les réponses interrompues, marquées comme telles).

Le contrôle qualité n'est pas un agent décoratif : il a un format de verdict (`OK | REVISER`) et
déclenche une passe de correction effective.

### 2.5 Mémoire
Cinq niveaux persistés (personnelle, projets, documentaire, procédurale, épisodique) + mémoire
immédiate (contexte de conversation). Recherche **hybride** : BM25 sur racines françaises +
similarité vectorielle, fusionnées par Reciprocal Rank Fusion (aucun calibrage de scores).
Garde-fous : rien n'est mémorisé sans consigne explicite ou confirmation ; détection des doublons ;
détection des contradictions (même clé sémantique ou polarité inversée) soumise à l'utilisateur ;
remplacement traçable (l'ancien souvenir est conservé avec `supersededBy`) ; durée de conservation ;
export JSON ; provenance systématique.
Persistance Android : Room (SQLite), index en mémoire vive chargé au démarrage.

### 2.6 Autonomie contrôlée
`PermissionGate` applique les niveaux 0–4 : plafond fixé par l'utilisateur ; niveau 3 seulement
avec approbation permanente non expirée ; **toute action irréversible, à effet externe ou de niveau 4
exige une confirmation explicite à usage unique**. Chaque décision est journalisée (audit).
Les actions Android passent par des Intents publics : l'application cible affiche le résultat et
l'utilisateur garde la main (ex. le brouillon d'agenda n'est enregistré qu'après validation).

### 2.7 Comic Intelligence
`ClaimLedger` implémente la hiérarchie des sources (§8.1) et les niveaux P1–P5 (§8.2) avec des
invariants **appliqués par le code**, pas seulement demandés au modèle : P1 exige une source primaire
vérifiée avec numéro et page ; sans source primaire, P2 exige deux sources fortes ; sources
secondaires seules → P4 maximum ; contradiction → P3 maximum ; aucune source → P5. Un niveau
injustifié est rétrogradé avec explication. Le contrôle qualité des scripts bloque toute mention de
numéro ou de page qui ne s'appuie pas sur une affirmation enregistrée (anti-invention).

### 2.8 Google Drive
API Drive v3 + Google Identity Services. Portée **`drive.file`** par défaut (fichiers choisis par
l'utilisateur), `drive.readonly` uniquement sur activation explicite de l'indexation d'un dossier.
Métadonnées d'abord, contenu à la demande, empreinte md5 pour éviter les retéléchargements,
reprise des transferts (Range). Les noms de fichiers comics sont analysés prudemment
(`ComicFileName`) : ce qui n'est pas reconnu reste vide.

### 2.9 Forge Brain
Amélioration **sans prétendre réentraîner les poids** : retours typés (7 catégories du cahier des
charges), satisfaction par spécialiste et version d'instructions, leçons procédurales tirées des
retours commentés (soumises à confirmation), registre d'instructions versionnées avec retour
arrière, et jeu d'évaluations reproductible (dont une base « honnêteté » : pas de flatterie,
pas d'invention de numéro, pas d'action simulée).

## 3. Confidentialité
- Clés API chiffrées AES-256-GCM avec clé Android Keystore ; exclues des sauvegardes.
- Base de mémoire exclue de la sauvegarde cloud et du transfert d'appareil.
- Micro uniquement sur action de l'utilisateur, notification permanente en mode mains libres,
  arrêt d'urgence depuis l'app et la notification.

### 2.10 Moteur local : MediaPipe/LiteRT d'abord, llama.cpp ensuite
| Critère | MediaPipe / LiteRT-LM (.task) | llama.cpp (GGUF, JNI) |
|---|---|---|
| Accélération S25 | GPU (et NPU selon versions) | CPU ARM (i8mm), GPU Vulkan/OpenCL expérimental |
| Choix de modèles | Gemma surtout | Très large (Qwen, Mistral, Llama, Phi…) |
| Intégration | Dépendance Gradle | Compilation NDK, JNI à maintenir |
| Appels d'outils | À construire par prompt | À construire par prompt / grammaires GBNF |

Décision : MediaPipe pour la voix (latence), llama.cpp ajouté en J3 pour le choix des modèles
et les grammaires contraintes (appels d'outils fiables). Les deux derrière `LlmProvider` +
`ModelResourceManager` : un seul gros modèle résident à la fois.

### 2.11 Autres choix (§13)
| Besoin | Choix | Raison |
|---|---|---|
| Architecture app | MVVM + modules de domaine purs (Clean Architecture « légère ») | Les règles métier sont déjà isolées et testées ; pas de couche use-case vide |
| Persistance | Room/SQLite ; FTS5 via `SupportSQLiteDatabase` brut quand le volume l'exigera | Room n'expose que FTS3/4 en annotations |
| Vecteurs | Index en RAM puis sqlite-vec si > 50 000 éléments | Simplicité tant que le volume est faible |
| Embeddings | Modèle multilingue léger en LiteRT (ex. E5-small / EmbeddingGemma) | Paraphrase FR, ~100–300 Mo |
| OCR | ML Kit Text Recognition (latin), sur l'appareil | Gratuit, local, rapide |
| PDF | `PdfRenderer` (rendu) + extraction texte (PdfBox-Android) | Natif + texte sélectionnable |
| Tâches de fond | WorkManager (contraintes batterie/réseau) | Indexation Drive, révisions |
| Secrets | Android Keystore (AES-GCM) | Matériel, non exportable |
| Réglages | SharedPreferences aujourd'hui, DataStore si besoin de flux réactifs | Suffisant pour l'instant |
