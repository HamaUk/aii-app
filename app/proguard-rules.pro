# =================================================================================================
# Nexus release shrinker configuration
#
# Target: AGP 9 / R8 full mode, minSdk 26. Every rule below is here because something actually needs
# it; there is deliberately no package-wide `-keep` for the app, for Compose or for the engine, since
# those are statically linked and shrink correctly on their own. Keep additions targeted.
# =================================================================================================

# --- Diagnostics --------------------------------------------------------------------------------
# Release stack traces keep line numbers (so a user-reported crash is actionable) while dropping the
# original file names, which are noise and a small information leak.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, Signature, InnerClasses, EnclosingMethod

# --- Optional dependencies that are referenced but never reached --------------------------------
# pdfbox-android's JPXFilter type-references the optional Gemalto JPEG-2000 decoder. Nexus extracts
# *text* from PDFs, so the filter is never entered; the class is simply absent from the APK.
-dontwarn com.gemalto.jp2.**
# Tink (pulled in by androidx.security:security-crypto) is annotated with errorprone annotations that
# upstream declares compileOnly - they exist at build time only.
-dontwarn com.google.errorprone.annotations.**

# --- kotlinx.serialization ----------------------------------------------------------------------
# The compiler plugin emits one `$$serializer` class per @Serializable type, plus companion lookups
# for the json format and its built-in serializers. Renaming or stripping either breaks every
# persisted message, provider config and settings blob at runtime.
-keep,includedescriptorclasses class com.nexus.aichat.**$$serializer { *; }
-keepclassmembers class com.nexus.aichat.** { *** Companion; }
-keepclasseswithmembers class com.nexus.aichat.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Persisted enum names -----------------------------------------------------------------------
# Preferences and the database store enums by *name* (`ThemePreset.id`, `AgentMode.valueOf(...)`).
# Obfuscating the constants would not crash - it would silently reset the user's theme, agent
# policy and reasoning settings on every update, which is worse.
-keepclassmembers enum com.nexus.aichat.** { *; }

# --- Ktor engine discovery ----------------------------------------------------------------------
# Ktor resolves a default engine through ServiceLoader on `META-INF/services`; the OkHttp container
# is also constructed directly by NetworkModule, but keeping the name costs nothing and protects the
# ServiceLoader path from being renamed out of existence.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keepnames class * implements io.ktor.client.engine.HttpClientEngineContainer
# Ktor's optional logging/security integrations look for these at runtime; all are optional.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Annotation processors ----------------------------------------------------------------------
# Room, Hilt and the Compose compiler all ship their own consumer rules, which R8 merges
# automatically: no additional keeps are required for DAOs, entities, @Inject constructors or
# composables. Listed here so nobody adds a blanket keep later "just in case".
