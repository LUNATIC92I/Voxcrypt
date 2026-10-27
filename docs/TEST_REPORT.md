# Rapport de tests VOXCRYPT 1.0.0

Résultats **réellement observés** le 27 octobre 2026 dans l'environnement de
développement (conteneur Linux x86_64, Intel Xeon 2,1 GHz, 4 cœurs, OpenJDK 21,
Android SDK 35, Gradle 8.14.3).

Commande exécutée :

```bash
./gradlew clean test lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

Résultat : `BUILD SUCCESSFUL`, code de sortie 0.

## 1. Synthèse

| Tâche Gradle | Tests | Réussis | Échecs | Ignorés |
|---|---|---|---|---|
| `testDebugUnitTest` | 66 | 66 | 0 | 0 |
| `testReleaseUnitTest` | 66 | 66 | 0 | 0 |
| `streamingMemoryDebugTest` (tas 160 Mio) | 1 | 1 | 0 | 0 |
| `streamingMemoryReleaseTest` (tas 160 Mio) | 1 | 1 | 0 | 0 |
| **Total exécuté** | **134** | **134** | **0** | **0** |
| Tests instrumentés (`androidTest`, 6 tests) | compilés | **non exécutés** | | |

Android Lint (`lintDebug`) : **0 erreur**, 38 avertissements (versions plus
récentes de dépendances disponibles, suggestions de style). Aucun
avertissement de sécurité.

## 2. Détail par classe (variante debug)

| Classe | Tests | Durée |
|---|---|---|
| `VxcEngineTest` | 21 | 8,3 s |
| `VaultRepositoryTest` | 19 | 1,1 s |
| `VxcHeaderTest` | 10 | 0,02 s |
| `VxcMetadataAndPolicyTest` | 7 | 0,1 s |
| `AppUiTest` (Robolectric, interface réelle) | 6 | 20,3 s |
| `FormatCompatibilityTest` | 3 | 0,4 s |
| `LargeFileStreamingTest` | 1 | 5,1 s |

## 3. Couverture des exigences

### Tests cryptographiques

| Exigence | Test(s) | Résultat |
|---|---|---|
| Fichier chiffré différent de l'original | `ciphertext_differsFromOriginal_andLeaksNoPlaintext` | Réussi |
| Audio original non récupérable dans le fichier (motifs, fenêtres de 32 octets, nom d'origine) | idem | Réussi |
| Mot de passe incorrect : échec, aucun audio écrit | `wrongPassword_fails`, `wrongPassword_writesNoAudio`, `wrongPassword_leavesNoDecryptedFile` | Réussi |
| Modification détectée (inversion d'un bit tous les 97 octets, dernier tag) | `everySingleBitFlip_inCiphertextIsDetected` | Réussi |
| Troncature détectée (début, milieu, fin, frontières exactes de segment) | `truncation_isDetected_atEveryCutPoint`, `openingATruncatedVxcFile_reportsTruncation` | Réussi |
| Données ajoutées en fin détectées | `appendedData_isDetected` | Réussi |
| Réordonnancement, suppression, duplication de segments | `segmentReorderingAndRemoval_areDetected` | Réussi |
| Greffe de segments d'un autre fichier | `segmentsFromAnotherFile_cannotBeSpliced` | Réussi |
| Métadonnées authentifiées modifiées (chaque octet du préfixe, du sel, des itérations ; taille de segment) | `headerModifications_areDetected`, `segmentSizeModification_isRejectedOrDetected` | Réussi |
| Même audio et même mot de passe : fichiers différents (sel et préfixe distincts) | `sameAudioSamePassword_producesDifferentFiles` | Réussi |
| Paramètres invalides refusés (itérations, sel, segment, algorithme, indicateurs, longueurs) | `rejectsInvalidParameters_beforeAnyDerivation`, `builderRefusesInvalidParameters` | Réussi |
| Version et signature | `rejectsUnsupportedVersion`, `rejectsNonVxcData`, `openingANonVxcFile_isRejectedBeforeAskingForAPassword` | Réussi |
| Pas de réutilisation de nonce par erreur de programmation | `contentKey_cannotEncryptTwice`, `decryptionKey_cannotBeUsedToEncrypt` | Réussi |
| PBKDF2 conforme à une référence indépendante (Python `hashlib`) | `pbkdf2_matchesIndependentKnownAnswer` | Réussi |
| Mode appareil : aller-retour ; autre clé refusée ; mot de passe refusé | `deviceMode_roundTrip_andOtherDeviceFails`, `deviceProtection_flow` | Réussi (clé simulée sur JVM) |
| Mémoire bornée sur gros fichiers | `encryptAndDecrypt256MiB_withBoundedMemory` | Réussi : 256 Mio chiffrés puis déchiffrés avec un tas limité à 160 Mio, pic mesuré 32 à 34 Mio, empreintes SHA-256 identiques |
| Nettoyage des temporaires sur erreur | `failureDuringEncryption_cleansPartialFile_andKeepsSource`, `cancellationDuringEncryption_cleansPartialFile`, `cancellationDuringDecryption_leavesNothing`, `tamperedFile_leavesNoDecryptedFile` | Réussi |
| Compatibilité du format et non régression | `referenceFile_v1_stillDecrypts`, `encoder_v1_isByteStable`, `referenceFile_headerFields` | Réussi |

### Tests fonctionnels

| Exigence | Test(s) | Résultat |
|---|---|---|
| Chiffrement d'un enregistrement via l'interface, destruction de la copie en clair | `fullFlow_encryptPendingRecording_thenDecryptWithWrongAndRightPassword` (Robolectric) | Réussi |
| Déchiffrement avec le bon mot de passe via l'interface, nom d'origine restauré | idem | Réussi |
| Échec avec mauvais mot de passe via l'interface, message unique, rien restitué | idem | Réussi |
| Copie déchiffrée effacée en quittant l'écran | idem | Réussi |
| Importation d'un audio (flux de taille inconnue) | `importFromUnknownSizeStream_andExport_roundTrip` | Réussi |
| Exportation d'un fichier chiffré, sans mot de passe ni audio dedans | idem | Réussi |
| Transfert vers un "autre appareil" (dépôt distinct) et déchiffrement | idem | Réussi |
| Import d'un fichier non `.vxc` refusé | `importRejectsNonVxcFiles` | Réussi |
| Renommage (collisions, traversée de chemin neutralisée) | `rename_keepsIndex_andAvoidsCollisions`, `pendingRename_andDelete` | Réussi |
| Suppression | `delete_removesFileAndIndex`, `secureDelete_overwritesBeforeDeleting` | Réussi |
| Fichiers vides | `emptyAudio_isRefused`, `emptyAudio_isSupportedByTheEngine` | Réussi |
| Espace de stockage insuffisant | `insufficientStorage_isReportedBeforeWriting` | Réussi |
| Fichier endommagé dans la bibliothèque | `damagedHeader_isListedAsInvalid` | Réussi |
| Opérations hors de l'espace privé refusées | `operationsOutsideTheVault_areRefused` | Réussi |
| Écran d'accueil, statut, 5 entrées | `home_showsBrandStatusAndAllEntries` | Réussi |
| Permission micro : demande puis commandes affichées | `record_requestsPermission_thenShowsControls` | Réussi |
| Écran Sécurité, `FLAG_SECURE` actif par défaut puis désactivable | `security_screenShowsParameters_andTogglesScreenshotBlocking` | Réussi |

Vérification croisée hors JVM : le décodeur Python indépendant
`tools/vxc_decrypt.py` (bibliothèque `cryptography` 50.0.1) déchiffre le
fichier de référence et retrouve le nom `vector.m4a` et les 10 000 octets
attendus.

## 4. Ce qui n'a PAS pu être exécuté

L'environnement ne dispose ni de téléphone ni d'accélération matérielle
(`/dev/kvm` absent) : aucun émulateur Android n'a pu être lancé. En
conséquence :

- les 6 tests instrumentés (`app/src/androidTest`) sont **compilés**
  (`app-debug-androidTest.apk` produit) mais **non exécutés** :
  - `DeviceCryptoInstrumentedTest` : Keystore Android réel (aller-retour,
    isolation entre clés, données associées), mode appareil complet, mode
    mot de passe à 600 000 itérations, mesure PBKDF2 ;
  - `RecordingInstrumentedTest` : enregistrement micro réel avec pause et
    reprise, chiffrement, déchiffrement, **lecture de l'audio déchiffré avec
    MediaPlayer**, arrêt immédiat sans conservation ;
- l'enregistrement réel, la lecture audio réelle et le Keystore réel n'ont
  donc été validés que par compilation et par tests JVM avec simulations
  (Robolectric, clé simulée). Sous Robolectric, MediaPlayer ne décode pas
  l'audio : l'écran affiche alors "Lecture impossible", comportement attendu
  pour des octets de test qui ne sont pas un vrai fichier M4A ;
- aucune mesure de performance sur téléphone. Mesures indicatives sur la
  JVM du conteneur : PBKDF2-HMAC-SHA256 310 000 itérations en 160 ms,
  600 000 en 206 ms, 1 000 000 en 304 ms. Sur un téléphone d'entrée de
  gamme, prévoir plusieurs fois plus. Utiliser le bouton "Mesurer sur cet
  appareil" de l'écran Sécurité.

Pour exécuter les tests instrumentés sur un appareil branché :

```bash
./gradlew connectedDebugAndroidTest
adb logcat -s VoxcryptTest   # temps PBKDF2 mesurés sur l'appareil
```

## 5. Captures d'écran

Générées par `AppUiTest` (rendu natif Robolectric, 400 x 860 dp) et copiées
dans `docs/screenshots/`. Les animations sont figées dans ce mode de rendu.
