# ======================================================================
# GhostScreen Production ProGuard / R8 Rules
# ======================================================================

# Retain line numbers and source attributes for de-obfuscation via mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip verbose and debug logging in release builds (Goldilocks Rule)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Room Database reflection rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# WorkManager background worker rules
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Maintain data classes for JSON parsing in GitHub Releases & Updater
-keepclassmembers class com.etaegord.ghostscreen.updater.** {
    <fields>;
}

# Keep diagnostic event data models
-keepclassmembers class com.etaegord.ghostscreen.util.DiagnosticLogger$* {
    <fields>;
}