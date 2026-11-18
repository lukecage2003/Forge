# Mise en place

## Cœur (toute machine avec JDK 17+)
```bash
./gradlew :forge-core:test
```

## Application Android
1. Installer Android Studio (ou les command-line tools) et la plateforme **Android 35**.
   L'acceptation des licences du SDK vous revient (`sdkmanager --licenses`).
2. Créer `local.properties` avec `sdk.dir=/chemin/vers/Android/sdk`.
3. `./gradlew :app:assembleDebug` puis installer sur le Galaxy S25 (`adb install`).

## Moteurs d'intelligence (au choix, cumulables)
- **Local sur le téléphone** : copier un modèle MediaPipe `.task` (ex. Gemma 3) sur l'appareil,
  renseigner son chemin dans Réglages.
- **Serveur domestique** : `ollama serve` sur un PC (exposé sur le réseau local), puis dans Réglages
  `http://IP_DU_PC:11434/v1` et le nom du modèle.
- **Cloud facultatif** : clé Anthropic ou API compatible OpenAI dans Réglages (stockée chiffrée).

## Google Drive (facultatif)
Dans Google Cloud Console : créer un projet, activer **Google Drive API**, créer un identifiant
OAuth de type **Android** (package `ai.forge.app` + empreinte SHA-1 de votre clé de signature),
configurer l'écran de consentement. Forge demande la portée `drive.file` par défaut.
