import FirebaseCore
import FirebaseCrashlytics
import Shared

/// Crashlytics for iPhone (2026-11-13): the Swift side of `CrashReporting.kt`. Crashes, breadcrumbs (the app's scrubbed
/// Diagnostics notes) and Kotlin exceptions with their Kotlin stacks. No user id or any other identifier is set.
/// A build without GoogleService-Info.plist (kept out of git) has no crash reporting; nothing else changes.
final class CrashReporterImpl: CrashReporterProvider {
    /// Configures Firebase if this build has its plist (push may already have), then hands Kotlin the reporter.
    static func install() {
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        #if DEBUG
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(false) // Xcode runs don't fill the dashboard
        #endif
        CrashReporterBridge.shared.provider = CrashReporterImpl()
        CrashReportingKt.installCrashReporting()
    }

    func log(message: String) {
        Crashlytics.crashlytics().log(message)
    }

    func recordKotlinCrash(name: String, reason: String, addresses: [KotlinLong]) {
        let model = ExceptionModel(name: name, reason: reason)
        model.stackTrace = addresses.map { StackFrame(address: UInt(bitPattern: Int($0.int64Value))) }
        Crashlytics.crashlytics().record(exceptionModel: model)
    }
}
