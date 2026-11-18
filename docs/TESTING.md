# Stratégie de tests et d'évaluation (§16)

## Ce qui existe
`./gradlew test` exécute **79 tests unitaires et d'intégration JVM** répartis dans 12 modules.

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
