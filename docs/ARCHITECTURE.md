# Architecture de VOXCRYPT

Application Android native, Kotlin, Jetpack Compose, architecture MVVM.
Un seul module Gradle `app`, découpé en paquets à responsabilité unique.

```
com.voxcrypt.app
├── crypto/        Moteur cryptographique pur JVM (aucune dépendance Android)
│   ├── VxcFormat          constantes et bornes du format v1
│   ├── VxcHeader          encodage / lecture / validation de l'en-tête
│   ├── VxcMetadata        métadonnées chiffrées (nom, type, durée, taille)
│   ├── VxcEngine          AES-256-GCM segmenté, ContentKey à usage unique
│   ├── KeyDerivation      PBKDF2-HMAC-SHA256 + mesure de performance
│   ├── DeviceKeyWrapper   interface d'enveloppement de clé (Keystore)
│   ├── PasswordPolicy     robustesse minimale des mots de passe
│   └── VxcException       erreurs typées, sans donnée sensible
├── data/
│   └── VaultRepository    stockage privé, fichiers temporaires, chiffrement
│                          vérifié, déchiffrement sûr, import, export,
│                          renommage, suppression (pur JVM, testé sans appareil)
├── security/
│   ├── KeystoreDeviceKeyWrapper   clé AES-256-GCM non exportable (StrongBox si possible)
│   └── SecuritySettings           préférences non sensibles (itérations, captures)
├── audio/
│   ├── VoiceRecorder      MediaRecorder AAC/M4A, pause, reprise, niveau sonore
│   ├── AudioPlayer        MediaPlayer pour fichiers privés
│   └── AudioInfo          durée, nom, taille, détection d'audio corrompu
├── ui/
│   ├── theme/             palette (noir, bleu électrique, turquoise, argent), typographie
│   ├── components/        HUD : panneaux, boutons, logo animé, visualiseur, icônes
│   ├── screens/           écrans + ViewModels (Home, Record, Encrypt, Decrypt, Library, Security)
│   ├── util/              formatage, messages d'erreur, SAF, partage, fabrique de ViewModels
│   └── VoxcryptNavHost    navigation Compose
├── VoxcryptApp            Application + conteneur de dépendances (AppContainer)
└── MainActivity           activité unique, FLAG_SECURE, intents entrants .vxc
```

## Flux de données

```
UI (Compose) ──événements──▶ ViewModel ──Dispatchers.Default/IO──▶ VaultRepository ──▶ VxcEngine
     ▲                          │                                        │
     └──── StateFlow (état) ◀───┘                       fichiers privés (files/, cache/)
```

- Les opérations longues (dérivation, chiffrement, vérification) tournent hors
  du thread principal. La progression remonte via un rappel qui vérifie aussi
  l'annulation (`ensureActive`) à chaque segment.
- Les ViewModels survivent aux rotations ; l'enregistreur vit au niveau de
  l'application pour ne pas s'arrêter lors d'un changement d'écran.
- `AppContainer.vaultVersion` est incrémenté à chaque modification de la
  bibliothèque pour rafraîchir les écrans concernés.

## Stockage

| Dossier | Contenu | Durée de vie |
|---|---|---|
| `files/vault/` | fichiers `.vxc` + `.index` (dates, durées : non sensible) | permanent |
| `files/pending/` | enregistrements **non chiffrés** en attente | jusqu'au chiffrement ou à la suppression |
| `files/*/.part-*` | écritures en cours | renommées après succès, sinon détruites |
| `cache/plain/` | audio déchiffré temporaire | effacé en quittant l'écran et à chaque démarrage |
| `cache/outgoing/` | copie **chiffrée** pour la feuille de partage | effacée au démarrage suivant |

Toutes ces données sont dans l'espace privé de l'application, exclues des
sauvegardes Android (`allowBackup=false`, règles d'extraction vides).
Import et export passent par le Storage Access Framework (aucune permission
de stockage requise).

## Cycle de vie et interruptions

- **Arrière-plan pendant un enregistrement** : pause automatique (Android
  coupe le micro des applications en arrière-plan), message affiché.
- **Rotation** : rien n'est interrompu (`isChangingConfigurations`).
- **Mort du processus** : les fichiers `.part` (enregistrement interrompu,
  chiffrement inachevé) et les copies déchiffrées sont supprimés au
  démarrage suivant. Le nettoyage ne touche que les fichiers antérieurs au
  démarrage, pour ne jamais effacer une opération lancée entre-temps.
- **Retour pendant une opération** : annule l'opération, le fichier partiel
  est détruit.

## Choix techniques

- **Pas de framework d'injection** : un conteneur manuel suffit à cette taille.
- **Pas de base de données** : la liste des fichiers est le contenu du dossier ;
  l'index ne stocke que des informations non sensibles.
- **Pas de permission Internet** : l'application est incapable d'émettre sur
  le réseau, ce qui se vérifie dans le manifeste et l'APK.
- **minSdk 26 (Android 8.0)** : `PBKDF2WithHmacSHA256`, `MediaRecorder.pause()`
  (API 24), clés AES du Keystore (API 23) sont disponibles. StrongBox est
  utilisé à partir de l'API 28 quand le matériel existe.
