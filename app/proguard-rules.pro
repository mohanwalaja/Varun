# ---------------------------------------------------------------------------
# Varun Studio — R8 / ProGuard rules
#
# Minification is currently DISABLED for both build types (see
# app/build.gradle.kts). These rules are still required, because
# `proguardFiles(...)` names this file and AGP fails the build if it is
# missing. They are written so that switching `isMinifyEnabled = true` later
# produces a working APK rather than one that crashes in JNI.
# ---------------------------------------------------------------------------

# --- JNI ------------------------------------------------------------------
# llama_bridge.cpp registers natives by symbol name, e.g.
#   Java_dev_varun_studio_ai_LlamaNative_nativeLoadModel
# The JVM resolves those symbols from the *declared* class and method names.
# If R8 renames or removes them, System.loadLibrary succeeds but the first
# native call throws UnsatisfiedLinkError. Keep the whole holder class.
-keepclasseswithmembernames,includedescriptorclasses class dev.varun.studio.ai.LlamaNative {
    native <methods>;
}
-keep class dev.varun.studio.ai.LlamaNative { *; }

# Types crossing the JNI boundary. The C++ side resolves these by name:
#   env->GetMethodID(cls, "onToken",   "(Ljava/lang/String;II)Z")
#   env->GetMethodID(pcls, "onProgress", "(F)V")
# Both are top-level fun interfaces in ai/LlamaNative.kt (NOT nested in
# LlamaEngine), so the internal names are dev/varun/studio/ai/TokenCallback
# and dev/varun/studio/ai/LoadProgressCallback. Renaming either makes the
# method lookup return null and generation die with an obscure native error.
-keep interface dev.varun.studio.ai.TokenCallback {
    boolean onToken(java.lang.String, int, int);
}
-keep interface dev.varun.studio.ai.LoadProgressCallback {
    void onProgress(float);
}
-keepclassmembers class * implements dev.varun.studio.ai.TokenCallback {
    public boolean onToken(java.lang.String, int, int);
}
-keepclassmembers class * implements dev.varun.studio.ai.LoadProgressCallback {
    public void onProgress(float);
}

# NativeLoader inspects Build.SUPPORTED_ABIS and picks a .so by name.
-keep class dev.varun.studio.ai.NativeLoader { *; }

# --- Enum ordinals used as wire values ------------------------------------
# GenerationResultCode ordinals are produced by the C++ layer (kOk, kCancelled,
# ...). Renaming is harmless but REORDERING or stripping entries would silently
# mis-map every result code.
-keepclassmembers enum dev.varun.studio.ai.GenerationResultCode {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keep enum dev.varun.studio.ai.GenerationResultCode { *; }
-keepclassmembers enum dev.varun.studio.agent.PromptLibrary$TaskKind {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# --- Data classes persisted / parsed by name ------------------------------
# SQLite column mapping and org.json parsing are done by hand, but the
# constructors are invoked reflectively by Kotlin's copy()/default-arg support.
-keepclassmembers class dev.varun.studio.data.db.** {
    <init>(...);
    *** copy(...);
    *** component*();
}
-keep class dev.varun.studio.data.db.** { *; }
-keep class dev.varun.studio.github.** { *; }

# --- Jetpack Compose ------------------------------------------------------
# Compose runtime uses reflection for @Composable lambdas and stability
# inference; the standard androidx consumer rules cover most of it. These two
# are the ones that still bite when minifying a Compose-only app.
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**

# --- OkHttp ---------------------------------------------------------------
# OkHttp ships consumer rules, but it optionally reflects on Conscrypt /
# Android 10+ TLS providers that are absent here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Android platform APIs guarded by SDK_INT -----------------------------
# LocaleManager (API 33+) and OnBackInvokedCallback are referenced from code
# paths guarded at runtime; R8 must not treat them as missing classes.
-dontwarn android.app.LocaleManager
-dontwarn android.os.LocaleList
-dontwarn android.window.OnBackInvokedCallback
-dontwarn android.window.OnBackInvokedDispatcher

# --- Kotlin metadata / coroutines ----------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# --- BuildConfig is read at runtime for version strings -------------------
-keep class dev.varun.studio.BuildConfig { *; }

# Keep the Application/Activity/Service entry points named in the manifest.
# (AGP adds manifest-declared components automatically; these are explicit so a
# manual `proguardFiles` run outside AGP still works.)
-keep class dev.varun.studio.VarunApplication { *; }
-keep class dev.varun.studio.MainActivity { *; }
-keep class dev.varun.studio.ai.InferenceForegroundService { *; }
