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
    /// The straight line between "you" and whoever the map is focused on (see setLine).
    private var polyline: GMSPolyline?
    private var lineColor: UIColor = .clear
    /// The zoom the line's dashes were last measured at: they are lengths on the ground, so they are redone as it changes.
    private var dashZoom: Float = -1

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
        // The screen reports its own bar and panel heights (setInsets), safe areas included.
        mapView.paddingAdjustmentBehavior = .never
        setStyle(json: styleJson)
    }

    func mapView(_ mapView: GMSMapView, didChange position: GMSCameraPosition) {
        onCamera()
        if polyline != nil, abs(position.zoom - dashZoom) > 0.2 { dashLine() }
    }

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
        // Room for the pins (62 pt tall, plus the "You" tag), on top of the map's own padding (setInsets).
        let update = GMSCameraUpdate.fit(bounds, withPadding: 72)
        if animated { mapView.animate(with: update) } else { mapView.moveCamera(update) }
    }

    func setStyle(json: String) {
        mapView.mapStyle = try? GMSMapStyle(jsonString: json)
    }

    func setLine(latitudes: [KotlinDouble], longitudes: [KotlinDouble], red: Double, green: Double, blue: Double, alpha: Double) {
        guard latitudes.count > 1, latitudes.count == longitudes.count else {
            polyline?.map = nil
            polyline = nil
            return
        }
        let path = GMSMutablePath()
        for (lat, lng) in zip(latitudes, longitudes) {
            path.add(CLLocationCoordinate2D(latitude: lat.doubleValue, longitude: lng.doubleValue))
        }
        let line = polyline ?? GMSPolyline()
        line.path = path
        line.strokeWidth = 3
        line.geodesic = true
        line.isTappable = false
        lineColor = UIColor(red: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: CGFloat(alpha))
        line.strokeColor = lineColor
        line.map = mapView
        polyline = line
        dashLine()
    }

    /// Dashes of 8 pt with 6 pt gaps at the current zoom (the SDK measures them in metres). A very long line
    /// seen close up would need thousands of them, so its dashes are stretched to keep it to 600 pairs: it is
    /// always broken, never a solid line that could be taken for a road route.
    private func dashLine() {
        guard let line = polyline, let path = line.path else { return }
        dashZoom = mapView.camera.zoom
        let pointsPerMetre = Double(mapView.projection.points(forMeters: 1, at: mapView.camera.target))
        let length = path.length(of: .geodesic)
        guard pointsPerMetre > 0, length > 0 else {
            line.spans = nil
            return
        }
        let stretch = max(1, length * pointsPerMetre / 14 / 600)
        let styles = [GMSStrokeStyle.solidColor(lineColor), GMSStrokeStyle.solidColor(.clear)]
        let lengths = [NSNumber(value: 8 * stretch / pointsPerMetre), NSNumber(value: 6 * stretch / pointsPerMetre)]
        line.spans = GMSStyleSpans(path, styles, lengths, .geodesic)
    }

    func setInsets(top: Double, bottom: Double) {
        mapView.padding = UIEdgeInsets(top: CGFloat(top), left: 0, bottom: CGFloat(bottom), right: 0)
    }
}
