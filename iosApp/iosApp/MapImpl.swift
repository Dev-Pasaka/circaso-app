import CoreLocation
import GoogleMaps
import Shared
import UIKit

/// Google Maps for live location's map (see PlatformMap.ios.kt). Installed only when this build has a key
/// (local.properties `maps.ios.key`, restricted to the bundle id); without one the map falls back to the
/// server's picture.
final class MapImpl: NSObject, MapViewProvider {
    static func install() {
        let key = MapBridge.shared.apiKey
        guard !key.isEmpty else { return }
        GMSServices.provideAPIKey(key)
        MapBridge.shared.provider = MapImpl()
    }

    func makeMap(styleJson: String, latitude: Double, longitude: Double, onCamera: @escaping () -> Void) -> NativeMap {
        NativeMapImpl(styleJson: styleJson, latitude: latitude, longitude: longitude, onCamera: onCamera)
    }
}

final class NativeMapImpl: NSObject, NativeMap, GMSMapViewDelegate {
    private let mapView: GMSMapView
    private let onCamera: () -> Void

    var view: UIView { mapView }

    init(styleJson: String, latitude: Double, longitude: Double, onCamera: @escaping () -> Void) {
        let options = GMSMapViewOptions()
        options.camera = GMSCameraPosition(latitude: latitude, longitude: longitude, zoom: 16)
        mapView = GMSMapView(options: options)
        self.onCamera = onCamera
        super.init()
        mapView.delegate = self
        mapView.settings.compassButton = false
        mapView.settings.myLocationButton = false
        mapView.isBuildingsEnabled = false
        mapView.isIndoorEnabled = false
        setStyle(json: styleJson)
    }

    func mapView(_ mapView: GMSMapView, didChange position: GMSCameraPosition) { onCamera() }

    func mapViewDidFinishTileRendering(_ mapView: GMSMapView) { onCamera() }

    func project(latitude: Double, longitude: Double) -> MapPoint? {
        guard mapView.bounds.width > 0 else { return nil }
        let p = mapView.projection.point(for: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
        return MapPoint(x: Double(p.x), y: Double(p.y))
    }

    func moveTo(latitude: Double, longitude: Double, zoom: Double) {
        let z = zoom < 0 ? mapView.camera.zoom : Float(zoom)
        mapView.animate(to: GMSCameraPosition(latitude: latitude, longitude: longitude, zoom: z))
    }

    func fit(latitudes: [KotlinDouble], longitudes: [KotlinDouble], animated: Bool) {
        var bounds = GMSCoordinateBounds()
        for (lat, lng) in zip(latitudes, longitudes) {
            bounds = bounds.includingCoordinate(CLLocationCoordinate2D(latitude: lat.doubleValue, longitude: lng.doubleValue))
        }
        let update = GMSCameraUpdate.fit(bounds, withPadding: 96)
        if animated { mapView.animate(with: update) } else { mapView.moveCamera(update) }
    }

    func setStyle(json: String) {
        mapView.mapStyle = try? GMSMapStyle(jsonString: json)
    }
}
