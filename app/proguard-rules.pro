# ============================================================
#  Focus Life — R8 / ProGuard rules
# ============================================================

# ---- General ----
-optimizationpasses 2
-dontusemixedcaseclassnames
-verbose

# Keep line numbers for readable crash reports, but obfuscate names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute FocusLife

# ---- AndroidX / Material ----
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# ---- Room ----
# Entities are accessed reflectively by the generated code.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# ---- WorkManager ----
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keep class androidx.work.impl.** { *; }
-dontwarn androidx.work.impl.**

# ---- Lifecycle ----
-keep class * implements androidx.lifecycle.LifecycleObserver { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ---- ViewBinding generated classes ----
-keep class com.focuslife.app.databinding.** { *; }

# ---- Accessibility service (instantiated by the framework by name) ----
-keep class com.focuslife.app.blocking.BlockAccessibilityService { *; }

# ---- Receivers referenced from the manifest ----
-keep class com.focuslife.app.notify.** { *; }

# ---- Enums used in mode / stats modelling ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Parcelable ----
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
