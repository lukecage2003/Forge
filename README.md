# FORGE — intelligence personnelle

Assistant personnel conversationnel, local-first, conçu pour le Samsung Galaxy S25.
Voix naturelle, mémoire contrôlable, recherche sourcée, intelligence documentaire comics,
rédaction Primestone Media, correction et corrigés pédagogiques — sous un contrôle d'autonomie strict.

## Structure

| Partie | Contenu | État |
|---|---|---|
| 15 modules `forge-*` | Cerveau en Kotlin/JVM pur : sécurité, moteurs, mémoire, voix, raisonnement, recherche Web, documents, comics, éditorial, Academy, actions, évaluation | **96 tests passent** |
| `app/` | Application Android (Compose) : voix, Room, modèle local, OCR, bibliothèque, recherche, Drive, actions, mémoire, réglages | **Compile**, lint sans erreur, **7 tests Robolectric** ; pas encore essayée sur un S25 |
| `docs/` | Audit, architecture, état réel, plan, tests, permissions, Motion Studio, installation | — |

## Démarrer

```bash
./gradlew test          # tous les tests JVM (aucun SDK Android requis)
```

Pour l'application Android : installer le SDK Android (plateforme 35), puis `./gradlew :app:assembleDebug`.
Le module `:app` n'est inclus que si un SDK est détecté (`ANDROID_HOME` ou `local.properties`).

Lire ensuite : [`docs/AUDIT.md`](docs/AUDIT.md) · [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) ·
[`docs/STATUS.md`](docs/STATUS.md) · [`docs/ROADMAP.md`](docs/ROADMAP.md) · [`docs/TESTING.md`](docs/TESTING.md) ·
[`docs/PERMISSIONS.md`](docs/PERMISSIONS.md) · [`docs/MOTION_STUDIO.md`](docs/MOTION_STUDIO.md) · [`docs/SETUP.md`](docs/SETUP.md)
