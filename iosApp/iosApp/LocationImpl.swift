import CoreLocation
import Shared

/// CoreLocation for "Share location" in a chat: asks "when in use" the first time, then one fix (or a recent one).
/// Live location uses a second manager that keeps updating in the background while a share is on.
final class LocationImpl: NSObject, LocationBridgeProvider, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var pending: [(KotlinDouble?, KotlinDouble?, KotlinDouble?) -> Void] = []
    private let live = CLLocationManager()
    private var onFix: ((KotlinDouble, KotlinDouble, KotlinDouble) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
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
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: if !pending.isEmpty { manager.requestLocation() }
        case .denied, .restricted: finish(nil)
        default: break
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
        finish(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if manager === live { return }
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
