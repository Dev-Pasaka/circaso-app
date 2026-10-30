// swift-tools-version:5.9
//
// libsignal's Swift code builds from source (the `LibSignalClient` package the app also links), but its Rust core is a
// prebuilt XCFramework that Signal publishes separately. SwiftPM only allows a binary target inside a package the app
// owns, so this tiny package holds it. Both must be the same release, or linking fails on `signal_ffi_version_X_Y_Z`.
// Version pinned together with `libsignal` in gradle/libs.versions.toml.

import PackageDescription

let package = Package(
    name: "LibSignalFfi",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "LibSignalFfi", targets: ["LibSignalFfi"]),
    ],
    targets: [
        .binaryTarget(
            name: "LibSignalFfi",
            url: "https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.106.1.xcframework.zip",
            checksum: "60baf6a3a8ee4cab82339a5e4ed4676e2119c6dc8002018f8063d6a96c195a77"
        ),
    ]
)
