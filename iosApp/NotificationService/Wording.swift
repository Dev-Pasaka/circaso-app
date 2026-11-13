import Foundation

/// The words, exactly as Android writes them (shared/…/core/notifications/NotificationText.kt, MessagePreview.kt and
/// MoneyWording): change both together. The extension can't run the shared Kotlin code (it is far too large for an
/// extension's memory), so the few rules it needs are kept here.
enum Wording {
    static let appName = "Circaso"
    static let privateMessage = "New message"
    static let privateMoney = "You received money"
    static let privateGroupMoney = "Money moved in a group"
    static let privateMissedCall = "Missed call"
    static let privateGroupInvite = "You have a new group invite"
    static let privateAddedToGroup = "You were added to a group"

    /// With Notification privacy on: nothing that names a person, a group, an amount or what was said. [kind] is the
    /// push's `kind`; nil with a message id is a chat message, nil without one a group add. Nil keeps the server's words.
    static func privateWords(kind: String?, hasMessage: Bool) -> (String, String)? {
        switch kind {
        case nil: return (appName, hasMessage ? privateMessage : privateAddedToGroup)
        case "payment.received": return (appName, privateMoney)
        case "chama.contribution": return (appName, privateGroupMoney)
        case let k? where k.hasPrefix("chama.request"): return (appName, privateGroupMoney)
        case "call.missed", "call.ended": return (appName, privateMissedCall)
        case "chat.invite": return (appName, privateGroupInvite)
        case "contact.joined": return (appName, "Someone you know joined Circaso")
        case "connection.added": return (appName, "You have a new connection")
        case "moment.loved": return (appName, "Someone reacted to your moment")
        case "wallet.setup": return (appName, "Someone wants to send you money")
        // Words with nothing personal in them stay as the server wrote them.
        case "session.revoked", "asha.back", "support.ticket", "transfer.updated": return nil
        default: return (appName, "New notification")
        }
    }

    // MARK: chat messages

    /// Payloads that never show as a message (ChatPayload's control types).
    static let silentTypes: Set<String> = ["liveUpdate", "liveStop", "control", "momentBody", "metaKey", "metaKeyRequest", "metaKeyNone", "senderKey"]

    /// A message's own words ("📷 Photo", "🎤 Voice message (0:12)"); nil when there's nothing to say.
    static func preview(_ p: Payload) -> String? {
        let caption = p.text?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        switch p.type {
        case "text": return caption
        case "image": return caption.map { "📷 \($0)" } ?? "📷 Photo"
        case "video": return caption.map { "🎥 \($0)" } ?? "🎥 Video" + duration(p.durationMs)
        case "voice": return "🎤 Voice message" + duration(p.durationMs)
        case "file":
            let name = caption ?? p.label?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            return name.map { "📄 \($0)" } ?? "📄 Document"
        case "liveLocation": return "📍 Live location"
        case "location": return p.label?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty.map { "📍 \($0)" } ?? "📍 Location"
        default: return nil
        }
    }

    /// What a message says without who said it (the communication notification names the sender beside it).
    static func line(_ said: String?, momentReply: Bool) -> String {
        guard let said, !said.isEmpty else { return privateMessage }
        return momentReply ? "Replied to your moment: \(said)" : said
    }

    static func reaction(emoji: String, target: String?) -> String {
        let quoted = target.flatMap { $0.isEmpty || $0.hasPrefix("{") ? nil : " to \"\(String($0.prefix(60)))\"" } ?? " to your message"
        return "Reacted \(emoji)\(quoted)"
    }

    private static func duration(_ ms: Int64?) -> String {
        guard let ms, ms / 1000 > 0 else { return "" }
        let s = ms / 1000
        return " (\(s / 60):\(String(format: "%02d", s % 60)))"
    }

    // MARK: money

    /// "KES 1,300" in the person's own currency when there's a rate (dollar coins), else "20.00 USDC".
    static func amount(minor: String, asset: String, fiat: Snapshot.Fiat?) -> String {
        let decimals = decimalsOf(asset)
        let coin = coinText(minor, decimals) + " " + asset
        guard let fiat, isDollar(asset), let rate = Decimal(string: fiat.rate, locale: Locale(identifier: "en_US_POSIX")),
              let minorValue = Decimal(string: minor, locale: Locale(identifier: "en_US_POSIX")) else { return coin }
        var local = minorValue / pow(10, decimals) * rate
        local = rounded(local, scale: 2)
        if local >= 100 { local = rounded(local, scale: 0) }
        return "\(fiat.currency) \(fiatText(local))"
    }

    static func isDollar(_ asset: String) -> Bool { ["USDC", "USDT"].contains(asset.uppercased()) }

    static func decimalsOf(_ asset: String) -> Int {
        switch asset.uppercased() {
        case "BTC", "WBTC", "CBBTC", "CIRBTC": return 8
        case "SOL": return 9
        case "ETH", "WETH", "POL", "MATIC", "BNB", "AVAX", "DAI", "LINK": return 18
        default: return 6
        }
    }

    /// MinorUnits.toDisplay: the coin's decimals, trailing zeros dropped down to two, the whole part grouped.
    static func coinText(_ minor: String, _ decimals: Int) -> String {
        var digits = String(minor.trimmingCharacters(in: .whitespaces).drop { $0 == "0" })
        if digits.isEmpty { digits = "0" }
        while digits.count < decimals + 1 { digits = "0" + digits }
        let whole = String(digits.dropLast(decimals))
        var frac = String(digits.suffix(decimals))
        while frac.count > 2 && frac.hasSuffix("0") { frac.removeLast() }
        return frac.isEmpty ? group(whole) : group(whole) + "." + frac
    }

    /// formatFiat: whole units grouped, cents only when there are some.
    private static func fiatText(_ value: Decimal) -> String {
        let text = NSDecimalNumber(decimal: value).stringValue
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        let whole = String(parts.first ?? "0")
        var frac = parts.count > 1 ? String(parts[1].prefix(2)) : ""
        while frac.hasSuffix("0") { frac.removeLast() }
        return group(whole) + (frac.isEmpty ? "" : "." + frac.padding(toLength: 2, withPad: "0", startingAt: 0))
    }

    private static func group(_ whole: String) -> String {
        var out = ""
        for (i, c) in whole.reversed().enumerated() {
            if i > 0 && i % 3 == 0 { out.append(",") }
            out.append(c)
        }
        return String(out.reversed())
    }

    private static func rounded(_ value: Decimal, scale: Int) -> Decimal {
        var input = value
        var result = Decimal()
        NSDecimalRound(&result, &input, scale, .plain)
        return result
    }
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
