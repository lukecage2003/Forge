# Historique des versions

## 0.3.0 — 18/11/2026
- **Application Android compilée** (SDK 35), APK construit, Lint sans erreur ; 7 tests Robolectric.
- Actions de bout en bout (scénario G) : planification, portail, Intent réel, résultat rapporté par le code.
- `forge-browser` : recherche Web (SearXNG/Brave), robots.txt, extraction lisible, sources encadrées (scénario C).
- `forge-documents` : CBZ sécurisé, index de bibliothèque, OCR ML Kit, preuves « non vérifiées » (scénario D).
- Écrans Mémoire (consulter, supprimer, exporter) et Bibliothèque (import de CBZ depuis le téléphone).
- Choix du modèle local par fichier ; correctifs : moteur local qui fuyait, exécuteur d'actions nul, OCR au démarrage.

## 0.2.0 — 18/11/2026
- Audit technique (`docs/AUDIT.md`) ; découpage en 13 modules JVM.
- Correctif critique : aucune donnée privée vers un fournisseur cloud sans autorisation explicite.
- Protection contre l'injection de prompt ; gouvernance des fournisseurs (tâches, limites, coûts estimés, test réel).
- Action Engine (journal, arrêt d'urgence) ; gestion des ressources du modèle local ; bascule hors-ligne.
- Éditorial §9.4–9.7 ; Forge Academy ; évaluation à erreurs critiques ; présence visuelle et charte noir/or.
- 79 tests JVM verts. Application Android toujours non compilée.

## 0.1.0 — 17/11/2026
- Cœur initial et application Android (non compilée). 45 tests JVM.
