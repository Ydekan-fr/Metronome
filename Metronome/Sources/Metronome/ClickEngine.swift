import AVFoundation
import AudioToolbox
import CoreAudio
import IOKit.pwr_mgt

// MARK: - Sounds

enum SoundKind: String, CaseIterable, Identifiable, Codable {
    case click = "Click"
    case wood = "Wood block"
    case beep = "Beep"
    case cowbell = "Cowbell"
    case hihat = "Hi-hat"
    case clave = "Clave"

    var id: String { rawValue }

    func render(accent: Bool, sampleRate sr: Double) -> [Float] {
        switch self {
        case .click:
            return synth([(accent ? 1800 : 1200, 1)], dur: 0.05, decay: 90, sr: sr)
        case .wood:
            let f = accent ? 1100.0 : 800.0
            return synth([(f, 1), (f * 2.4, 0.4)], dur: 0.09, decay: 60, sr: sr)
        case .beep:
            return synth([(accent ? 1320 : 880, 1)], dur: 0.10, decay: 25, sr: sr)
        case .cowbell:
            let f = accent ? 700.0 : 540.0
            return synth([(f, 1), (f * 1.504, 0.8)], dur: 0.20, decay: 22, sr: sr)
        case .hihat:
            return synth([], dur: accent ? 0.09 : 0.045, decay: accent ? 45 : 90, noise: 1, sr: sr)
        case .clave:
            let f = accent ? 2900.0 : 2300.0
            return synth([(f, 1), (f * 1.6, 0.3)], dur: 0.06, decay: 70, sr: sr)
        }
    }
}

private func synth(_ partials: [(Double, Double)], dur: Double, decay: Double,
                   noise: Double = 0, sr: Double) -> [Float] {
    let n = Int(dur * sr)
    var out = [Float](repeating: 0, count: n)
    var prev = 0.0
    var peak: Float = 0
    for i in 0..<n {
        let t = Double(i) / sr
        var s = 0.0
        for (f, a) in partials { s += a * sin(2 * Double.pi * f * t) }
        if noise > 0 {
            let r = Double.random(in: -1...1)
            s += noise * (r - prev) * 0.5   // crude high-pass
            prev = r
        }
        let env = exp(-t * decay) * min(1, t / 0.0008)
        let v = Float(s * env)
        out[i] = v
        peak = max(peak, abs(v))
    }
    let fade = max(1, min(n / 8, 256))
    let scale: Float = peak > 0 ? 0.9 / peak : 1
    for i in 0..<n {
        var v = out[i] * scale
        if i >= n - fade { v *= Float(n - i) / Float(fade) }
        out[i] = v
    }
    return out
}

// MARK: - Events sent to the UI

struct ClickEvent {
    var sample: Int64
    var beat: Int
    var sub: Int
    var state: NoteState
    var muted: Bool
}

// MARK: - Engine

final class ClickEngine {
    let sampleRate = 48000.0
    var onTempoChange: ((Int) -> Void)?

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let format: AVAudioFormat
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "metronome.scheduler", qos: .userInteractive)
    private var timer: DispatchSourceTimer?
    private var running = false

    // Shared state (protected by `lock`)
    private var cfg = Config()
    private var normalClick: [Float] = []
    private var accentClick: [Float] = []
    private var events: [ClickEvent] = []

    // Scheduler state (touched on `queue` only)
    private var scheduledUntil: Int64 = 0
    private var exactPos = 0.0
    private var beatIndex = 0
    private var barsPlayed = 0
    private var cycleBar = 0
    private var barsSinceAdd = 0
    private var lastAddSample: Int64 = 0
    private var mutedBar = false

    init() {
        format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        setSound(.click)
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
                                               object: engine, queue: nil) { [weak self] _ in
            self?.queue.async { self?.handleConfigChange() }
        }
    }

    // MARK: Public API

    func update(_ c: Config) {
        lock.lock(); cfg = c; lock.unlock()
    }

    func setSound(_ s: SoundKind) {
        let normal = s.render(accent: false, sampleRate: sampleRate).map { $0 * 0.7 }
        let accent = s.render(accent: true, sampleRate: sampleRate)
        lock.lock(); normalClick = normal; accentClick = accent; lock.unlock()
    }

    func setVolume(_ v: Float) { engine.mainMixerNode.outputVolume = v }
    func setPan(_ p: Float) { player.pan = p }

    func start() { queue.sync { startInternal() } }
    func stop() { queue.sync { stopInternal() } }

    func currentSample() -> Int64 {
        guard let nt = player.lastRenderTime, let pt = player.playerTime(forNodeTime: nt) else { return 0 }
        return pt.sampleTime
    }

    func drainEvents(upTo s: Int64) -> [ClickEvent] {
        lock.lock(); defer { lock.unlock() }
        let due = Array(events.prefix(while: { $0.sample <= s }))
        events.removeFirst(due.count)
        return due
    }

    func setOutputDevice(_ id: AudioDeviceID?) {
        queue.sync {
            let was = running
            if was { stopInternal() }
            engine.stop()
            var dev: AudioDeviceID = id ?? defaultOutputDeviceID()
            if let au = engine.outputNode.audioUnit {
                let st = AudioUnitSetProperty(au, kAudioOutputUnitProperty_CurrentDevice,
                                              kAudioUnitScope_Global, 0, &dev,
                                              UInt32(MemoryLayout<AudioDeviceID>.size))
                if st != noErr { NSLog("Could not set output device: \(st)") }
            }
            if was { startInternal() }
        }
    }

    // MARK: Internals

    private func handleConfigChange() {
        if running { stopInternal(); startInternal() }
    }

    private func startInternal() {
        if running { return }
        if !engine.isRunning {
            engine.prepare()
            do { try engine.start() } catch { NSLog("Audio engine error: \(error)"); return }
        }
        lock.lock(); events.removeAll(); lock.unlock()
        scheduledUntil = 0; exactPos = 0; beatIndex = 0; barsPlayed = 0
        cycleBar = 0; barsSinceAdd = 0; lastAddSample = 0; mutedBar = false
        player.stop(); player.reset()
        for _ in 0..<2 { scheduleBeat() }
        player.play()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(10), repeating: .milliseconds(10), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in self?.refill() }
        t.resume()
        timer = t
        running = true
    }

    private func stopInternal() {
        timer?.cancel(); timer = nil
        player.stop(); player.reset()
        engine.stop()
        lock.lock(); events.removeAll(); lock.unlock()
        running = false
    }

    private func refill() {
        guard running else { return }
        let now = currentSample()
        let ahead = Int64(0.15 * sampleRate)
        var count = 0
        while scheduledUntil - now < ahead && count < 8 {
            scheduleBeat()
            count += 1
        }
    }

    private func bump(_ c: Config) -> Config {
        var c = c
        let nb = min(300, max(20, c.bpm + c.addBpm))
        if nb != c.bpm {
            c.bpm = nb
            lock.lock(); cfg.bpm = nb; lock.unlock()
            DispatchQueue.main.async { [weak self] in self?.onTempoChange?(nb) }
        }
        return c
    }

    /// Renders one beat (all its subdivisions) into a PCM buffer and queues it.
    private func scheduleBeat() {
        lock.lock()
        var c = cfg
        let normal = normalClick
        let accent = accentClick
        lock.unlock()

        if beatIndex >= c.sig.beats { beatIndex = 0 }

        // Bar-level logic (auto mode)
        if beatIndex == 0 {
            if c.autoOn {
                switch c.autoMode {
                case .mute:
                    let total = max(1, c.playBars + c.muteBars)
                    mutedBar = (cycleBar % total) >= c.playBars
                    cycleBar += 1
                case .add:
                    mutedBar = false
                    if c.addUnit == .bar {
                        if barsPlayed > 0 { barsSinceAdd += 1 }
                        if barsSinceAdd >= max(1, c.addEvery) {
                            barsSinceAdd = 0
                            c = bump(c)
                        }
                    }
                }
            } else {
                mutedBar = false; cycleBar = 0; barsSinceAdd = 0
            }
            barsPlayed += 1
        }
        if c.autoOn && c.autoMode == .add && c.addUnit == .sec {
            if Double(scheduledUntil - lastAddSample) >= Double(max(1, c.addEvery)) * sampleRate {
                lastAddSample = scheduledUntil
                c = bump(c)
            }
        } else {
            lastAddSample = scheduledUntil
        }

        // Length of this beat (fractional accumulation avoids drift)
        exactPos += 60.0 / Double(max(1, c.bpm)) * sampleRate
        let endSample = Int64(exactPos.rounded())
        let n = max(1, Int(endSample - scheduledUntil))

        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)) else { return }
        buf.frameLength = AVAudioFrameCount(n)
        let ptr = buf.floatChannelData![0]
        for i in 0..<n { ptr[i] = 0 }

        let subs = beatIndex < c.subs.count ? max(1, c.subs[beatIndex]) : 1
        var newEvents: [ClickEvent] = []
        for k in 0..<subs {
            let st = c.state(beatIndex, k)
            let off = Int(Double(k) / Double(subs) * Double(n))
            newEvents.append(ClickEvent(sample: scheduledUntil + Int64(off), beat: beatIndex,
                                        sub: k, state: st, muted: mutedBar))
            if st == .rest || mutedBar { continue }
            let src = (st == .accent) ? accent : normal
            let gain: Float = (k == 0) ? 1.0 : 0.8
            let len = min(src.count, n - off)
            if len > 0 {
                for i in 0..<len { ptr[off + i] += src[i] * gain }
            }
        }
        lock.lock(); events.append(contentsOf: newEvents); lock.unlock()

        player.scheduleBuffer(buf, completionHandler: nil)
        scheduledUntil = endSample
        beatIndex += 1
    }
}

// MARK: - Output devices

struct AudioOutputDevice: Identifiable, Hashable {
    let id: AudioDeviceID
    let name: String
}

func defaultOutputDeviceID() -> AudioDeviceID {
    var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                          mScope: kAudioObjectPropertyScopeGlobal,
                                          mElement: kAudioObjectPropertyElementMain)
    var id = AudioDeviceID(0)
    var size = UInt32(MemoryLayout<AudioDeviceID>.size)
    AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id)
    return id
}

func listOutputDevices() -> [AudioOutputDevice] {
    var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices,
                                          mScope: kAudioObjectPropertyScopeGlobal,
                                          mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    let sys = AudioObjectID(kAudioObjectSystemObject)
    guard AudioObjectGetPropertyDataSize(sys, &addr, 0, nil, &size) == noErr else { return [] }
    let count = Int(size) / MemoryLayout<AudioDeviceID>.size
    var ids = [AudioDeviceID](repeating: 0, count: count)
    guard AudioObjectGetPropertyData(sys, &addr, 0, nil, &size, &ids) == noErr else { return [] }

    return ids.compactMap { id -> AudioOutputDevice? in
        var cAddr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration,
                                               mScope: kAudioObjectPropertyScopeOutput,
                                               mElement: kAudioObjectPropertyElementMain)
        var s: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &cAddr, 0, nil, &s) == noErr, s > 0 else { return nil }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(s), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(id, &cAddr, 0, nil, &s, raw) == noErr else { return nil }
        let abl = UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self))
        let channels = abl.reduce(0) { $0 + Int($1.mNumberChannels) }
        guard channels > 0 else { return nil }

        var nameAddr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName,
                                                  mScope: kAudioObjectPropertyScopeGlobal,
                                                  mElement: kAudioObjectPropertyElementMain)
        var cfName: Unmanaged<CFString>?
        var ns = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &nameAddr, 0, nil, &ns, &cfName) == noErr,
              let name = cfName?.takeRetainedValue() else { return nil }
        return AudioOutputDevice(id: id, name: name as String)
    }
}

// MARK: - Sleep prevention

final class PowerManager {
    private var systemID = IOPMAssertionID(0)
    private var displayID = IOPMAssertionID(0)

    func setSystem(_ on: Bool) {
        if on && systemID == 0 {
            IOPMAssertionCreateWithName(kIOPMAssertionTypePreventUserIdleSystemSleep as CFString,
                                        IOPMAssertionLevel(kIOPMAssertionLevelOn),
                                        "Metronome is playing" as CFString, &systemID)
        } else if !on && systemID != 0 {
            IOPMAssertionRelease(systemID); systemID = 0
        }
    }

    func setDisplay(_ on: Bool) {
        if on && displayID == 0 {
            IOPMAssertionCreateWithName(kIOPMAssertionTypePreventUserIdleDisplaySleep as CFString,
                                        IOPMAssertionLevel(kIOPMAssertionLevelOn),
                                        "Metronome keeps display awake" as CFString, &displayID)
        } else if !on && displayID != 0 {
            IOPMAssertionRelease(displayID); displayID = 0
        }
    }

    deinit { setSystem(false); setDisplay(false) }
}
