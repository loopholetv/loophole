# Loophole R8 rules (release and benchmark builds).
#
# Most libraries ship their own consumer rules (Room, Media3, OkHttp, Okio, Moshi, kotlinx-coroutines,
# Compose, Coil); the rules below are the app-specific ones plus belt-and-braces keeps for code that
# is reached by reflection or by name.

# Readable stack traces in bug reports (no crash reporting is included; users copy them from logcat).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Room ---
# The generated AppDatabase_Impl is instantiated reflectively by Room.databaseBuilder().
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- Media3 (ExoPlayer, HLS, OkHttp data source) ---
# DefaultRenderersFactory / DefaultExtractorsFactory look up optional extension classes by name; they
# are not bundled, so silence the notes. The OkHttp data source factory is used directly.
-dontnote androidx.media3.**
-keep class androidx.media3.datasource.okhttp.OkHttpDataSource$Factory { *; }

# --- OkHttp / Okio ---
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Failure messages fall back to the exception class name; keep those readable.
-keepnames class * extends java.io.IOException

# --- Moshi ---
# Only the streaming JsonReader is used (no reflective or codegen adapters), so nothing to keep but
# the exception names shown in error handling.
-keepnames class com.squareup.moshi.JsonDataException
-keepnames class com.squareup.moshi.JsonEncodingException

# --- kotlinx-coroutines ---
# Service-loaded main dispatcher and exception handlers (also covered by the library's own rules).
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# --- Compose ---
# Compose ships complete consumer rules; nothing app-specific is needed.
