import AVFoundation
import Shared

/// Re-encodes a moment's video with the shared rule (`MomentVideoEncoding.plan`, Pasaka 2026-11-07): the source's
/// size up to 1080 on the short side (never upscaled), its frame rate, H.264 High profile, and a bit rate for that size
/// that the 32 MiB limit allows for the clip's length. AVAssetExportSession can't set a bit rate (its 1280x720 preset
/// was the soft video Pasaka saw), so this is AVAssetReader into AVAssetWriter, the platform's own tools.
enum MomentVideoTranscoder {
    /// Writes [range] of [asset] to [url] as MP4; false when this phone can't (the caller then has nothing to post).
    static func transcode(_ asset: AVAsset, range: CMTimeRange, to url: URL, maxBytes: Int64 = MomentCameraKt.MomentMediaMaxBytes) async -> Bool {
        guard let video = try? await asset.loadTracks(withMediaType: .video).first,
              let loaded = try? await video.load(.naturalSize, .preferredTransform, .nominalFrameRate, .estimatedDataRate)
        else { return false }
        let (natural, transform, fps, dataRate) = loaded
        let shown = CGRect(origin: .zero, size: natural).applying(transform).size
        let sideways = abs(shown.width - natural.width) > 1
        let durationMs = Int64((CMTimeGetSeconds(range.duration) * 1000).rounded())
        let plan = MomentVideoEncoding.shared.plan(
            width: Int32(abs(shown.width).rounded()), height: Int32(abs(shown.height).rounded()),
            frameRate: fps > 0 ? KotlinFloat(value: fps) : nil,
            sourceBitRate: dataRate > 0 ? KotlinLong(value: Int64(dataRate)) : nil,
            durationMs: max(durationMs, 1), maxBytes: maxBytes
        )
        // The encoder works in the track's own orientation; the rotation stays in the file as it was.
        let (encW, encH) = sideways ? (Int(plan.height), Int(plan.width)) : (Int(plan.width), Int(plan.height))

        try? FileManager.default.removeItem(at: url)
        guard let reader = try? AVAssetReader(asset: asset), let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        reader.timeRange = range
        writer.shouldOptimizeForNetworkUse = true

        let videoOut = AVAssetReaderTrackOutput(track: video, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        ])
        videoOut.alwaysCopiesSampleData = false
        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: Int(plan.bitRate),
            AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            AVVideoH264EntropyModeKey: AVVideoH264EntropyModeCABAC,
            AVVideoMaxKeyFrameIntervalDurationKey: 2,
        ]
        if fps > 0 { compression[AVVideoExpectedSourceFrameRateKey] = Int(fps.rounded()) }
        let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: encW,
            AVVideoHeightKey: encH,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoCompressionPropertiesKey: compression,
        ])
        videoIn.transform = transform
        videoIn.expectsMediaDataInRealTime = false
        guard reader.canAdd(videoOut), writer.canAdd(videoIn) else { return false }
        reader.add(videoOut)
        writer.add(videoIn)

        var pairs: [(AVAssetReaderOutput, AVAssetWriterInput)] = [(videoOut, videoIn)]
        if let audio = try? await asset.loadTracks(withMediaType: .audio).first {
            var channels = 2
            var sampleRate = 44_100.0
            if let format = try? await audio.load(.formatDescriptions).first,
               let basic = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee {
                channels = Int(min(max(basic.mChannelsPerFrame, 1), 2))
                if basic.mSampleRate >= 8_000 && basic.mSampleRate <= 48_000 { sampleRate = basic.mSampleRate }
            }
            let audioOut = AVAssetReaderTrackOutput(track: audio, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVNumberOfChannelsKey: channels,
                AVSampleRateKey: sampleRate,
            ])
            let audioIn = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: channels,
                AVSampleRateKey: sampleRate,
                AVEncoderBitRateKey: Int(MomentVideoEncoding.shared.AudioBitRate),
            ])
            audioIn.expectsMediaDataInRealTime = false
            if reader.canAdd(audioOut), writer.canAdd(audioIn) {
                reader.add(audioOut)
                writer.add(audioIn)
                pairs.append((audioOut, audioIn))
            }
        }

        guard reader.startReading(), writer.startWriting() else { reader.cancelReading(); writer.cancelWriting(); return false }
        writer.startSession(atSourceTime: range.start)
        await withTaskGroup(of: Void.self) { group in
            for (index, pair) in pairs.enumerated() {
                group.addTask { await pump(pair.0, into: pair.1, queue: DispatchQueue(label: "moment-transcode-\(index)")) }
            }
        }
        if reader.status == .failed { writer.cancelWriting(); return false }
        await writer.finishWriting()
        return writer.status == .completed
    }

    /// Copies samples from [output] into [input] whenever the writer wants more, until the reader runs out.
    private static func pump(_ output: AVAssetReaderOutput, into input: AVAssetWriterInput, queue: DispatchQueue) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            var finished = false
            input.requestMediaDataWhenReady(on: queue) {
                guard !finished else { return }
                while input.isReadyForMoreMediaData {
                    guard let sample = output.copyNextSampleBuffer() else {
                        finished = true
                        input.markAsFinished()
                        cont.resume()
                        return
                    }
                    if !input.append(sample) {
                        finished = true
                        input.markAsFinished()
                        cont.resume()
                        return
                    }
                }
            }
        }
    }
}
