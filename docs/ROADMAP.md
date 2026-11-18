# Plan de développement priorisé

Chaque lot : objectif défini → implémentation → tests → documentation → intégration sans régression.
Rien n'est déclaré terminé sans vérification réelle (et, à partir de J1, sur le Galaxy S25).

## Priorités (§17 étape 3)

| Catégorie | Contenu | État |
|---|---|---|
| **Fondations** | Modules, contrats, sécurité (autorisations, sortie des données, anti-injection), mémoire, routage, évaluation | ✅ fait, testé (JVM) |
| **Indispensables** | App compilée et installée ; conversation écrite et vocale ; mémoire persistante ; Action Center ; hors-ligne | 🟡 code écrit, compilation à faire (J1) |
| **Importantes** | Recherche Web sourcée ; Drive + indexation ; lecture PDF/CBZ + OCR ; éditorial de bout en bout ; Academy dans l'app | 🟡 logique prête pour éditorial/Academy ; connecteurs à faire |
| **Avancées** | Détection des cases ; appels d'outils par le modèle ; voix neuronale propre ; llama.cpp ; embedder sémantique | ⬜ |
| **Expérimentales** | Reconnaissance de personnages ; duplex sans écouteurs ; AppFunctions ; mot d'activation | ⬜ (faisabilité incertaine, voir AUDIT §5) |

## Lots livrés
- **L0** Cœur initial (orchestrateur, mémoire, voix logique, Claim Ledger, éditorial, correction).
- **L1** Audit + découpage en 13 modules sans régression.
- **L2** Sécurité : sortie des données, anti-injection, gouvernance fournisseurs, actions + arrêt d'urgence, ressources, hors-ligne.
- **L3** Éditorial §9.4–9.7 : style oral, vérification et statut « Vérifié », production, SRT, Motion Studio.
- **L4** Forge Academy (§10). **L5** Évaluation continue à erreurs critiques (§16).
- **L6** Intégration app : présence visuelle, Action Center, autorisations cloud, test de connexion.

## Première tranche fonctionnelle proposée (J1) — « Forge parle, se souvient, et agit prudemment »
Objectif mesurable sur le S25, couvrant les scénarios A, B, G et H :
1. Installer l'app ; serveur Ollama domestique **ou** Gemma local.
2. Conversation vocale : interruption « attends », changement de sujet, reprise (scénario A).
3. « Souviens-toi que… », fermeture, réouverture le lendemain, rappel (scénario B).
4. « Mets un réveil à 7 h » : passage par le portail, journal, résultat réel affiché (scénario G).
5. Mode avion : conversation locale maintenue, recherche signalée indisponible (scénario H).
6. Mesures : premier jeton, latence voix-à-voix, RAM, température après 10 min, batterie/heure.

Prérequis bloquant : **SDK Android** (licences à accepter par le propriétaire) et un S25 connecté.

## Lots suivants
- **J1** Compilation, tests instrumentés, tranche fonctionnelle ci-dessus, rapport de mesures.
- **J2** Voix : VAD Silero, STT streaming (Sherpa-ONNX/whisper.cpp), AEC, voix Piper originale sous licence.
- **J3** Outils : appels d'outils par le modèle via `ActionEngine` ; llama.cpp + grammaires ; recherche Web
  (fournisseur choisi par l'utilisateur) et extraction de pages encadrées par `UntrustedContent`.
- **J4** Bibliothèque comics : Picker/SAF Drive, indexation WorkManager, PDF/CBZ sécurisé (anti zip-slip,
  limites de taille), OCR ML Kit, détection des cases, recherche « retrouve les passages où… » (scénario D).
- **J5** Primestone : chaîne éditoriale complète dans l'app, audio de narration, export Motion Studio (scénarios E, F).
- **J6** Academy dans l'app : import de cours, fiches, révisions planifiées, coaching oral.
- **J7** Forge Brain en production : retours dans l'UI, leçons injectées, bancs exécutés à chaque version.
