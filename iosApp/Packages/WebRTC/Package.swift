// swift-tools-version:5.9
// Google WebRTC M155 as built by stasel/WebRTC 155.0.0 (checksum cb9adacf2d6b… matches the release). Kept as a local
// binary package because Swift Package Manager's download of the release asset hangs on this build machine.
import PackageDescription

let package = Package(
    name: "WebRTC",
    platforms: [.iOS(.v13)],
    products: [.library(name: "WebRTC", targets: ["WebRTC"])],
    targets: [.binaryTarget(name: "WebRTC", path: "WebRTC.xcframework")]
)
