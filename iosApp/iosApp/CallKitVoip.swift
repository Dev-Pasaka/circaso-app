import AVFoundation
import CallKit
import PushKit
import Shared
import WebRTC

/// Incoming calls on iOS, even with Circaso closed or the phone locked: the server sends a PushKit (VoIP) push for a
/// ring, and CallKit shows the system's full-screen incoming call with the person's ringtone. Apple requires every
/// VoIP push to be reported to CallKit at once, so that happens before anything else. Answer and Decline go to the
/// shared CallController through IosCallKitBridge; the controller tells CallKit when a call ends or is answered in
/// the app. CallKit owns the audio session for these calls, so WebRTC runs in manual-audio mode and starts its audio
/// when CallKit activates the session.
final class CallKitVoip: NSObject, PKPushRegistryDelegate, CXProviderDelegate, CallKitProvider {
    static let shared = CallKitVoip()

    private let registry = PKPushRegistry(queue: .main)
    private let provider: CXProvider
    private let callController = CXCallController()
    /// CallKit's UUID for each call id, and back.
    private var uuids: [String: UUID] = [:]
    private var answered: Set<String> = []

    /// True while a call reported to CallKit is live: CallKit, not CallEngineImpl, activates the audio session.
    private(set) var ownsAudio = false

    private override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = true
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = true
        if let icon = UIImage(named: "CallKitIcon") { config.iconTemplateImageData = icon.pngData() }
        provider = CXProvider(configuration: config)
        super.init()
    }

    static func install() {
        let me = shared
        RTCAudioSession.sharedInstance().useManualAudio = true
        me.provider.setDelegate(me, queue: .main)
        me.registry.delegate = me
        me.registry.desiredPushTypes = [.voIP]
        IosCallKitBridge.shared.provider = me
    }

    // MARK: PushKit

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let hex = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        IosCallKitBridge.shared.tokenUpdated(hex: hex)
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        let data = payload.dictionaryPayload
        let callId = data["callId"] as? String ?? UUID().uuidString
        let name = (data["callerName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Circaso"
        let video = (data["callKind"] as? String) == "VIDEO"
        let uuid = uuids[callId] ?? UUID()
        uuids[callId] = uuid
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: name)
        update.localizedCallerName = name
        update.hasVideo = video
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if error == nil, data["callId"] != nil {
                self.ownsAudio = true
                IosCallKitBridge.shared.incoming(callId: callId)
            } else if error != nil {
                // Already ringing (the same call reported twice) or Do Not Disturb: nothing more to do.
                if (error as? CXErrorCodeIncomingCallError)?.code != .callUUIDAlreadyExists { self.forget(callId) }
            }
            completion()
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {}

    // MARK: CallKit actions

    func providerDidReset(_ provider: CXProvider) {
        for callId in uuids.keys { IosCallKitBridge.shared.end(callId: callId) }
        uuids.removeAll()
        answered.removeAll()
        ownsAudio = false
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard let callId = callId(for: action.callUUID) else { action.fail(); return }
        answered.insert(callId)
        IosCallKitBridge.shared.answer(callId: callId)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        if let callId = callId(for: action.callUUID) {
            IosCallKitBridge.shared.end(callId: callId)
            forget(callId)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        action.fulfill() // the app's own mute button drives the microphone
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        let rtc = RTCAudioSession.sharedInstance()
        rtc.audioSessionDidActivate(audioSession)
        rtc.isAudioEnabled = true
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        let rtc = RTCAudioSession.sharedInstance()
        rtc.audioSessionDidDeactivate(audioSession)
        rtc.isAudioEnabled = false
    }

    // MARK: From the app (IosCallKitBridge)

    func reportEnded(callId: String) {
        DispatchQueue.main.async {
            guard let uuid = self.uuids[callId] else { return }
            self.provider.reportCall(with: uuid, endedAt: nil, reason: self.answered.contains(callId) ? .remoteEnded : .unanswered)
            self.forget(callId)
        }
    }

    func reportAnswered(callId: String) {
        DispatchQueue.main.async {
            guard let uuid = self.uuids[callId], !self.answered.contains(callId) else { return }
            self.answered.insert(callId)
            self.callController.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { _ in }
        }
    }

    private func callId(for uuid: UUID) -> String? { uuids.first { $0.value == uuid }?.key }

    private func forget(_ callId: String) {
        uuids[callId] = nil
        answered.remove(callId)
        if uuids.isEmpty { ownsAudio = false }
    }
}
