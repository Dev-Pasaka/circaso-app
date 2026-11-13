import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.crashlytics) // crash reports; release builds upload their R8 mapping
}

// Java 17, not 11: AGP 9 only desugars Java records in debug builds when the language level is above 14, and
// libsignal-client uses records. Release builds never had the problem; this keeps debug and release the same.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":circlewallet")) // the Circle SDK, behind the wallet signer seam

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.play.app.update) // Play In-App Updates: checks for a newer Circaso at launch
    implementation(libs.play.app.update.ktx)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
    // libsignal uses java.time and Java records; on minSdk 24 both need core library desugaring.
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

val mapsAndroidKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("maps.android.key").orEmpty()

val shareHost: String = (findProperty("share.host") as String?)
    ?: Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.getProperty("share.host")
    ?: "links.circaso.com" // the share host since 2026-11-07 (sandbox.circaso.com links still open the app)

android {
    namespace = "com.circaso"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // Needed to strip libsignal's native libraries (about 120 MB per ABI unstripped, about 25 MB stripped).
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.circaso.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // The version: raise versionCode by one for every bundle uploaded to Play by hand (Play refuses a code it has
        // seen). CI overrides both with -Pcircaso.versionCode / -Pcircaso.versionName (its codes start above 1000).
        versionCode = providers.gradleProperty("circaso.versionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("circaso.versionName").getOrElse("1.0.0")
        // Google Maps for Android (live location's map). Per developer, like the Circle credentials:
        // `maps.android.key=…` in local.properties. Restrict it in the Cloud console to this package and signing
        // certificate, and to the Maps SDK for Android. Without it the map falls back to Circaso's drawn tile.
        manifestPlaceholders["mapsApiKey"] = mapsAndroidKey
        // Share links (decision 2026-11-06): the host whose /s/{code} links open the app (App Links). The sandbox by
        // default; `share.host=…` in local.properties (or -Pshare.host) for the production short domain.
        manifestPlaceholders["shareHost"] = shareHost
        // Phones only (Pasaka, 2026-11-08): releases go to people's Android phones, not emulators or Chromebooks, so
        // the bundle carries ARM code alone (x86 doubled its native libraries for no reader).
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // libsignal-client ships desktop natives for the JVM tests; they have no place in the APK.
            excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so")
        }
        jniLibs {
            // Test-only hooks Signal ships in the AAR; about 150 MB per ABI and never called by the app.
            excludes += "**/libsignal_jni_testing.so"
        }
    }
    // The Play upload key, only when CI (or a developer) provides it through the environment; without it a release
    // build is unsigned, as before. The key never lives in the repo.
    val uploadStore = System.getenv("CIRCASO_UPLOAD_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (uploadStore != null) {
            create("upload") {
                storeFile = uploadStore
                storePassword = System.getenv("CIRCASO_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CIRCASO_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("CIRCASO_UPLOAD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // No native debug symbols in the bundle: they are only for crash reports inside the native libraries we
            // ship from others (ONNX Runtime, WebRTC, libsignal) and doubled the upload.
            ndk { debugSymbolLevel = "NONE" }
            if (uploadStore != null) signingConfig = signingConfigs.getByName("upload")
            // To try a release build on a phone without the upload key: -Pcircaso.releaseDebugSigned=true.
            else if (providers.gradleProperty("circaso.releaseDebugSigned").orNull == "true") signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
}