/*
 * Didit's identity verification SDK, in its own module so nothing else in the app knows Didit exists: the app installs
 * `DiditKyc` into the shared `IdentityVerifierBridge`, and the shared code only ever asks for one thing, "run the open
 * check for this session token" (guide "Identity verification (KYC)", R0). The token comes from Circaso's backend;
 * no Didit key is in the app.
 *
 * `me.didit:didit-sdk-core` is the SDK without NFC chip reading: the ID photo, the selfie and the face match with
 * auto-capture, which is all the workflow asks. It comes from Didit's own Maven repository (settings.gradle.kts),
 * needs no credentials, and asks for Android 6 (API 23), below the app's own minimum. Its colours come from Didit's
 * white-label settings (set to Circaso's ink palette at Didit), not from resources here.
 */
plugins {
    id("com.android.library") // the version comes with AGP, already on the classpath from androidApp
}

android {
    namespace = "com.circaso.diditkyc"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("me.didit:didit-sdk-core:${libs.versions.didit.sdk.get()}")
}
