# Feuille de route

Chaque jalon se termine par des tests réels ; rien n'est déclaré terminé sans vérification.

## J1 — Application installable (priorité immédiate)
- Compiler `app/` avec le SDK Android, corriger les écarts d'API, tests instrumentés de base.
- Premier lancement sur S25 : chat écrit + voix avec un serveur Ollama sur le réseau local.
- Téléchargement guidé d'un modèle local (Gemma .task) et mesure de latence réelle.

## J2 — Voix « JARVIS »
- VAD Silero + Sherpa-ONNX/whisper.cpp en streaming ; ponctuation pour un meilleur endpointing.
- Annulation d'écho (AcousticEchoCanceler / pipeline audio propre) pour le duplex sans écouteurs.
- Voix neuronale locale (Piper/Sherpa) : sélection et affinage d'une voix originale sous licence.
- Reprise de conversation, historique, transcription facultative persistée.

## J3 — Outils et recherche
- Appels d'outils par le modèle (function calling) passant par `PermissionGate`.
- `web.search` (fournisseur au choix de l'utilisateur, ex. SearXNG auto-hébergé ou API à clé) et
  `web.fetch` (extraction lisible, respect de robots.txt), archivage des citations.
- Dossiers de recherche avec `Corroboration` et revérification des sujets récents.

## J4 — Bibliothèque comics
- Sélection de fichiers Drive (Picker/SAF), indexation progressive en tâche de fond (WorkManager),
  cache borné, reprise.
- Extraction PDF (PdfRenderer) et CBZ (ZIP sécurisé : limites de taille, anti zip-slip).
- OCR ML Kit (latin) des bulles + détection des cases ; lien panel ↔ affirmation.
- Recherche « retrouve les passages où… » avec résultats formatés par `ClaimLedger.formatResult`.

## J5 — Primestone Media
- Chaîne éditoriale complète (`EditorialPipeline`) avec livrables par rôle.
- Feuille de montage, suggestions visuelles, exports Markdown/PDF/DOCX.

## J6 — Forge Academy et corrigés
- Import de cours (PDF/Drive), indexation documentaire, corrigés fidèles au cours.

## J7 — Forge Brain en production
- Interface de retours dans l'app, leçons validées injectées dans les instructions,
  évaluations automatiques à chaque changement d'instructions, embedder sémantique local.
