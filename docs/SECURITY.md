# Sécurité mobile et limites connues

## Mesures appliquées

| Exigence | Mise en œuvre | Où le vérifier |
|---|---|---|
| Aucune transmission réseau | Aucune permission `INTERNET` dans le manifeste | `AndroidManifest.xml`, `aapt dump permissions` |
| Aucun compte, aucune télémétrie | Aucune bibliothèque d'analyse ou de publicité | `app/build.gradle.kts` |
| Permissions minimales | `RECORD_AUDIO` uniquement, demandée au moment de l'enregistrement | `RecordScreen.kt` |
| Pas de sauvegarde cloud implicite | `allowBackup=false`, `fullBackupContent=false`, règles d'extraction excluant tout | `res/xml/data_extraction_rules.xml` |
| Fichiers temporaires privés | `files/` et `cache/` de l'application, jamais le stockage partagé | `VaultRepository.kt` |
| Nettoyage | Fichiers partiels détruits en cas d'échec ou d'annulation, nettoyage complet au démarrage | `VaultRepository.cleanupTemporary` |
| Pas de copie en clair après chiffrement | L'enregistrement en clair est détruit après chiffrement **et** relecture authentifiée | `VaultRepository.encrypt` |
| Captures d'écran et aperçu | `FLAG_SECURE` activé par défaut, `setRecentsScreenshotEnabled(false)` sur Android 13+ | `MainActivity.kt`, écran Sécurité |
| Pas de secret dans les journaux | Aucun appel `Log` ; suppression des `Log.v/d/i` par R8 en release | `proguard-rules.pro` |
| Mot de passe non affiché | Champ masqué par défaut, clavier "mot de passe" sans apprentissage ni correction | `Fields.kt` |
| Mot de passe non persistant | Jamais stocké ; pas dans l'état sauvegardé de l'activité | `EncryptScreen.kt`, `DecryptScreen.kt` |
| Partage | Seul le fichier chiffré est transmis, jamais le mot de passe | `Share.kt` |
| Export d'audio déchiffré | Avertissement explicite avant l'export | `DecryptScreen.kt` |
| Fichiers entrants | Signature, version et paramètres vérifiés avant de demander le mot de passe | `VxcHeader.read` |
| Accès aux fichiers | Opérations refusées hors des dossiers de l'application (pas de traversée de chemin) | `VaultRepository.requireInside` |

## Limites connues

1. **Effacement "sécurisé"** : avant suppression, les fichiers sont écrasés par
   des zéros. Sur mémoire flash (répartition de l'usure, journalisation du
   système de fichiers), cela ne garantit pas la disparition physique des
   anciennes données. Pour les fichiers `.vxc`, ce n'est pas un problème : ils
   sont chiffrés. Pour un enregistrement **non chiffré**, la meilleure
   protection est de le chiffrer rapidement et d'utiliser le chiffrement
   intégral de l'appareil Android (actif par défaut sur les appareils récents).
2. **Enregistrements en attente** : entre l'arrêt de l'enregistrement et le
   chiffrement, l'audio est en clair dans l'espace privé de l'application.
   L'écran d'accueil et la bibliothèque l'indiquent clairement. Un
   chiffrement à la volée pendant la capture n'est pas implémenté, car
   MediaRecorder écrit le conteneur MP4 dans un fichier.
3. **Fichier d'origine importé** : VOXCRYPT ne modifie ni ne supprime le
   fichier audio choisi dans le stockage de l'utilisateur. L'interface
   rappelle de le supprimer si nécessaire.
4. **Mémoire** : les `String` saisies dans Compose et les copies internes de
   la JVM ne peuvent pas être effacées de manière garantie.
5. **PBKDF2** : moins résistant que Argon2id face au matériel spécialisé. La
   robustesse repose donc fortement sur la qualité du mot de passe.
6. **Taille observable** : la taille d'un `.vxc` révèle approximativement la
   durée de l'enregistrement. Le nom du fichier `.vxc` (choisi par
   l'utilisateur) est visible ; le nom d'origine est chiffré.
7. **FLAG_SECURE** : empêche les captures logicielles standard, pas une photo
   de l'écran ni un système compromis (root, logiciel espion).
8. **Mode appareil** : la clé Keystore n'est pas sauvegardée ; un fichier
   protégé ainsi devient définitivement illisible si l'application est
   désinstallée, si les données sont effacées ou si l'appareil est
   réinitialisé. Le mode n'exige pas d'authentification biométrique.
9. **Mot de passe oublié** : aucune récupération possible, par conception.
10. **Formats audio importés** : la lecture après déchiffrement dépend des
    décodeurs de l'appareil (AAC/M4A, MP3, OGG, WAV, FLAC sont courants).
    Un fichier non reconnu comme audio par Android est refusé à l'import.
11. **Interface en français uniquement** ; les textes sont dans le code
    Compose, pas encore dans des ressources traduisibles.
12. **Tests sur appareil non exécutés ici** : voir `docs/TEST_REPORT.md`.
13. **Versions des dépendances** : versions stables éprouvées (AGP 8.9.1,
    Kotlin 2.1.0, Compose BOM 2024.12.01). Lint signale des versions plus
    récentes ; une mise à jour est possible mais doit être suivie d'une
    nouvelle exécution complète des tests.

## Signaler une vulnérabilité

Ouvrez un ticket privé sur le dépôt (security advisory GitHub) plutôt qu'un
ticket public.
