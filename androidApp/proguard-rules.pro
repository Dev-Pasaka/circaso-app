# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Circle's wallet SDK refers to its optional Facebook and Firebase social sign-in, which Circaso doesn't ship (it signs in
# with its own Google and Apple flow). Those paths are never called; R8 must not fail on the missing classes.
-dontwarn com.facebook.**
-dontwarn com.google.firebase.auth.ktx.**
-dontwarn com.google.firebase.ktx.Firebase

# Readable crash reports in Play: keep line numbers (the mapping file CI uploads restores names).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Google sign-in (Credential Manager): it finds its Play Services provider by class name at runtime, which R8 can't see,
# so without these the release build has no provider and sign-in fails ("no credentials available"). Google's own rule.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# libsignal's native code calls back into Java by name: the stores' loadSession, storeSession, getIdentityKeyPair, …
# Its own rules don't cover the stores (InMemorySignalProtocolStore and friends) or classes extending them, so R8
# renamed loadSession and the first sign-in crashed: "JNI error Method not found: loadSession". Keep libsignal whole.
-keep class org.signal.libsignal.** { *; }
-keep class * extends org.signal.libsignal.protocol.groups.state.InMemorySenderKeyStore { *; }
-keep class * implements org.signal.libsignal.protocol.state.** { *; }
-keep class * implements org.signal.libsignal.protocol.groups.state.** { *; }

# Any class with native methods keeps their names, and the classes in their signatures (JNI looks both up by name).
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# ONNX Runtime (Asha's voice detector): its native code builds Java objects (TensorInfo, OnnxTensor, …) by name through
# JNI. R8 renamed them and voice mode crashed in convertToTensorInfo (GetMethodID). Keep the Java API whole.
-keep class ai.onnxruntime.** { *; }

# Circle's wallet SDK (the PIN and signing screens). Its own rules cover only its JSON models; at runtime it also uses
# Retrofit interfaces, a WebView JavaScript bridge and generic type information, and the release build failed to open the
# PIN (payments said "Not sent. Nothing was taken" after the server had prepared them). Keep the SDK, Circaso's wrapper
# around it, and what Retrofit/Moshi/WebView read by reflection.
-keep class circle.programmablewallet.** { *; }
-keep interface circle.programmablewallet.** { *; }
-keep class com.circaso.circlewallet.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
