import Foundation
import Shared
#if canImport(onnxruntime_objc)
import onnxruntime_objc
#endif

/// Silero VAD (MIT, v5, 16 kHz) on ONNX Runtime's Objective-C API, for the phone's own barge-in in voice mode. The
/// shared code does the framing, context and state (SileroVad.kt); this only runs the graph: one session, one thread,
/// every graph optimisation. Installed at launch; see IosVadEngine.kt. Without the pod (`pod install` not run) the
/// build still works and `open` answers nil: the voice screen falls back to its energy detector.
final class VadImpl: NSObject, VadBridgeApi {
    func open(model: Data) -> (any VadSessionApi)? {
        #if canImport(onnxruntime_objc)
        return try? VadSession(model: model)
        #else
        return nil
        #endif
    }
}

#if canImport(onnxruntime_objc)
private final class VadSession: NSObject, VadSessionApi {
    private static let env = try? ORTEnv(loggingLevel: .warning)
    private let session: ORTSession
    private let sampleRate: ORTValue

    init(model: Data) throws {
        guard let env = Self.env else { throw CocoaError(.featureUnsupported) }
        // ORTSession opens a file: the model is written once to Caches (and again if the bundled one changes)
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("silero_vad.onnx")
        let existing = try? Data(contentsOf: url)
        if existing != model { try model.write(to: url, options: .atomic) }
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(1)
        try options.setGraphOptimizationLevel(.all)
        session = try ORTSession(env: env, modelPath: url.path, sessionOptions: options)
        var rate: Int64 = 16_000
        sampleRate = try ORTValue(tensorData: NSMutableData(bytes: &rate, length: MemoryLayout<Int64>.size), elementType: .int64, shape: [])
    }

    func run(input: Data, state: Data) -> Data? {
        do {
            let x = try ORTValue(tensorData: NSMutableData(data: input), elementType: .float, shape: [1, NSNumber(value: input.count / 4)])
            let h = try ORTValue(tensorData: NSMutableData(data: state), elementType: .float, shape: [2, 1, 128])
            let out = try session.run(withInputs: ["input": x, "state": h, "sr": sampleRate], outputNames: ["output", "stateN"], runOptions: nil)
            guard let p = try out["output"]?.tensorData(), let next = try out["stateN"]?.tensorData() else { return nil }
            var answer = Data(capacity: 4 + next.length)
            answer.append(Data(referencing: p).prefix(4))
            answer.append(Data(referencing: next))
            return answer
        } catch {
            return nil
        }
    }

    func close() {}
}
#endif
