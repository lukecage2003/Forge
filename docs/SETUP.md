# Mise en place

## Installer Forge sur le Galaxy S25
1. Copier `app-debug.apk` sur le téléphone (ou `adb install app-debug.apk`).
2. Autoriser l'installation depuis la source utilisée (Paramètres → Sécurité → Installer des applis inconnues).
3. Ouvrir Forge et toucher **« Installer le cerveau de Forge »** (1,6 Go, Wi-Fi conseillé). C'est tout :
   Forge fonctionne ensuite sur le téléphone, même hors connexion. Les autres moteurs ci-dessous sont facultatifs.

## Moteurs d'intelligence (au choix, cumulables)
- **Local sur le téléphone (hors-ligne)** : télécharger un modèle MediaPipe au format `.task`
  (par exemple Gemma 3 1B ou Gemma 3n, publiés par Google sur Kaggle et Hugging Face — leur licence
  doit être acceptée sur le site), puis Réglages → « Choisir un modèle local (.task) ».
- **Serveur domestique** : `ollama serve` sur un PC du même réseau (avec `OLLAMA_HOST=0.0.0.0`),
  puis dans Réglages `http://IP_DU_PC:11434/v1` et le nom du modèle (ex. `qwen3:8b`).
- **Cloud facultatif** : clé Anthropic ou API compatible OpenAI. Par défaut, seules les demandes
  sans mémoire ni document lui sont envoyées ; élargir dans Réglages si vous le souhaitez.
- « Tester les connexions » vérifie réellement chaque moteur.

## Recherche Web (facultatif)
- **SearXNG** (recommandé, peut tourner chez vous : `docker run -p 8080:8080 searxng/searxng`,
  format JSON activé dans `settings.yml`), URL à saisir dans Réglages.
- Ou une clé **Brave Search API**.

## Bibliothèque comics
Onglet **Comics** → « Ajouter des comics (CBZ) » : les fichiers choisis sont indexés sur le téléphone.

## Google Drive (facultatif)
Dans Google Cloud Console : créer un projet, activer **Google Drive API**, créer un identifiant
OAuth de type **Android** (package `ai.forge.app` + empreinte SHA-1 de la clé de signature),
configurer l'écran de consentement. Forge demande la portée `drive.file` par défaut.

## Développement
```bash
./gradlew test                          # modules JVM
./gradlew :app:testDebugUnitTest        # tests Android (Robolectric)
./gradlew :app:assembleDebug            # APK (SDK Android 35 requis, local.properties : sdk.dir=…)
```
