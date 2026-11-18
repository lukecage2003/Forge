# Format d'échange Forge → Primestone Motion Studio (manifeste v1)

L'API de Primestone Motion Studio n'étant pas encore spécifiée, Forge produit un manifeste JSON
versionné (`schemaVersion: 1`). Un adaptateur le convertira au format réel dès qu'il sera connu.

## Conditions d'export (sinon : refus motivé)
- Script au statut **Vérifié** (`EditorialVerifier`).
- Fichier audio de narration fourni.
- Chaque image porte une autorisation documentée (bibliothèque personnelle / licence / création originale).

## Champs
| Champ | Contenu |
|---|---|
| `title`, `scriptStatus`, `script` | Script validé complet (segments, visuels, affirmations, sources) |
| `audioFile` | Chemin du fichier de narration |
| `storyboard` | Séquences → plans minutés (texte dit, visuel, animation, note si pas de visuel pertinent) |
| `images` | Images autorisées (id, description, emplacement, base d'autorisation) |
| `editingInstructions` | Format, durée, synchronisation, zone sûre, exactitude des pages affichées |
| `targetFormat`, `resolution`, `durationSeconds` | 9:16 / 1080×1920 ou 16:9 / 1920×1080 |
| `subtitlesSrt` | Sous-titres SRT (2 lignes × 42 caractères max) |
| `identity` | Couleurs (noir #0A0A0A, or #D4A017), polices, logo |
| `qualityPriorities` | Priorités de qualité ordonnées |

Les minutages sont estimés à partir du débit oral du format ; ils doivent être recalés sur l'audio réel.
