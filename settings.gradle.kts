rootProject.name = "Circaso"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // ONNX Runtime reduced to Silero VAD's operators (third_party/onnxruntime-minimal: built from source, README
        // there), the only place this module comes from.
        exclusiveContent {
            forRepository { maven { name = "OnnxRuntimeMinimal"; url = uri("third_party/onnxruntime-minimal/maven") } }
            filter { includeModule("com.circaso.thirdparty", "onnxruntime-android-silero") }
        }
        // libsignal moved off Maven Central after 0.86; Signal publishes newer builds here.
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
            content { includeGroup("org.signal") }
        }
        // Circle's user-controlled wallets SDK is on GitHub Packages only. The credentials live in local.properties
        // (never committed); without them :circlewallet builds its stub and this repository is not added.
        val local = java.util.Properties().apply { file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        val circleUser = local.getProperty("pwsdk.maven.username")
        val circleToken = local.getProperty("pwsdk.maven.password")
        if (!circleUser.isNullOrBlank() && !circleToken.isNullOrBlank()) {
            maven {
                name = "CircleProgrammableWallet"
                url = uri("https://maven.pkg.github.com/circlefin/w3s-android-sdk")
                credentials { username = circleUser; password = circleToken }
                content { includeGroup("circle.programmablewallet") }
            }
        }
    }
}

include(":androidApp")
include(":shared")
include(":circlewallet")