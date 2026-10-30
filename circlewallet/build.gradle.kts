import java.util.Properties

/*
 * The Circle user-controlled wallets SDK, in its own module so nothing else in the app knows Circle exists: the app
 * installs `CircleWalletSigner` into the shared `WalletSignerBridge`, and the shared code only ever runs a challenge
 * (set a PIN, add a network, sign a payment). Circle publishes the SDK on GitHub Packages, which needs a GitHub token
 * with `read:packages`:
 *
 *   local.properties
 *     pwsdk.maven.username=<github username>
 *     pwsdk.maven.password=<token>
 *
 * Without those two lines the module builds the stub in `src/stub` (signing answers "not available in this build"),
 * so everyone can still build and run the rest of the app. With them, `src/circle` and the real SDK are compiled in.
 */
plugins {
    id("com.android.library") // the version comes with AGP, already on the classpath from androidApp
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val circleUser = localProperties.getProperty("pwsdk.maven.username")
val circleToken = localProperties.getProperty("pwsdk.maven.password")
val hasCircle = !circleUser.isNullOrBlank() && !circleToken.isNullOrBlank()

// The repository itself is declared in settings.gradle.kts, where the app resolves its dependencies.

android {
    namespace = "com.circaso.circlewallet"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            kotlin.srcDir(if (hasCircle) "src/circle/kotlin" else "src/stub/kotlin")
            if (hasCircle) {
                manifest.srcFile("src/circle/AndroidManifest.xml")
                res.srcDir("src/circle/res")
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    // Circle's social login (Facebook) is not used: Circaso signs people in itself and gives the SDK a user token.
    // The SDK's code never references Facebook, so it is left out entirely; its manifest entries are removed in
    // src/circle/AndroidManifest.xml. Google's play-services-auth stays because Circaso's own Google sign-in uses it.
    if (hasCircle) {
        implementation("circle.programmablewallet:sdk:${libs.versions.circle.wallet.sdk.get()}") {
            exclude(group = "com.facebook.android")
        }
        // The SDK uses LiveData (MediatorLiveData) but got it only through Facebook's SDK; with Facebook left out it
        // must come from here, or the PIN confirmation screen crashes (Redmi, 2026-10-30).
        implementation("androidx.lifecycle:lifecycle-livedata:${libs.versions.androidx.lifecycle.get()}")
    }
}

logger.lifecycle("circlewallet: Circle SDK " + if (hasCircle) "enabled" else "not configured (stub); add pwsdk.maven.* to local.properties")
