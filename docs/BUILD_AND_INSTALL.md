# Compiler, signer et installer VOXCRYPT

## Prérequis

- JDK 17 ou plus récent (testé avec OpenJDK 21).
- Android SDK : plateforme `android-35`, build-tools `35.0.0`, platform-tools.
- Accès à Internet pour le premier téléchargement des dépendances Gradle.

Versions utilisées : Gradle 8.14.3 (wrapper inclus), Android Gradle Plugin
8.9.1, Kotlin 2.1.0, Compose BOM 2024.12.01, `minSdk` 26 (Android 8.0),
`targetSdk` 35 (Android 15).

## Dans Android Studio

1. **File > Open** et choisir le dossier racine `Voxcrypt`.
2. Laisser Android Studio synchroniser Gradle (il crée `local.properties`
   avec le chemin du SDK).
3. Choisir la configuration **app** et un appareil, puis **Run**.
4. Tests : clic droit sur `app/src/test` > **Run Tests** (JVM), ou sur
   `app/src/androidTest` avec un appareil connecté (tests instrumentés).
5. APK : **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

## En ligne de commande

```bash
# Si le SDK n'est pas détecté automatiquement :
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties

./gradlew assembleDebug            # APK de débogage, installable
./gradlew assembleRelease          # APK release optimisé (R8)
./gradlew test                     # tests JVM (moteur, stockage, interface Robolectric)
./gradlew lintDebug                # analyse statique Android
./gradlew connectedDebugAndroidTest   # tests instrumentés (appareil requis)
```

Emplacements produits :

| Fichier | Chemin |
|---|---|
| APK debug | `app/build/outputs/apk/debug/app-debug.apk` |
| APK release non signé | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| APK release signé (si `keystore.properties`) | `app/build/outputs/apk/release/app-release.apk` |
| APK de tests instrumentés | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Captures d'écran des tests | `app/build/outputs/screenshots/` |

Des copies des APK générés pour la version 1.0.0 sont dans `dist/`, avec
leurs empreintes SHA-256.

## Signature de production

Le dépôt ne contient **aucune clé de signature**. L'APK release est produit
non signé tant que le propriétaire du projet ne fournit pas sa propre clé.

1. Générer une clé (une seule fois, à conserver hors du dépôt et sauvegardée
   de façon sûre : la perdre empêche toute mise à jour de l'application) :

   ```bash
   keytool -genkeypair -v -keystore ~/cles/voxcrypt-release.jks \
     -alias voxcrypt -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Créer `keystore.properties` à la racine (ignoré par Git) :

   ```properties
   storeFile=/home/vous/cles/voxcrypt-release.jks
   storePassword=...
   keyAlias=voxcrypt
   keyPassword=...
   ```

3. `./gradlew assembleRelease` produit alors `app-release.apk` signé.

Alternative sans fichier de propriétés, pour signer l'APK non signé :

```bash
BT=$ANDROID_HOME/build-tools/35.0.0
$BT/zipalign -p -f 4 app-release-unsigned.apk app-release-aligned.apk
$BT/apksigner sign --ks ~/cles/voxcrypt-release.jks --out app-release.apk app-release-aligned.apk
$BT/apksigner verify --verbose app-release.apk
```

Pour une publication sur Google Play, préférer un Android App Bundle
(`./gradlew bundleRelease`) et Play App Signing.

## Installer l'APK sur un smartphone

### Avec un câble (ADB)

1. Sur le téléphone : **Paramètres > À propos du téléphone**, toucher 7 fois
   **Numéro de build** pour activer les options pour les développeurs, puis
   activer **Débogage USB**.
2. Brancher le téléphone et accepter l'autorisation de débogage.
3. `adb install -r dist/VOXCRYPT-1.0.0-debug.apk`

### Sans ordinateur

1. Copier l'APK sur le téléphone (câble, carte SD, gestionnaire de fichiers).
2. L'ouvrir depuis le gestionnaire de fichiers.
3. Autoriser l'installation d'applications inconnues pour cette source
   lorsque Android le demande, puis **Installer**.

L'APK debug s'installe sous l'identifiant `com.voxcrypt.app.debug` et peut
coexister avec une version release (`com.voxcrypt.app`).

## Transférer un fichier chiffré vers un autre appareil

1. Sur l'appareil A : **Mes fichiers sécurisés > Partager** (ou **Exporter**).
   Seul le `.vxc` est transmis.
2. Communiquer le mot de passe par un autre moyen (oralement, par exemple).
3. Sur l'appareil B (VOXCRYPT installé) : ouvrir le `.vxc` reçu avec
   VOXCRYPT, ou **Déchiffrer un fichier > Choisir un fichier .vxc**.
4. Saisir le mot de passe : l'audio n'est restitué qu'après vérification
   complète de l'intégrité.

Les fichiers protégés par **clé de l'appareil** ne peuvent pas être ouverts
sur un autre appareil : utiliser le mode mot de passe pour tout transfert.

Hors de l'application, un fichier protégé par mot de passe peut aussi être
déchiffré sur ordinateur avec le décodeur de référence :
`python3 tools/vxc_decrypt.py fichier.vxc sortie.m4a` (nécessite
`pip install cryptography`).
