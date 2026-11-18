# Permissions et confidentialité

| Permission Android | Pourquoi | Quand |
|---|---|---|
| `RECORD_AUDIO` | Conversation vocale | Demandée au premier appui sur le micro ; jamais activée en secret |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Mode mains libres | Notification permanente avec bouton Arrêter |
| `POST_NOTIFICATIONS` | Notification de session, rappels | Android 13+ : demandée à l'usage |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Serveur domestique, cloud facultatif, Drive ; détection hors-ligne | — |
| `SET_ALARM` | Préparer un réveil (l'app Horloge affiche la validation) | Niveau 2, réversible |

**Non demandé :** contacts, SMS, localisation, accès à tous les fichiers, service d'accessibilité.

## Données
| Donnée | Stockage | Sort de l'appareil ? |
|---|---|---|
| Mémoire, conversations | Room (SQLite), exclue des sauvegardes | Vers un moteur cloud **seulement** si l'utilisateur l'autorise (classe « personnelles ») |
| Comics, Drive, cours | Cache local de l'app | Jamais vers le cloud sans autorisation explicite (classe « documents privés ») |
| Clés API | Chiffrées AES-256-GCM, clé dans l'Android Keystore | Uniquement vers le fournisseur concerné |
| Journal des actions | Mémoire de l'app | Non |

## Contrôles utilisateur
Plafond d'autonomie (0–4) · classes de données et tâches autorisées par fournisseur cloud ·
limite de requêtes/jour · arrêt d'urgence (voix + actions) · export et suppression de la mémoire.

## Contenus externes
Pages Web, documents et fichiers sont des **données**, jamais des instructions : encadrés par
`UntrustedContent`, analysés, et toute tentative d'instruction est signalée à l'utilisateur.
