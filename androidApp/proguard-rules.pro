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
