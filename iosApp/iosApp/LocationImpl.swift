import CoreLocation
import Shared

/// CoreLocation for "Share location" in a chat: asks "when in use" the first time, then one fix (or a recent one).
/// Live location uses a second manager that keeps updating in the background while a share is on.
final class LocationImpl: NSObject, LocationBridgeProvider, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var pending: [(KotlinDouble?, KotlinDouble?, KotlinDouble?) -> Void] = []
    private let live = CLLocationManager()
    private var onFix: ((KotlinDouble, KotlinDouble, KotlinDouble) -> Void)?
    /// The map's "you are here": a third manager that only runs while the map is on screen (never in the background).
    private let nearby = CLLocationManager()
    private var onNearby: ((KotlinDouble, KotlinDouble, KotlinDouble) -> Void)?
    private var pendingAccess: [(String) -> Void] = []
    /// Location Services for the whole phone. Asking can block, so it is read off the main thread and kept.
    private var servicesOn = true

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        refreshServices()
    }

    private func refreshServices() {
        DispatchQueue.global(qos: .utility).async {
            let on = CLLocationManager.locationServicesEnabled()
            DispatchQueue.main.async { self.servicesOn = on }
        }
    }

    func current(completion: @escaping (KotlinDouble?, KotlinDouble?, KotlinDouble?) -> Void) {
        DispatchQueue.main.async {
            switch self.manager.authorizationStatus {
            case .denied, .restricted:
                completion(nil, nil, nil)
            case .notDetermined:
                self.pending.append(completion)
                self.manager.requestWhenInUseAuthorization()
            default:
                self.pending.append(completion)
                self.manager.requestLocation()
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refreshServices()
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: if !pending.isEmpty { self.manager.requestLocation() }
        case .denied, .restricted: finish(nil)
        default: break
        }
        if manager.authorizationStatus != .notDetermined, !pendingAccess.isEmpty {
            let waiting = pendingAccess
            pendingAccess.removeAll()
            let answer = access()
            for done in waiting { done(answer) }
        }
    }

    func access() -> String {
        // Off for the whole phone: Circaso's own page in Settings can't change that, so it is told apart.
        if !servicesOn { return "off" }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: return "granted"
        case .notDetermined: return "notAsked"
        default: return "denied"
        }
    }

    func requestAccess(completion: @escaping (String) -> Void) {
        DispatchQueue.main.async {
            if self.manager.authorizationStatus == .notDetermined {
                self.pendingAccess.append(completion)
                self.manager.requestWhenInUseAuthorization()
            } else {
                completion(self.access())
            }
        }
    }

    func startNearby(onFix: @escaping (KotlinDouble, KotlinDouble, KotlinDouble) -> Void) {
        DispatchQueue.main.async {
            self.onNearby = onFix
            self.nearby.delegate = self
            self.nearby.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
            self.nearby.distanceFilter = 5
            self.nearby.startUpdatingLocation()
        }
    }

    func stopNearby() {
        DispatchQueue.main.async {
            self.nearby.stopUpdatingLocation()
            self.onNearby = nil
        }
    }

    func startUpdates(onFix: @escaping (KotlinDouble, KotlinDouble, KotlinDouble) -> Void) {
        DispatchQueue.main.async {
            self.onFix = onFix
            self.live.delegate = self
            self.live.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
            self.live.distanceFilter = kCLDistanceFilterNone
            self.live.pausesLocationUpdatesAutomatically = false
            // Needs the `location` background mode; the blue pill tells the person sharing is on.
            self.live.allowsBackgroundLocationUpdates = true
            self.live.showsBackgroundLocationIndicator = true
            self.live.startUpdatingLocation()
        }
    }

    func stopUpdates() {
        DispatchQueue.main.async {
            self.live.stopUpdatingLocation()
            self.live.allowsBackgroundLocationUpdates = false
            self.onFix = nil
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if manager === live {
            if let l = locations.last, let fix = onFix {
                fix(KotlinDouble(value: l.coordinate.latitude), KotlinDouble(value: l.coordinate.longitude), KotlinDouble(value: l.horizontalAccuracy))
            }
            return
        }
        if manager === nearby {
            if let l = locations.last, let fix = onNearby {
                fix(KotlinDouble(value: l.coordinate.latitude), KotlinDouble(value: l.coordinate.longitude), KotlinDouble(value: l.horizontalAccuracy))
            }
            return
        }
        finish(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if manager === live || manager === nearby { return }
        finish(manager.location)
    }

    private func finish(_ location: CLLocation?) {
        let waiting = pending
        pending.removeAll()
        for done in waiting {
            if let l = location {
                done(KotlinDouble(value: l.coordinate.latitude), KotlinDouble(value: l.coordinate.longitude), KotlinDouble(value: l.horizontalAccuracy))
            } else {
                done(nil, nil, nil)
            }
        }
    }
}
