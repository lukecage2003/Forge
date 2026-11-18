# FORGE — intelligence personnelle

Assistant personnel conversationnel, local-first, conçu pour le Samsung Galaxy S25.
Voix naturelle, mémoire contrôlable, recherche sourcée, intelligence documentaire comics,
rédaction Primestone Media, correction et corrigés pédagogiques — sous un contrôle d'autonomie strict.

## Structure

| Partie | Contenu | État |
|---|---|---|
| 13 modules `forge-*` | Cerveau en Kotlin/JVM pur : sécurité, moteurs, mémoire, voix, raisonnement, comics, éditorial, Academy, actions, évaluation | Compile, **79 tests passent** |
| `app/` | Application Android (Compose) : voix, Room, modèle local, Drive, actions, Action Center, réglages | Écrit, **pas encore compilé** |
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
