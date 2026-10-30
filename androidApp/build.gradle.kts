import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
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

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
    // libsignal uses java.time and Java records; on minSdk 24 both need core library desugaring.
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

val mapsAndroidKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("maps.android.key").orEmpty()

android {
    namespace = "com.circaso"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // Needed to strip libsignal's native libraries (about 120 MB per ABI unstripped, about 25 MB stripped).
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.circaso.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // Google Maps for Android (live location's map). Per developer, like the Circle credentials:
        // `maps.android.key=…` in local.properties. Restrict it in the Cloud console to this package and signing
        // certificate, and to the Maps SDK for Android. Without it the map falls back to Circaso's drawn tile.
        manifestPlaceholders["mapsApiKey"] = mapsAndroidKey
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
    buildTypes {
        release {
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