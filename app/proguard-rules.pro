# VOXCRYPT relies only on platform crypto (javax.crypto / Android Keystore),
# no reflection based serialization. Default rules are sufficient.

# Strip verbose and debug logging calls from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
