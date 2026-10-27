# Format de fichier VOXCRYPT `.vxc` (version 1)

Ce document est la spécification de référence du conteneur chiffré `.vxc`.
Une implémentation indépendante, écrite uniquement à partir de ce document,
se trouve dans `tools/vxc_decrypt.py` et déchiffre le fichier de référence
`app/src/test/resources/vectors/v1_password_segment4096.vxc`.

## 1. Vue d'ensemble

```
+---------------------------+  en clair, authentifié (données associées)
| En-tête (25 à 1024 octets)|
+---------------------------+
| Segment chiffré 0         |  AES-256-GCM, tag 16 octets
| Segment chiffré 1         |
| ...                       |
| Segment chiffré n (final) |  drapeau "dernier segment" dans le nonce
+---------------------------+
```

Tous les entiers sont en **big endian**. Le fichier ne contient aucune donnée
audio lisible, ni le nom d'origine, ni le type MIME, ni la durée : ces
informations sont chiffrées dans le flux.

## 2. En-tête

| Décalage | Taille | Champ | Valeurs acceptées (v1) |
|---|---|---|---|
| 0 | 8 | Signature | ASCII `VOXCRYPT` |
| 8 | 1 | Version du format | `1` |
| 9 | 2 | Longueur totale de l'en-tête (signature incluse) | 25 à 1024, doit correspondre exactement |
| 11 | 1 | Suite cryptographique | `1` = AES-256-GCM segmenté |
| 12 | 1 | Méthode de protection de la clé | `1` = mot de passe (PBKDF2-HMAC-SHA256), `2` = clé de l'appareil (Android Keystore) |
| 13 | 1 | Indicateurs | `0` (réservé, toute autre valeur est refusée) |
| 14 | 4 | Taille de segment (octets en clair) | 4 096 à 1 048 576 (défaut 65 536) |
| 18 | 7 | Préfixe de nonce | aléatoire, propre au fichier |
| 25 | variable | Paramètres de protection | voir ci-dessous |

### 2.1 Protection par mot de passe (`1`)

| Décalage | Taille | Champ | Valeurs acceptées |
|---|---|---|---|
| 25 | 4 | Itérations PBKDF2 | 100 000 à 10 000 000 (défaut 600 000) |
| 29 | 1 | Longueur du sel `s` | 16 à 64 (l'application écrit 32) |
| 30 | `s` | Sel | aléatoire, propre au fichier |

Longueur totale de l'en-tête : `30 + s` (62 octets avec un sel de 32 octets).

### 2.2 Protection liée à l'appareil (`2`)

| Décalage | Taille | Champ | Valeurs acceptées |
|---|---|---|---|
| 25 | 2 | Longueur `w` de la clé enveloppée | 28 à 512 |
| 27 | `w` | Clé de contenu enveloppée | `IV (12) ‖ AES-256-GCM(clé de contenu 32 octets) ‖ tag (16)` |

La clé de contenu est enveloppée par une clé AES-256-GCM non exportable du
Keystore Android, avec comme données associées
`"VOXCRYPT-DEVICE-KEY-v1" ‖ préfixe de nonce`.

### 2.3 Règles de validation

Le lecteur refuse le fichier **avant toute dérivation de clé ou allocation
importante** si :

- la signature est absente (`NotVxcFile`) ;
- la version est inconnue (`UnsupportedVersion`) ;
- la longueur déclarée sort de [25, 1024] ou ne correspond pas au contenu ;
- un identifiant (suite, protection) est inconnu ou les indicateurs sont non nuls ;
- un paramètre sort des bornes ci-dessus ;
- des octets non prévus restent dans l'en-tête ;
- le fichier se termine avant la fin de l'en-tête (`Truncated`).

Ces bornes empêchent un fichier malveillant de provoquer une dérivation
interminable (itérations énormes) ou une allocation mémoire excessive
(segments ou champs géants).

## 3. Flux chiffré

### 3.1 Texte clair

Le texte clair chiffré est la concaténation :

```
u32 longueur_métadonnées (1 à 16 384)
métadonnées (voir 3.3)
octets audio
```

### 3.2 Segments

Le texte clair est découpé en segments de `taille_segment` octets ; le dernier
segment contient entre 0 et `taille_segment` octets. Chaque segment `i`
(à partir de 0) est chiffré indépendamment :

```
nonce_i = préfixe_nonce (7) ‖ i en u32 big endian (4) ‖ dernier (1 octet : 0x01 si dernier segment, sinon 0x00)
segment_chiffré_i = AES-256-GCM(clé, nonce_i, données_associées = en-tête complet, segment_clair_i)
                  = texte_chiffré ‖ tag 128 bits
```

Conséquences :

- **aucun nonce n'est réutilisé** avec une même clé : le compteur est unique
  par segment, et la clé est unique par fichier (sel aléatoire ou clé de
  contenu aléatoire) ;
- **réordonner, dupliquer ou supprimer** un segment change le nonce attendu :
  l'authentification échoue ;
- **tronquer** le fichier est détecté : le dernier segment lu n'a pas été
  chiffré avec le drapeau `dernier`, ou il est incomplet ;
- **ajouter des octets** à la fin est détecté de la même manière ;
- **modifier l'en-tête** (sel, itérations, préfixe, taille de segment...) est
  détecté, car l'en-tête complet est la donnée associée de chaque segment ;
- au plus 2^32 segments (256 Tio avec des segments de 64 Kio).

Cette construction suit le principe du "STREAM" de Hoang, Reyhanitabar,
Rogaway et Vizár (2015), également utilisé par Google Tink
(`AesGcmHkdfStreaming`).

### 3.3 Métadonnées chiffrées (version 1)

| Taille | Champ |
|---|---|
| 1 | Version des métadonnées (`1`) |
| 2 + n | Nom d'origine (u16 longueur, UTF-8, max 1024 octets) |
| 2 + n | Type MIME (u16 longueur, UTF-8, max 1024 octets) |
| 8 | Date de création (millisecondes Unix, i64) |
| 8 | Durée en millisecondes (i64, -1 si inconnue) |
| 8 | Taille de l'audio en octets (i64, -1 si inconnue) |

Si la taille de l'audio est connue, le lecteur vérifie qu'elle correspond au
nombre d'octets audio obtenus.

## 4. Clés

| Mode | Clé de contenu |
|---|---|
| Mot de passe | `PBKDF2-HMAC-SHA256(mot_de_passe, sel, itérations, 32 octets)` |
| Appareil | 32 octets aléatoires (SecureRandom), enveloppés par le Keystore |

## 5. Procédure de déchiffrement

1. Lire et valider l'en-tête (section 2.3).
2. Obtenir la clé (dérivation PBKDF2 ou désenveloppement Keystore).
3. Lire les segments un par un avec un octet d'anticipation pour savoir si le
   segment courant est le dernier.
4. Authentifier et déchiffrer chaque segment. Un échec sur le **premier**
   segment est signalé "mot de passe incorrect ou fichier altéré", sans
   distinguer les deux cas. Un échec plus loin signale une modification ou
   une troncature.
5. Écrire le résultat dans un fichier temporaire privé, et ne le présenter
   comme valide **qu'après authentification du segment final**. En cas
   d'échec, le fichier partiel est détruit.

## 6. Évolution du format

- Toute évolution incompatible incrémente l'octet de version. Un lecteur v1
  refuse proprement une version supérieure (`UnsupportedVersion`).
- Les identifiants de suite et de protection permettent d'ajouter de nouveaux
  algorithmes (par exemple Argon2id pour la dérivation, ou une suite
  XChaCha20-Poly1305) sans changer la structure générale.
- L'octet d'indicateurs est réservé et doit valoir 0 en v1.
- Le nombre d'itérations est stocké dans chaque fichier : augmenter la valeur
  par défaut ne rend pas les anciens fichiers illisibles.
- Le test `FormatCompatibilityTest` garantit que le fichier de référence v1
  reste lisible et que l'encodeur produit exactement les mêmes octets pour un
  même aléa. Toute future version de l'application doit continuer à lire les
  fichiers v1.
