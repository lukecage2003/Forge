# FORGE — intelligence personnelle

Assistant personnel conversationnel, local-first, conçu pour le Samsung Galaxy S25.
Voix naturelle, mémoire contrôlable, recherche sourcée, intelligence documentaire comics,
rédaction Primestone Media, correction et corrigés pédagogiques — sous un contrôle d'autonomie strict.

## Structure

| Module | Contenu | État |
|---|---|---|
| `forge-core/` | Cerveau en Kotlin/JVM pur : orchestrateur, spécialistes, mémoire, autorisations, logique vocale, Claim Ledger, éditorial, correction, Forge Brain | Compile, **45 tests passent** |
| `app/` | Application Android (Compose) : micro, synthèse vocale, service mains libres, Room, modèle local MediaPipe, Google Drive, actions Android, réglages chiffrés, export PDF | Écrit, **pas encore compilé** (voir `docs/STATUS.md`) |
| `docs/` | Architecture, décisions, état réel, feuille de route, mise en place | — |

## Démarrer

```bash
./gradlew :forge-core:test          # tests du cœur (aucun SDK Android requis)
```

Pour l'application Android : installer le SDK Android (plateforme 35), puis `./gradlew :app:assembleDebug`.
Le module `:app` n'est inclus que si un SDK est détecté (`ANDROID_HOME` ou `local.properties`).

Lire ensuite : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · [`docs/STATUS.md`](docs/STATUS.md) ·
[`docs/ROADMAP.md`](docs/ROADMAP.md) · [`docs/SETUP.md`](docs/SETUP.md)
