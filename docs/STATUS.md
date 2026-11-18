# État réel du projet

Ce qui fonctionne, ce qui est écrit mais non vérifié sur appareil, ce qui n'existe pas. Mise à jour : 18/11/2026 (v0.3.0).

## Vérifié : compilé et testé
- `./gradlew test` → **96 tests JVM, 0 échec** (14 modules).
- `./gradlew :app:testDebugUnitTest` → **7 tests Android (Robolectric), 0 échec**.
- `./gradlew :app:assembleDebug` → APK construit ; **Android Lint : 0 erreur**.

| Domaine | Démontré par les tests |
|---|---|
| Démarrage | L'application et l'écran principal Compose démarrent sans plantage (Robolectric) |
| Scénario B — mémoire | Souvenirs persistés dans Room, retrouvés par une nouvelle instance, suppression durable |
| Scénario C — recherche | Recherche → pages consultées (robots.txt, pas de contournement, hôtes privés refusés) → sources encadrées et classées → bibliographie ajoutée par le code |
| Scénario D — bibliothèque | CBZ sécurisé → OCR → page retrouvée avec titre, numéro, année, fichier → piste « non vérifiée » ≤ P2, P1 seulement après consultation |
| Scénario E/F — éditorial | Vérification, statut « Vérifié », storyboard, SRT, export Motion Studio conditionnel |
| Scénario G — actions | « Mets un réveil à 7h15 » → portail → Intent ACTION_SET_ALARM réellement émis (7 h 15) → journal → blocage après arrêt d'urgence |
| Scénario H — hors-ligne | Routage vers les moteurs locaux, recherche désactivée et signalée |
| Sécurité | Aucune donnée privée vers un cloud non autorisé ; injections signalées ; secrets jamais transmis |
| Drive | Pagination, analyse des noms de comics, reprise de téléchargement, cache par empreinte (serveur simulé) |

## Pas encore vérifié
- **Aucun test sur un Galaxy S25 réel** : latence, RAM, température, batterie, qualité vocale restent à mesurer.
- Pas d'émulateur disponible dans l'environnement de développement (pas de virtualisation KVM).
- Moteur MediaPipe, OCR ML Kit, reconnaissance et synthèse vocales : compilés, non exécutés réellement.
- Connexion Google Drive : nécessite un identifiant OAuth Android créé par vous (voir SETUP).

## Limites connues
- Interruption vocale sans écouteurs : risque d'écho (bouton « Interrompre » disponible).
- `HashingEmbedder` est lexical, pas sémantique.
- Analyse d'intention par règles : peut se tromper sur des formulations inattendues.
- OCR des lettrages de comics stylisés : fiabilité moyenne, d'où le statut « piste à vérifier ».
- Les contrôles éditoriaux sont des heuristiques : ils complètent le Fact Checker.
- Coûts : estimations à partir d'un tarif saisi ; jetons estimés.
- Indexation de la bibliothèque au premier plan (pas encore de WorkManager) ; PDF non indexés.

## Pas encore développé
Écrans Projets/Recherche/Éditorial/Academy, interface de connexion Drive, indexation PDF, détection
des cases, WorkManager, export DOCX, voix neuronale propre, llama.cpp, embedder sémantique.
