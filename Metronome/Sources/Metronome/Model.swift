import SwiftUI
import AppKit

// MARK: - Basic types

enum NoteState: Int, Codable { case normal, accent, rest }
enum AutoMode: String, Codable { case mute, add }
enum AddUnit: String, Codable { case bar, sec }

struct TimeSig: Hashable, Codable {
    var beats: Int
    var unit: Int
    var label: String { "\(beats)/\(unit)" }

    static let all: [TimeSig] = [
        TimeSig(beats: 2, unit: 4), TimeSig(beats: 3, unit: 4), TimeSig(beats: 4, unit: 4),
        TimeSig(beats: 5, unit: 4), TimeSig(beats: 6, unit: 4), TimeSig(beats: 7, unit: 4),
        TimeSig(beats: 3, unit: 8), TimeSig(beats: 5, unit: 8), TimeSig(beats: 6, unit: 8),
        TimeSig(beats: 7, unit: 8), TimeSig(beats: 9, unit: 8), TimeSig(beats: 12, unit: 8)
    ]

    /// Possible groupings (5/8 and 7/8 can be cycled).
    var groupings: [[Int]] {
        switch (beats, unit) {
        case (5, 8): return [[2, 3], [3, 2]]
        case (7, 8): return [[2, 2, 3], [3, 2, 2], [2, 3, 2]]
        case (3, 8): return [[3]]
        case (6, 8): return [[3, 3]]
        case (9, 8): return [[3, 3, 3]]
        case (12, 8): return [[3, 3, 3, 3]]
        default: return [[beats]]
        }
    }

    func groupStarts(_ index: Int) -> Set<Int> {
        let g = groupings
        let grp = g[min(max(0, index), g.count - 1)]
        var s = Set<Int>()
        var p = 0
        for x in grp { s.insert(p); p += x }
        return s
    }
}

struct Preset: Codable {
    var bpm: Int
    var sig: TimeSig
    var grouping: Int
    var subs: [Int]
    var states: [[NoteState]]
}

struct Config: Codable {
    var bpm = 120
    var sig = TimeSig(beats: 4, unit: 4)
    var grouping = 0
    var subs: [Int] = [1, 1, 1, 1]
    var states: [[NoteState]] = [[.accent], [.normal], [.normal], [.normal]]

    // Auto mode
    var autoOn = false
    var autoMode: AutoMode = .mute
    var playBars = 2
    var muteBars = 1
    var addBpm = 5
    var addEvery = 4
    var addUnit: AddUnit = .bar

    func state(_ b: Int, _ k: Int) -> NoteState {
        guard b >= 0, b < states.count, k >= 0, k < states[b].count else { return .normal }
        return states[b][k]
    }

    mutating func resetPattern(keepSubs: Bool = false) {
        let n = sig.beats
        if !keepSubs || subs.count != n { subs = Array(repeating: 1, count: n) }
        let starts = sig.groupStarts(grouping)
        states = (0..<n).map { b in
            (0..<max(1, subs[b])).map { k in
                (k == 0 && starts.contains(b)) ? NoteState.accent : NoteState.normal
            }
        }
    }

    var preset: Preset {
        Preset(bpm: bpm, sig: sig, grouping: grouping, subs: subs, states: states)
    }
}

func tempoName(_ bpm: Int) -> String {
    switch bpm {
    case ..<40: return "Grave"
    case 40..<60: return "Largo"
    case 60..<66: return "Larghetto"
    case 66..<76: return "Adagio"
    case 76..<108: return "Andante"
    case 108..<120: return "Moderato"
    case 120..<156: return "Allegro"
    case 156..<176: return "Vivace"
    case 176..<200: return "Presto"
    default: return "Prestissimo"
    }
}

// MARK: - Persistence helpers

private func saveJSON<T: Encodable>(_ value: T, _ key: String) {
    if let d = try? JSONEncoder().encode(value) { UserDefaults.standard.set(d, forKey: key) }
}

private func loadJSON<T: Decodable>(_ key: String) -> T? {
    guard let d = UserDefaults.standard.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(T.self, from: d)
}

// MARK: - Model

final class MetronomeModel: ObservableObject {
    let engine = ClickEngine()
    private let power = PowerManager()
    private var ticker: Timer?
    private var keyMonitor: Any?
    private var taps: [Date] = []

    @Published var cfg = Config() {
        didSet { engine.update(cfg); saveJSON(cfg, "cfg") }
    }
    @Published var isPlaying = false
    @Published var currentBeat = -1
    @Published var currentSub = -1
    @Published var flash = false
    @Published var flashAccent = false
    @Published var tempoEditTrigger = 0

    @Published var volume = 0.8 {
        didSet { applyVolume(); UserDefaults.standard.set(volume, forKey: "volume") }
    }
    @Published var muted = false { didSet { applyVolume() } }
    @Published var pan = 0.0 {
        didSet { engine.setPan(Float(pan)); UserDefaults.standard.set(pan, forKey: "pan") }
    }
    @Published var sound = SoundKind.click {
        didSet { engine.setSound(sound); UserDefaults.standard.set(sound.rawValue, forKey: "sound") }
    }
    @Published var lightEnabled = true {
        didSet { UserDefaults.standard.set(lightEnabled, forKey: "light") }
    }
    @Published var keepDisplayAwake = false {
        didSet { updatePower(); UserDefaults.standard.set(keepDisplayAwake, forKey: "keepAwake") }
    }
    @Published var floatOnTop = false {
        didSet { window?.level = floatOnTop ? .floating : .normal }
    }
    @Published var outputDevices: [AudioOutputDevice] = []
    @Published var outputDeviceName = "" {
        didSet { applyDevice(); UserDefaults.standard.set(outputDeviceName, forKey: "device") }
    }
    @Published var presets: [Preset?] = Array<Preset?>(repeating: nil, count: 9)
    @Published var currentSlot = 0

    weak var window: NSWindow? {
        didSet { window?.level = floatOnTop ? .floating : .normal }
    }

    init() {
        if let c: Config = loadJSON("cfg") {
            var loaded = c
            loaded.autoOn = false
            if loaded.subs.count == loaded.sig.beats && loaded.states.count == loaded.sig.beats {
                cfg = loaded
            }
        }
        if let p: [Preset?] = loadJSON("presets"), p.count == 9 { presets = p }

        let d = UserDefaults.standard
        if let v = d.object(forKey: "volume") as? Double { volume = v }
        if let s = d.string(forKey: "sound"), let k = SoundKind(rawValue: s) { sound = k }
        if let p = d.object(forKey: "pan") as? Double { pan = p }
        if d.object(forKey: "light") != nil { lightEnabled = d.bool(forKey: "light") }
        keepDisplayAwake = d.bool(forKey: "keepAwake")
        outputDevices = listOutputDevices()
        outputDeviceName = d.string(forKey: "device") ?? ""

        engine.onTempoChange = { [weak self] bpm in self?.setBPM(bpm) }
        engine.update(cfg)
        engine.setSound(sound)
        applyVolume()
        engine.setPan(Float(pan))

        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self = self else { return e }
            return self.handleKey(e) ? nil : e
        }
    }

    // MARK: Transport

    func toggle() { isPlaying ? stop() : start() }

    func start() {
        guard !isPlaying else { return }
        engine.start()
        isPlaying = true
        updatePower()
        let t = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        ticker = t
    }

    func stop() {
        guard isPlaying else { return }
        engine.stop()
        isPlaying = false
        ticker?.invalidate(); ticker = nil
        currentBeat = -1; currentSub = -1; flash = false
        updatePower()
    }

    private func tick() {
        let events = engine.drainEvents(upTo: engine.currentSample())
        guard let last = events.last else { return }
        currentBeat = last.beat
        currentSub = last.sub
        for e in events where e.sub == 0 && !e.muted && e.state != .rest && lightEnabled {
            flashAccent = e.state == .accent
            flash = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.09) { [weak self] in self?.flash = false }
        }
    }

    private func updatePower() {
        power.setSystem(isPlaying)
        power.setDisplay(isPlaying && keepDisplayAwake)
    }

    private func applyVolume() { engine.setVolume(muted ? 0 : Float(volume)) }

    func refreshDevices() { outputDevices = listOutputDevices() }

    private func applyDevice() {
        let id = outputDevices.first(where: { $0.name == outputDeviceName })?.id
        engine.setOutputDevice(id)
    }

    // MARK: Tempo

    func setBPM(_ v: Int) {
        let c = min(300, max(20, v))
        if c != cfg.bpm { cfg.bpm = c }
    }

    func nudge(_ d: Int) { setBPM(cfg.bpm + d) }

    /// Snap to the nearest multiple of 5 in the given direction first, then step by 5.
    func step5(_ dir: Int) {
        let b = cfg.bpm
        let r = b % 5
        if r == 0 { setBPM(b + 5 * dir) }
        else if dir > 0 { setBPM(b + (5 - r)) }
        else { setBPM(b - r) }
    }

    func tap() {
        let now = Date()
        if let l = taps.last, now.timeIntervalSince(l) > 2.0 { taps = [] }
        taps.append(now)
        if taps.count > 8 { taps.removeFirst() }
        if taps.count >= 3 {
            let intervals = zip(taps.dropFirst(), taps).map { $0.timeIntervalSince($1) }
            let avg = intervals.reduce(0, +) / Double(intervals.count)
            if avg > 0 { setBPM(Int((60.0 / avg).rounded())) }
        }
    }

    // MARK: Pattern editing

    func setSig(_ s: TimeSig) {
        var c = cfg
        c.sig = s
        c.grouping = 0
        c.resetPattern()
        cfg = c
    }

    func cycleGrouping() {
        var c = cfg
        c.grouping = (c.grouping + 1) % max(1, c.sig.groupings.count)
        c.resetPattern(keepSubs: true)
        cfg = c
    }

    func cycleSub(_ b: Int) {
        guard b < cfg.subs.count, b < cfg.states.count else { return }
        var c = cfg
        let n = c.subs[b] % 4 + 1          // 1 -> 2 -> 3 -> 4 -> 1
        c.subs[b] = n
        var row = Array(c.states[b].prefix(n))
        while row.count < n { row.append(.normal) }
        c.states[b] = row
        cfg = c
    }

    func toggleNote(_ b: Int, _ k: Int, _ s: NoteState) {
        guard b < cfg.states.count, k < cfg.states[b].count else { return }
        var c = cfg
        c.states[b][k] = (c.states[b][k] == s) ? .normal : s
        cfg = c
    }

    // MARK: Auto

    func toggleAuto() { cfg.autoOn.toggle() }
    func toggleAutoMode() { cfg.autoMode = (cfg.autoMode == .mute) ? .add : .mute }

    // MARK: Presets

    func savePreset(_ i: Int) {
        guard (0..<9).contains(i) else { return }
        currentSlot = i
        presets[i] = cfg.preset
        saveJSON(presets, "presets")
    }

    func loadPreset(_ i: Int) {
        guard (0..<9).contains(i) else { return }
        currentSlot = i
        guard let p = presets[i] else { return }
        guard p.subs.count == p.sig.beats, p.states.count == p.sig.beats else { return }
        var c = cfg
        c.bpm = p.bpm; c.sig = p.sig; c.grouping = p.grouping
        c.subs = p.subs; c.states = p.states
        cfg = c
    }

    func cyclePreset() {
        for off in 1...9 {
            let i = (currentSlot + off) % 9
            if presets[i] != nil { loadPreset(i); return }
        }
    }

    func cycleSound() {
        let all = SoundKind.allCases
        if let i = all.firstIndex(of: sound) { sound = all[(i + 1) % all.count] }
    }

    func changeVolume(_ d: Double) { volume = min(1, max(0, volume + d)) }

    // MARK: Keyboard

    func handleKey(_ e: NSEvent) -> Bool {
        if let kw = NSApp.keyWindow, let w = window, kw !== w { return false }
        if NSApp.keyWindow?.firstResponder is NSText { return false }   // typing a number

        let mods = e.modifierFlags.intersection([.command, .shift, .option, .control])
        let cmd = (mods == [.command])
        let plain = mods.isEmpty
        guard cmd || plain else { return false }

        let once: (() -> Void) -> Bool = { f in
            if !e.isARepeat { f() }
            return true
        }

        switch e.keyCode {
        case 126: if cmd { changeVolume(0.05) } else { nudge(1) }; return true
        case 125: if cmd { changeVolume(-0.05) } else { nudge(-1) }; return true
        case 124: if cmd { changeVolume(0.05) } else { step5(1) }; return true
        case 123: if cmd { changeVolume(-0.05) } else { step5(-1) }; return true
        case 36, 76: return plain ? once { self.tempoEditTrigger += 1 } : false
        case 51: return plain ? once { self.tap() } : false
        case 49: return plain ? once { self.toggle() } : false
        default: break
        }

        let ch = e.charactersIgnoringModifiers?.lowercased() ?? ""
        if let d = Int(ch), (1...9).contains(d) {
            return once { cmd ? self.savePreset(d - 1) : self.loadPreset(d - 1) }
        }
        switch ch {
        case "t": return once { cmd ? self.floatOnTop.toggle() : self.tap() }
        case "a" where plain: return once { self.toggleAuto() }
        case "m" where plain: return once { self.muted.toggle() }
        case "o" where plain: return once { self.cycleSound() }
        case "s" where plain: return once { self.savePreset(self.currentSlot) }
        case "p" where plain: return once { self.cyclePreset() }
        case "l" where plain: return once { self.lightEnabled.toggle() }
        case "f" where plain: return once { self.window?.toggleFullScreen(nil) }
        default: return false
        }
    }
}
