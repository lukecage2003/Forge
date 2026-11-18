# État réel du projet

Ce document dit ce qui fonctionne, ce qui est écrit mais non vérifié, et ce qui n'existe pas encore.
Il est mis à jour à chaque étape. Dernière mise à jour : 18/11/2026.

## Vérifié (compilé et testé)
`./gradlew :forge-core:test` → **45 tests, 0 échec**.

| Domaine | Ce qui est testé |
|---|---|
| Autorisations | Plafond, refus au-delà, confirmation unique pour l'irréversible, expiration des automatisations, audit |
| Mémoire | Rappel avec provenance, déduplication, contradiction par clé et par négation, remplacement, confirmation des préférences, non-mémorisation des bavardages, expiration, export, BM25 |
| Voix (logique) | Fin de tour adaptative, interruptions (« attends », changement de sujet, fin de session, acquiescements), découpage en phrases, annulation de la parole avec conservation du partiel |
| Comics | Rétrogradation wiki → P4, P1 vérifié, P2 sans page, contradiction → P3, P5 sans source, champs inconnus signalés, analyse de noms de fichiers |
| Orchestrateur | Routage des spécialistes, diffusion directe, contrôle qualité + révision, mémorisation explicite, échec honnête sans moteur, routage local-first et confidentialité |
| Moteurs | Flux SSE compatible OpenAI et Anthropic (serveur simulé), erreurs HTTP, en-têtes d'authentification |
| Éditorial / écriture | Cibles de format, contrôle qualité des scripts, reconstruction des Shorts, typographie française, prompts de correction et de corrigé, classification des sources, Forge Brain |

## Écrit mais NON compilé
Le module `app/` (Android) n'a pas pu être compilé dans l'environnement de développement :
l'installation du SDK Android exige l'acceptation de ses licences, qui revient au propriétaire du projet.
Des erreurs de compilation mineures sont donc possibles (versions d'API MediaPipe, icônes Compose…).
Aucun test sur appareil n'a été effectué.

## Limites connues
- **Interruption vocale sans écouteurs** : le micro peut capter la voix de Forge (pas d'annulation
  d'écho garantie avec `SpeechRecognizer`). Bouton « Interrompre » toujours disponible.
- **Reconnaissance vocale** : fonctionnement par sessions relancées ; la qualité dépend du moteur
  installé (Samsung/Google).
- **Embeddings** : `HashingEmbedder` est lexical, pas sémantique. Un vrai modèle d'embedding local
  est nécessaire pour la paraphrase.
- **Modèle local MediaPipe** : l'annulation interrompt la diffusion, pas forcément le calcul.
- **Routage d'intention** : par règles ; peut se tromper sur des formulations inattendues.
- **Mémoire Android** : index chargé en RAM ; adapté à quelques dizaines de milliers de souvenirs.

## Pas encore développé
Recherche Web (outils `web.search`/`web.fetch`), extraction PDF/CBZ et OCR, analyse des cases de
comics, indexation Drive progressive en arrière-plan, Forge Academy (cours), exports DOCX,
exécution des appels d'outils par le modèle (function calling), voix neuronale propre à Forge,
mode mains libres avec mot d'activation. Voir `ROADMAP.md`.
