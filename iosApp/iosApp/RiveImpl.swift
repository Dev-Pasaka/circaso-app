import RiveRuntime
import Shared
import UIKit

/// Asha's animated mascot through RiveRuntime (Swift package `rive-app/rive-ios`, pinned to 6.29.0 in the project).
/// The shared code (`IosRiveAnimation.kt`) hands over the file's bytes and the names to use, then sets the view model's
/// properties by name; nothing about Asha lives here. Uses data binding, not state machine inputs.
final class RiveImpl: NSObject, PlatformRiveProvider {
    /// The parsed file, kept for the process: every screen that opens reuses it and only makes its own instances.
    private var cached: (data: Data, file: RiveFile)?

    func make(data: Data, artboard: String, stateMachine: String, viewModel: String, maxFps: Int32, round: Bool) -> (any PlatformRiveHandle)? {
        let file: RiveFile
        if let cached, cached.data == data {
            file = cached.file
        } else {
            guard let parsed = try? RiveFile(data: data, loadCdn: false) else { return nil }
            cached = (data, parsed)
            file = parsed
        }
        // Checked first: RiveViewModel's initialiser force-tries these and would stop the app on a bad name.
        let model = RiveModel(riveFile: file)
        do {
            try model.setArtboard(artboard)
            try model.setStateMachine(stateMachine)
        } catch {
            return nil
        }
        guard let vm = file.viewModelNamed(viewModel), let instance = vm.createDefaultInstance() else { return nil }
        let rive = RiveViewModel(model, stateMachineName: stateMachine, fit: .contain, alignment: .center, autoPlay: true, artboardName: artboard)
        guard let machine = rive.riveModel?.stateMachine else { return nil }
        machine.bind(viewModelInstance: instance)
        return RiveHandleImpl(rive: rive, instance: instance, maxFps: Int(maxFps), round: round)
    }
}

/// One mascot on screen. It draws only while it is in a window, the app is in the foreground and the shared code
/// wants it playing (reduced motion holds a still frame), so it costs nothing off screen or in the background.
final class RiveHandleImpl: NSObject, PlatformRiveHandle {
    private let rive: RiveViewModel
    private let instance: RiveDataBindingViewModel.Instance
    private let container = WindowWatchingView()
    private var wanted = true
    private var running = true

    init(rive: RiveViewModel, instance: RiveDataBindingViewModel.Instance, maxFps: Int, round: Bool) {
        self.rive = rive
        self.instance = instance
        super.init()
        let riveView = rive.createRiveView()
        riveView.isOpaque = false
        riveView.backgroundColor = .clear
        riveView.frame = container.disc.bounds
        riveView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Touches belong to Compose (a tap pokes her there).
        riveView.isUserInteractionEnabled = false
        // A small header icon needs no more than 30 fps; 0 follows the display.
        if maxFps > 0 { riveView.setPreferredFramesPerSecond(preferredFramesPerSecond: maxFps) }
        container.disc.addSubview(riveView)
        container.round = round
        container.onWindowChange = { [weak self] in self?.apply() }
        let centre = NotificationCenter.default
        centre.addObserver(self, selector: #selector(appStateChanged), name: UIApplication.didEnterBackgroundNotification, object: nil)
        centre.addObserver(self, selector: #selector(appStateChanged), name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    func view() -> UIView { container }

    func setDiscFill(color: UIColor) {
        container.disc.backgroundColor = color
    }

    func setEnum(name: String, value: String) {
        instance.enumProperty(fromPath: name)?.value = value
    }

    func setNumber(name: String, value: Float) {
        instance.numberProperty(fromPath: name)?.value = value
    }

    func fire(enumProperty: String, value: String, trigger: String) {
        setEnum(name: enumProperty, value: value)
        instance.triggerProperty(fromPath: trigger)?.trigger()
    }

    func setPlaying(playing: Bool) {
        wanted = playing
        apply()
    }

    func dispose() {
        NotificationCenter.default.removeObserver(self)
        container.onWindowChange = nil
        rive.pause()
        rive.deregisterView()
        container.disc.subviews.forEach { $0.removeFromSuperview() }
    }

    @objc private func appStateChanged(_ note: Notification) {
        // willEnterForeground arrives before the state flips to active, so read the notification, not the state.
        apply(foreground: note.name == UIApplication.willEnterForegroundNotification)
    }

    private func apply(foreground: Bool? = nil) {
        let inForeground = foreground ?? (UIApplication.shared.applicationState != .background)
        let run = wanted && container.window != nil && inForeground
        guard run != running else { return }
        running = run
        if run { rive.play() } else { rive.pause() }
    }
}

/// Tells its owner when it joins or leaves a window (the screen was pushed away or came back). Its own background is
/// the colour around it (Compose leaves a clear hole over it); `disc` holds the drawing, rounded for an avatar.
private final class WindowWatchingView: UIView {
    var onWindowChange: (() -> Void)?
    /// A disc (the header icon), like every other avatar; Compose's clip can't reach a view under it.
    var round = false
    let disc = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        disc.frame = bounds
        disc.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        disc.isUserInteractionEnabled = false
        addSubview(disc)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        disc.layer.cornerRadius = round ? min(bounds.width, bounds.height) / 2 : 0
        disc.layer.masksToBounds = round
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        onWindowChange?()
    }
}
