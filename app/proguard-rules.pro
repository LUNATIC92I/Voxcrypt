# VOXCRYPT relies only on platform crypto (javax.crypto / Android Keystore),
# no reflection based serialization. Default rules are sufficient.

# Strip verbose and debug logging calls from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# View models are created by reflection through their (Application) constructor
# (see ui/util/ViewModels.kt). AndroidX already keeps it; stated explicitly here.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
