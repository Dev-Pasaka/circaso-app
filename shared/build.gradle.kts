import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
}

// The iOS Google Maps key (live location's map) comes from local.properties `maps.ios.key`, like the Circle
// credentials. It is restricted in the Cloud console to the bundle id, so it is safe inside the app; it is generated
// into iosMain only. Without it the iOS map falls back to the server's map picture.
val mapsIosKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("maps.ios.key").orEmpty()
val generateMapsIosKey = tasks.register("generateMapsIosKey") {
    val out = layout.buildDirectory.dir("generated/mapsKey/kotlin")
    val key = mapsIosKey
    inputs.property("key", key)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/circaso/feature/messaging/presentation/map/MapsIosKey.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.circaso.feature.messaging.presentation.map\n\n/** Generated from local.properties `maps.ios.key`; empty when it isn't set. */\ninternal const val MapsIosKey = \"$key\"\n")
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.circaso.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.stream.webrtc) // calls (designs H1–H5): Google WebRTC, media through Cloudflare Realtime
            implementation(libs.play.code.scanner) // QR scanning (design E4/E5): Google's ML Kit scanner, no camera permission
            // Live location's map (decision 2026-10-30): Google Maps, styled to Circaso; the key is locked to the app.
            implementation(libs.maps.compose)
            // Sends what was queued offline once there's a connection, even with the app closed (Pasaka, 2026-10-30).
            implementation(libs.work.runtime)
            // Video messages (Pasaka, 2026-10-30): playback, the player view, and shrinking a video to send it.
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.transformer)
            implementation(libs.play.services.maps)
            // Moments' in-app camera (approved 2026-11-03): CameraX, latest stable at build time (1.6.2).
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.video)
            implementation(libs.camerax.view)
            // Asha's animated mascot (asha-next.md part B): the Rive runtime behind AshaAvatar.
            implementation(libs.rive.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServicesAuth)
            implementation(libs.googleid)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            // Signal protocol (X3DH, double ratchet, sender keys). Android needs both artifacts; the JVM one also
            // gives the host tests real crypto.
            implementation(libs.libsignal.android)
            implementation(libs.libsignal.client)
        }
        iosMain { kotlin.srcDir(generateMapsIosKey) }
        commonMain.dependencies {
            implementation(libs.qrose) // drawing QR codes (design E4)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation3.ui)
            implementation(libs.androidx.lifecycle.viewmodelNavigation3)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.coil.compose)
            implementation(libs.markdown.renderer)
            implementation(libs.coil.network.ktor)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.datastore.preferences)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
