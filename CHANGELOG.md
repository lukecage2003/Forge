# Historique des versions

## 0.8.2 — 18/11/2026
- **Bulle : correction de l'affichage en haut de l'écran.**
  - En veille, plus de capsule noire permanente sur la barre d'état : la bulle n'apparaît qu'au
    mot « Forge » et se retire 2,5 s après la réponse (pastille permanente en option).
  - La fenêtre a la taille exacte de la bulle : la barre d'état, le volet des notifications et le
    haut des applications restent utilisables autour.
  - Pastille compacte centrée sur la caméra et à la hauteur de la barre d'état du téléphone.

## 0.8.1 — 18/11/2026
- **Recherches beaucoup plus rapides** :
  - Wikipédia et les wikis Marvel/DC renvoient le texte des articles dans la même requête que la
    recherche (un seul aller-retour, au lieu de télécharger robots.txt puis des pages HTML de 1 à 2 Mo) ;
  - la phrase parlée est réduite à ses mots-clés (« Forge, tu peux me chercher qui a créé Black
    Panther ? » → « créé Black Panther ») : moins de recherches vides ;
  - pages des autres sites téléchargées en parallèle dans un budget de 4 s : une page lente est
    abandonnée au lieu de bloquer la réponse ;
  - connexions réutilisées (client HTTP partagé, délais courts) ; résultats gardés 20 min ;
  - 3 sources plus courtes : moins de texte à relire pour le modèle embarqué.
- **Réponses comics et recherche diffusées immédiatement** sur le téléphone : la relecture par le
  modèle (2 à 3 générations complètes avant d'afficher quoi que ce soit) est remplacée par un
  contrôle instantané des citations (une source [n] inexistante est signalée).
- Indicateur « Recherche sur le Web… » pendant la recherche.

## 0.8.0 — 18/11/2026
- **Bulle Forge** façon île dynamique : capsule noire autour de la caméra, par-dessus les
  applications ; s'élargit quand vous parlez (onde dorée qui suit votre voix, transcription en
  direct), affiche « Je réfléchis… » puis la réponse ; toucher = ouvrir Forge, glisser = fermer.
- **« Forge » pour l'appeler** : détecteur hors-ligne (Vosk, 42 Mo, Apache 2.0) limité au mot
  d'appel avec mots leurres anti-faux réveils, sensibilité réglable ; signal sonore discret,
  question, réponse, puis retour automatique en veille après quelques secondes de silence.
- Réglages : autorisation d'affichage, installation du mot d'appel, veille, exclusion batterie.

## 0.7.0 — 18/11/2026
- **Écoute des longues phrases** : silences naturels, suite de phrase recollée au lieu de remplacer.
- **Réponses plus rapides** : conversation gardée en mémoire par le moteur local (plus de relecture
  complète), pas de double passe ni de recherche Web à l'oral.
- **Historique des conversations** (recherche, reprise, suppression) — migration de base sans perte.
- **Vision** : images jointes vues par Gemma 4 (repli OCR honnête).
- **Assistant du téléphone** : appui long sur le bouton latéral → Forge en écoute.
- **Studio** (scripts enregistrés, PDF, Word, SRT, animation) et **Études** (cours PDF, fiches,
  révision espacée, interrogation).
- **Google Drive**, **indexation en arrière-plan**, **comics PDF**.
- **Briefing du matin** (notification quotidienne).
- **Voix neuronale hors-ligne** « Estelle » (Kyutai Pocket TTS, CC-BY 4.0).
- Correctif : enregistrer les réglages n'écrase plus un modèle ou une voix installés depuis l'écran.

## 0.6.0 — 18/11/2026
- **Voix plus naturelle** : phrases enchaînées sans blanc (file continue), moteur Google prioritaire,
  voix neuronales en ligne avec repli local hors-ligne, choix de la voix avec écoute d'essai,
  texte dit débarrassé des émojis, symboles et étiquettes de sources.
- **Création de fichiers** : PDF mis en page (titres, gras, listes, tableaux, pagination), Word (.docx),
  Markdown, texte ; « fais-moi un PDF sur… » crée le fichier automatiquement (Téléchargements/Forge).
- **Sans limite de conversation** : le contexte est ajusté automatiquement à la mémoire du modèle,
  contexte local porté à 8 192 jetons quand le GPU l'accepte (repli 4 096), réponses plus longues,
  bouton « Continuer » si une réponse est coupée.

## 0.5.0 — 18/11/2026
- **Recherche Internet sans réglage** : Wikipédia + wikis Marvel/DC (API publiques), automatique pour
  les questions comics, éditoriales et de recherche ; SearXNG/Brave restent possibles.
- **Studio de scripts** TikTok / Shorts / Reels / YouTube : accroches, plans minutés, texte à l'écran,
  titres, miniature, contrôle de rétention automatique et révision si l'accroche ou l'intro échoue.
- **Analyse du marché YouTube** (API officielle, clé gratuite) : vidéos qui surperforment, formats,
  titres gagnants, injectés dans les scripts ; « analyse le marché sur X ».
- **Mode débat comics** : chaque argument cite une preuve ; audit « non sourcé / wiki / vérifié ».
  Pages de comics fournies en photo + référence = preuves vérifiées.
- **Amélioration continue** : 👍/👎 + consigne → leçon appliquée aux réponses suivantes.
- **Voix réparée** : plus d'écoute de sa propre voix, plus de doublons, erreurs bénignes silencieuses,
  bascule automatique du moteur, mot d'appel « Forge », vocabulaire comics corrigé et appris.
- **Animations originales** exportées en MP4 depuis un script.
- **Personnalité** : stratège calme et franc, verdicts « bonne / mauvaise / à retravailler » motivés.
- **Fluidité** : version optimisée (non débogable), affichage du texte regroupé, verre figé dans les
  listes, erreurs lisibles.

## 0.4.0 — 18/11/2026
- **Cerveau plus puissant** : Gemma 4 E4B (recommandé) et E2B (rapide), Google, Apache 2.0, sans compte,
  exécutés par LiteRT-LM sur le GPU du S25, repli CPU automatique, préchargement au démarrage,
  annulation réelle de la génération.
- **Plus rapide** : sur le téléphone, une seule passe diffusée immédiatement (contrôle qualité conservé
  pour comics, éditorial et recherche).
- **Nouvelle interface « verre »** : fond aurore animé, panneaux de verre dépoli (flou GPU, sans dépendance),
  orbe lumineuse, grands titres, bulles dorées, barre de saisie et onglets flottants, mise en forme des
  réponses, suggestions, retour haptique ; écrans Mémoire, Comics, Actions et Réglages redessinés.
- Chaîne de compilation : Gradle 9.1, Kotlin 2.4, AGP 8.13, compileSdk 36.

## 0.3.1 — 18/11/2026
- Premier lancement : carte « Forge a besoin de son cerveau » avec installation en un bouton du modèle
  recommandé (Qwen 2.5 1,5B, Apache 2.0, 1,6 Go, sans compte) : reprise après coupure, vérification
  SHA-256, alerte hors Wi-Fi.
- Gabarits de conversation par famille de modèles (ChatML pour Qwen, Gemma pour Gemma).

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
