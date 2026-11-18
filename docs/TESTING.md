# Stratégie de tests et d'évaluation (§16)

## Ce qui existe
`./gradlew test` exécute **96 tests JVM** (14 modules) ; `./gradlew :app:testDebugUnitTest` exécute **7 tests Android** sous Robolectric (démarrage de l'app, Room, Drive, bibliothèque, scénario G).

| Type | Où | Exemples |
|---|---|---|
| Unitaires | chaque module | invariants P1–P5, SM-2, fin de tour, BM25, SRT |
| Intégration (JVM) | reasoning, models | orchestrateur + mémoire + routeur + politique de sortie ; SSE sur serveur HTTP simulé |
| Non-régression | reasoning, editorial | requête comics jamais envoyée à un cloud non autorisé ; découpage SRT ≤ 42 caractères |
| Sécurité | security, reasoning | injections FR/EN, délimiteurs infalsifiables, alerte remontée, refus d'egress, secrets |
| Bancs comportementaux | evaluation | mémoire (rappel, suppression respectée, contradiction), références inventées, latence |

## Règles
- Un bilan (`Scorecard`) échoue dès qu'une métrique **critique** échoue, quelle que soit la moyenne.
- Métriques critiques actuelles : suppression respectée, contradiction signalée, absence de
  référence inventée, génération sans échec.
- Chaque défaut corrigé reçoit un test de non-régression.

## Ce qui manque (et sera fait en J1+)
| Type | Plan |
|---|---|
| Tests instrumentés Android | Room, Keystore, TTS/STT réels, permissions, service premier plan |
| Tests sur appareil réel (S25) | `LatencyProbe` + mesures RAM/température/batterie, consignées dans `docs/reports/` |
| Performances | premier jeton, voix-à-voix, temps de chargement des modèles, débit d'indexation |
| Évaluation par modèle | `Evaluator.honestyBaseline` + `ReferenceGuard` exécutés contre chaque moteur configuré |
| UI | tests Compose des états de présence et de l'Action Center |

## Grille de validation sur le Galaxy S25 (à remplir lors du premier essai)
| Mesure | Méthode | Cible indicative | Résultat |
|---|---|---|---|
| Premier jeton (modèle local) | Réglages → message court ; chronomètre | < 1,5 s | à mesurer |
| Voix-à-voix | fin de phrase → début de la voix de Forge | < 2,5 s | à mesurer |
| RAM avec modèle chargé | `adb shell dumpsys meminfo ai.forge.app` | < 4,5 Go | à mesurer |
| Température après 10 min de conversation | `adb shell dumpsys thermalservice` | pas de bridage SEVERE | à mesurer |
| Batterie | 30 min de conversation vocale | < 10 % | à mesurer |
| Interruption « attends » | 10 essais, avec et sans écouteurs | ≥ 9/10 avec écouteurs | à mesurer |
| Indexation OCR | CBZ de 24 pages | < 60 s | à mesurer |
