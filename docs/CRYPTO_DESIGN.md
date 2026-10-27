# Conception cryptographique de VOXCRYPT

## 1. Objectifs de sécurité

| Objectif | Moyen |
|---|---|
| Confidentialité de l'audio et de ses métadonnées | AES-256-GCM, métadonnées dans le flux chiffré |
| Intégrité et authenticité de tout le fichier | Tag GCM par segment, en-tête en données associées, drapeau de fin |
| Résistance à la force brute sur le mot de passe | PBKDF2-HMAC-SHA256 avec sel aléatoire et nombre d'itérations configurable |
| Portabilité (mode mot de passe) | Aucune dépendance à une clé de l'appareil |
| Protection locale sans mot de passe (mode appareil) | Clé de contenu aléatoire enveloppée par Android Keystore |
| Fichiers volumineux | Chiffrement en flux, mémoire bornée à deux segments |

Hors périmètre : un appareil compromis (logiciel espion, root malveillant)
pendant l'enregistrement ou l'écoute, la contrainte physique, l'analyse de la
taille des fichiers (la taille chiffrée révèle approximativement la durée).

## 2. Primitives

Uniquement des primitives standard fournies par la plateforme (`javax.crypto`,
Conscrypt/BoringSSL sur Android) :

- **AES-256-GCM** (`AES/GCM/NoPadding`), tag de 128 bits, nonce de 96 bits ;
- **PBKDF2WithHmacSHA256**, disponible sur Android depuis l'API 26 ;
- **SecureRandom** du système pour sels, préfixes de nonce et clés aléatoires ;
- **Android Keystore** (AES-256-GCM non exportable, StrongBox si disponible)
  pour le mode lié à l'appareil.

Aucun algorithme maison, pas de mode ECB, pas de HMAC redondant (GCM
authentifie déjà), aucune clé ni mot de passe codé en dur.

## 3. Pourquoi un chiffrement segmenté

AES-GCM appliqué en une seule fois sur un gros fichier oblige soit à tout
charger en mémoire, soit à restituer du texte clair non encore authentifié.
VOXCRYPT découpe donc le flux en segments de 64 Kio, chacun authentifié, selon
la construction "STREAM" (Hoang, Reyhanitabar, Rogaway, Vizár, 2015) :

```
nonce_i = préfixe_aléatoire(56 bits) ‖ compteur_i(32 bits) ‖ dernier(8 bits)
```

Points traités explicitement (exigés par le cahier des charges) :

- **Unicité des nonces** : la clé est unique par fichier (sel de 256 bits
  ou clé aléatoire) et le compteur est unique par segment. De plus, l'objet
  `ContentKey` refuse de chiffrer deux fois (test
  `contentKey_cannotEncryptTwice`), ce qui empêche une réutilisation par
  erreur de programmation.
- **Ordre des segments** : le compteur fait partie du nonce. Échange,
  suppression ou duplication détectés (test
  `segmentReorderingAndRemoval_areDetected`).
- **Troncature** : le dernier segment porte le drapeau `1`. Couper le fichier,
  même exactement à une frontière de segment, est détecté (test
  `truncation_isDetected_atEveryCutPoint`).
- **Mélange de fichiers** : chaque fichier a sa propre clé et son en-tête en
  données associées ; greffer des segments d'un autre fichier échoue (test
  `segmentsFromAnotherFile_cannotBeSpliced`).
- **Métadonnées critiques** : l'en-tête complet (version, algorithme, sel,
  itérations, taille de segment, préfixe, clé enveloppée) est la donnée
  associée de chaque segment.

## 4. Dérivation de clé par mot de passe

- PBKDF2-HMAC-SHA256, sortie de 256 bits, sel aléatoire de 256 bits par
  fichier.
- Valeur par défaut : **600 000 itérations** (recommandation OWASP 2023 pour
  PBKDF2-HMAC-SHA256). Choix proposés : 310 000, 600 000, 1 000 000,
  2 000 000. Bornes acceptées en lecture : 100 000 à 10 000 000.
- L'écran Sécurité contient un bouton **"Mesurer sur cet appareil"** qui
  chronomètre une dérivation réelle, pour calibrer la valeur sur les
  appareils cibles. Le test instrumenté `pbkdf2_benchmark` fait de même.
  Aucune mesure sur téléphone n'a pu être réalisée dans l'environnement de
  développement (pas d'appareil ni d'émulateur) : voir le rapport de tests.
- La valeur est écrite dans chaque fichier ; la modifier ne casse pas la
  lecture des anciens fichiers.
- Politique de mot de passe : 10 caractères minimum et robustesse estimée
  au moins "Correct" (longueur et variété). Les phrases de passe longues
  sont recommandées.

PBKDF2 n'est pas résistant aux attaques sur GPU comme Argon2id ou scrypt. Il a
été retenu parce qu'il est fourni par la plateforme sans dépendance tierce.
Le format prévoit un identifiant de méthode de dérivation pour ajouter
Argon2id plus tard (voir FORMAT_VXC.md, section 6).

## 5. Mode lié à l'appareil

- Une clé de contenu aléatoire de 256 bits est générée pour chaque fichier.
- Elle est chiffrée (enveloppée) par une clé AES-256-GCM générée dans
  Android Keystore (`voxcrypt.device.key.v1`), non exportable, avec IV
  aléatoire imposé par le Keystore (`setRandomizedEncryptionRequired`).
  StrongBox est utilisé quand l'appareil en dispose.
- Conséquence assumée et affichée à l'utilisateur : un tel fichier **ne
  peut pas être déchiffré sur un autre appareil**, ni après désinstallation
  de l'application ou réinitialisation du téléphone.
- Les deux modes ne sont jamais combinés dans un même fichier : un fichier
  est soit portable (mot de passe), soit local (appareil). L'interface
  recommande le mot de passe pour tout fichier à conserver ou transférer.

## 6. Messages d'erreur et oracle

- Un échec d'authentification sur le premier segment donne toujours le même
  message, "Mot de passe incorrect ou fichier altéré", sans indiquer lequel
  des deux.
- Les erreurs de structure (signature, version, paramètres, troncature de
  l'en-tête) sont signalées avant toute demande de mot de passe : elles ne
  dépendent d'aucun secret.
- Aucune donnée audio n'est restituée tant que tout le flux n'est pas
  authentifié : le déchiffrement écrit dans un fichier temporaire privé
  renommé seulement après succès, et détruit sinon.

## 7. Hygiène des secrets en mémoire

- Mots de passe convertis en `CharArray`, effacés après usage ;
  `PBEKeySpec.clearPassword()` est appelé.
- Clés de contenu effacées à la fermeture de `ContentKey`.
- Tampons de segments remis à zéro après usage.
- Limite connue : le champ de saisie Compose manipule des `String`
  immuables, et la JVM peut copier des clés en interne. L'effacement est donc
  "au mieux". Les champs de mot de passe ne sont jamais enregistrés dans
  l'état sauvegardé de l'activité.
- Aucun journal (Logcat) n'est produit par l'application ; R8 supprime en
  plus les appels `Log.v/d/i` en release.

## 8. Vérifications automatisées

Voir `docs/TEST_REPORT.md`. Les propriétés ci-dessus sont couvertes par
des tests unitaires exécutés sur la JVM, une valeur de référence PBKDF2
calculée indépendamment (Python `hashlib`), et un décodeur Python
indépendant (`tools/vxc_decrypt.py`) qui déchiffre le fichier de référence.
