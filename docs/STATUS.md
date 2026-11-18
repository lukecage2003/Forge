# État réel du projet

Ce qui fonctionne, ce qui est écrit mais non vérifié, ce qui n'existe pas. Mise à jour : 18/11/2026 (v0.2.0).

## Vérifié : compilé et testé (JVM)
`./gradlew test` → **79 tests, 0 échec**, 12 modules testés (`forge-core` ne contient que des types partagés).

| Module | Tests | Ce qui est démontré |
|---|---|---|
| forge-security | 7 | Plafond d'autonomie, confirmation unique, expiration, audit ; anti-injection ; politique de sortie |
| forge-models | 10 | SSE OpenAI-compatible et Anthropic, erreurs HTTP, test de connexion réel, cache, coûts/limites, ressources (LRU, thermique, batterie), bascule hors-ligne |
| forge-memory | 9 | Rappel avec provenance, doublons, contradictions, confirmation, rétention, export |
| forge-voice | 7 | Fin de tour, interruptions, découpage oral, annulation de la parole, états de présence |
| forge-reasoning | 11 | Routage, diffusion, contrôle qualité + révision, mémoire explicite, **comics jamais vers un cloud non autorisé**, injection encadrée et signalée, refus expliqué |
| forge-comic-intelligence | 7 | Invariants P1–P5, champs inconnus signalés, noms de fichiers |
| forge-editorial | 12 | Style oral, 8 types d'anomalies, statut « Vérifié », cadence 3–5 s sans image imposée, SRT, export Motion Studio conditionnel |
| forge-academy | 5 | Cours cités par page, fiches mot pour mot, SM-2, [Cours]/[Hors cours], lacunes, planning, examen |
| forge-actions | 2 | Journal de résultats réels, arrêt d'urgence qui annule et bloque |
| forge-evaluation | 5 | Erreur critique jamais masquée, banc mémoire, références inventées, sonde de latence |
| forge-writing, forge-research | 3 | Prompts de correction/corrigé, typographie, sources et corroboration |

## Écrit mais NON compilé : `app/`
Le SDK Android n'est pas installé dans l'environnement de développement : l'acceptation de ses
licences revient au propriétaire. Des erreurs de compilation sont probables (API MediaPipe, icônes,
signatures Compose). **Aucun test sur appareil, aucune mesure de performance réelle.**

## Limites connues
- Interruption vocale sans écouteurs : risque d'écho (bouton « Interrompre » disponible).
- `HashingEmbedder` est lexical, pas sémantique.
- Analyse d'intention par règles : peut se tromper sur des formulations inattendues.
- Les contrôles éditoriaux sont des heuristiques : ils complètent le Fact Checker, ne le remplacent pas.
- Coûts : estimations à partir d'un tarif saisi par l'utilisateur, jetons estimés (≈ 4 caractères).
- Le modèle ne déclenche pas encore lui-même d'actions (pas d'appel d'outils) : l'Action Engine existe,
  son branchement sur le modèle est prévu en J3.

## Pas encore développé
Recherche/navigation Web, lecture PDF/CBZ, OCR, détection des cases, indexation Drive en arrière-plan,
exports DOCX, voix neuronale propre, llama.cpp, embedder sémantique, écrans Mémoire/Projets/Recherche/
Drive/Comics/Éditorial/Academy/Modèles dans l'app. Voir `ROADMAP.md`.
