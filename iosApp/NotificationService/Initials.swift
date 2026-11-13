import Intents
import UIKit

/// The app's Avatar when there's no photo (designsystem/components/Avatar.kt and AvatarInitials.kt): up to two
/// initials, SemiBold, on the `fillStrong` disc in `ink`. Drawn for the light theme: a notification's icon can't follow
/// the phone's appearance after it is posted, and the light disc reads on both.
enum Initials {
    private static let fill = UIColor(red: 0xDC / 255, green: 0xDC / 255, blue: 0xE0 / 255, alpha: 1)
    private static let ink = UIColor(red: 0x0B / 255, green: 0x0B / 255, blue: 0x0C / 255, alpha: 1)
    private static let size: CGFloat = 192

    /// "Shiru (cousin)" gives "SC"; a name with no letters at all shows its first character whole.
    static func letters(_ name: String) -> String {
        let words = name.split(whereSeparator: \.isWhitespace)
        let firsts = words.compactMap { $0.first { $0.isLetter || $0.isNumber } }.prefix(2)
        if !firsts.isEmpty { return String(firsts).uppercased() }
        return name.trimmingCharacters(in: .whitespaces).first.map(String.init) ?? ""
    }

    static func image(_ name: String) -> INImage? {
        let text = letters(name)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format)
        let picture = renderer.image { _ in
            fill.setFill()
            UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: size, height: size)).fill()
            let font = UIFont.systemFont(ofSize: size * 0.36, weight: .semibold)
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: ink]
            let bounds = (text as NSString).size(withAttributes: attributes)
            (text as NSString).draw(at: CGPoint(x: (size - bounds.width) / 2, y: (size - bounds.height) / 2), withAttributes: attributes)
        }
        return picture.pngData().map { INImage(imageData: $0) }
    }
}
