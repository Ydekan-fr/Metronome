import SwiftUI
import AppKit

// MARK: - Helpers

/// Number you can scrub vertically (drag) or click to type.
struct ScrubNumber: View {
    @Binding var value: Int
    var range: ClosedRange<Int>
    var font: Font
    var pxPerStep: CGFloat = 6
    var signed = false
    var trigger = 0
    var width: CGFloat? = nil

    @State private var startValue: Int? = nil
    @State private var scrubbed = false
    @State private var editing = false
    @State private var text = ""
    @FocusState private var focused: Bool

    private var display: String { signed && value > 0 ? "+\(value)" : "\(value)" }
    private func clamp(_ v: Int) -> Int { min(range.upperBound, max(range.lowerBound, v)) }

    private func beginEdit() {
        text = "\(value)"
        editing = true
        DispatchQueue.main.async { focused = true }
    }

    private func commit() {
        guard editing else { return }
        editing = false
        if let v = Int(text.trimmingCharacters(in: .whitespaces)) { value = clamp(v) }
    }

    var body: some View {
        Group {
            if editing {
                TextField("", text: $text)
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.center)
                    .font(font)
                    .focused($focused)
                    .onSubmit { commit() }
                    .onExitCommand { editing = false }
                    .onChange(of: focused) { f in if !f { commit() } }
            } else {
                Text(display)
                    .font(font)
                    .monospacedDigit()
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { g in
                                if startValue == nil { startValue = value; scrubbed = false }
                                if abs(g.translation.height) > 3 { scrubbed = true }
                                if scrubbed, let s = startValue {
                                    let delta = Int((-g.translation.height / pxPerStep).rounded())
                                    value = clamp(s + delta)
                                }
                            }
                            .onEnded { _ in
                                if !scrubbed { beginEdit() }
                                startValue = nil
                            }
                    )
            }
        }
        .frame(width: width)
        .onChange(of: trigger) { _ in beginEdit() }
    }
}

/// Button that repeats while held.
struct HoldButton<Label: View>: View {
    var action: () -> Void
    @ViewBuilder var label: () -> Label
    @State private var timer: Timer?
    @State private var pressed = false

    var body: some View {
        label()
            .opacity(pressed ? 0.5 : 1)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        action()
                        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { _ in
                            timer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { _ in action() }
                        }
                    }
                    .onEnded { _ in
                        pressed = false
                        timer?.invalidate()
                        timer = nil
                    }
            )
    }
}

/// Transparent overlay that only reacts to right-clicks (left clicks pass through).
struct RightClick: NSViewRepresentable {
    var action: () -> Void

    func makeNSView(context: Context) -> NSView {
        let v = RCView()
        v.action = action
        return v
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        (nsView as? RCView)?.action = action
    }

    final class RCView: NSView {
        var action: (() -> Void)?
        override func rightMouseDown(with event: NSEvent) { action?() }
        override func hitTest(_ point: NSPoint) -> NSView? {
            NSApp.currentEvent?.type == .rightMouseDown ? super.hitTest(point) : nil
        }
    }
}

struct WindowAccessor: NSViewRepresentable {
    var onWindow: (NSWindow) -> Void
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async { if let w = v.window { onWindow(w) } }
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

// MARK: - Note icon

struct NoteIcon: View {
    let n: Int
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let hw: CGFloat = 10, hh: CGFloat = 7.5
            let headY = size.height - 10
            let topY: CGFloat = (n == 3) ? 18 : 9
            var stems: [CGFloat] = []
            for i in 0..<n {
                let cx = size.width * (CGFloat(i) + 1) / (CGFloat(n) + 1)
                let head = Path(ellipseIn: CGRect(x: cx - hw / 2, y: headY - hh / 2, width: hw, height: hh))
                ctx.fill(head, with: .color(color))
                let sx = cx + hw / 2 - 1
                stems.append(sx)
                var p = Path()
                p.move(to: CGPoint(x: sx, y: headY))
                p.addLine(to: CGPoint(x: sx, y: topY))
                ctx.stroke(p, with: .color(color), lineWidth: 1.8)
            }
            if n >= 2, let f = stems.first, let l = stems.last {
                ctx.fill(Path(CGRect(x: f, y: topY, width: l - f + 1.8, height: 3.2)), with: .color(color))
                if n == 4 {
                    ctx.fill(Path(CGRect(x: f, y: topY + 5.5, width: l - f + 1.8, height: 3.2)), with: .color(color))
                }
            }
            if n == 3 {
                ctx.draw(Text("3").font(.system(size: 10, weight: .bold, design: .rounded)).foregroundColor(color),
                         at: CGPoint(x: size.width / 2, y: 7))
            }
        }
    }
}

struct MiniButton: View {
    let symbol: String
    let on: Bool
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 8, weight: .bold))
                .frame(width: 17, height: 15)
                .foregroundStyle(on ? Color.black : Color.white.opacity(0.55))
                .background(RoundedRectangle(cornerRadius: 4).fill(on ? tint : Color.white.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Sections

struct TopBar: View {
    @EnvironmentObject var m: MetronomeModel

    private var groupingLabel: String {
        let g = m.cfg.sig.groupings
        return g[min(m.cfg.grouping, g.count - 1)].map(String.init).joined(separator: "+")
    }

    var body: some View {
        HStack(alignment: .top) {
            Circle()
                .fill(m.flash ? (m.flashAccent ? Color.orange : Color.green) : Color.white.opacity(0.08))
                .frame(width: 22, height: 22)
                .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 1))
                .opacity(m.lightEnabled ? 1 : 0.25)
                .animation(.easeOut(duration: 0.15), value: m.flash)
                .onTapGesture { m.lightEnabled.toggle() }
                .help("Click light (L)")
                .frame(width: 90, alignment: .leading)

            Spacer()

            VStack(spacing: 6) {
                Menu {
                    ForEach(TimeSig.all, id: \.self) { s in
                        Button(s.label) { m.setSig(s) }
                    }
                } label: {
                    Text(m.cfg.sig.label)
                        .font(.system(size: 32, weight: .semibold, design: .rounded))
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()

                if m.cfg.sig.groupings.count > 1 {
                    Button(groupingLabel) { m.cycleGrouping() }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help("Cycle grouping")
                }
            }

            Spacer()

            Button { m.tap() } label: {
                Text("TAP")
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Capsule().fill(Color.white.opacity(0.14)))
            }
            .buttonStyle(.plain)
            .help("Tap tempo (T / ⌫)")
            .frame(width: 90, alignment: .trailing)
        }
    }
}

struct TempoView: View {
    @EnvironmentObject var m: MetronomeModel

    var body: some View {
        VStack(spacing: 0) {
            HoldButton(action: { m.nudge(1) }) {
                Image(systemName: "chevron.up")
                    .font(.system(size: 26, weight: .bold))
                    .frame(width: 120, height: 36)
            }
            ScrubNumber(value: Binding(get: { m.cfg.bpm }, set: { m.setBPM($0) }),
                        range: 20...300,
                        font: .system(size: 110, weight: .thin, design: .rounded),
                        pxPerStep: 4,
                        trigger: m.tempoEditTrigger,
                        width: 280)
            HoldButton(action: { m.nudge(-1) }) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 26, weight: .bold))
                    .frame(width: 120, height: 36)
            }
            Text("BPM · \(tempoName(m.cfg.bpm))")
                .font(.system(size: 14, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)
                .padding(.top, 2)
        }
    }
}

struct BeatColumn: View {
    @EnvironmentObject var m: MetronomeModel
    let b: Int

    var body: some View {
        let subs = b < m.cfg.subs.count ? max(1, m.cfg.subs[b]) : 1
        let isCurrent = m.currentBeat == b
        let w = CGFloat(max(3, subs)) * 19
        let isStart = m.cfg.sig.groupStarts(m.cfg.grouping).contains(b)

        VStack(spacing: 6) {
            Button { m.cycleSub(b) } label: {
                NoteIcon(n: subs, color: isCurrent ? .orange : Color.white.opacity(0.92))
                    .frame(width: w, height: 48)
            }
            .buttonStyle(.plain)
            .overlay(RightClick { m.toggleNote(b, 0, .accent) })
            .help("Click: subdivision · Right-click: accent first note")

            HStack(spacing: 2) {
                ForEach(0..<subs, id: \.self) { k in
                    let st = m.cfg.state(b, k)
                    let live = isCurrent && m.currentSub == k
                    VStack(spacing: 3) {
                        MiniButton(symbol: "chevron.right", on: st == .accent, tint: .orange) {
                            m.toggleNote(b, k, .accent)
                        }
                        MiniButton(symbol: "pause.fill", on: st == .rest, tint: .gray) {
                            m.toggleNote(b, k, .rest)
                        }
                    }
                    .padding(1)
                    .background(RoundedRectangle(cornerRadius: 5)
                        .fill(live ? Color.orange.opacity(0.25) : Color.clear))
                }
            }

            Text("\(b + 1)")
                .font(.system(size: 11, weight: isStart ? .bold : .regular, design: .rounded))
                .foregroundStyle(isStart ? Color.orange : Color.secondary)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 10)
            .fill(isCurrent ? Color.white.opacity(0.10) : Color.white.opacity(0.04)))
    }
}

struct BeatsView: View {
    @EnvironmentObject var m: MetronomeModel

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(0..<m.cfg.sig.beats, id: \.self) { b in
                    BeatColumn(b: b)
                }
            }
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity)
        }
    }
}

struct AutoPanel: View {
    @EnvironmentObject var m: MetronomeModel

    private let small = Font.system(size: 12, weight: .semibold, design: .rounded)

    private func label(_ t: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(t)
                .font(small)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 5).fill(Color.white.opacity(0.1)))
        }
        .buttonStyle(.plain)
    }

    var body: some View {
        HStack(spacing: 8) {
            Button { m.toggleAuto() } label: {
                Text("AUTO")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(m.cfg.autoOn ? Color.black : Color.white)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Capsule().fill(m.cfg.autoOn ? Color.orange : Color.white.opacity(0.14)))
            }
            .buttonStyle(.plain)
            .help("Auto mode (A)")

            if m.cfg.autoMode == .mute {
                label("MUTE") { m.toggleAutoMode() }
                Text("play").font(small).foregroundStyle(.secondary)
                ScrubNumber(value: $m.cfg.playBars, range: 1...99, font: small, pxPerStep: 8, width: 30)
                Text("bars ·").font(small).foregroundStyle(.secondary)
                Text("mute").font(small).foregroundStyle(.secondary)
                ScrubNumber(value: $m.cfg.muteBars, range: 1...99, font: small, pxPerStep: 8, width: 30)
                Text("bars").font(small).foregroundStyle(.secondary)
            } else {
                label("ADD") { m.toggleAutoMode() }
                ScrubNumber(value: $m.cfg.addBpm, range: -50...50, font: small, pxPerStep: 8, signed: true, width: 36)
                Text("BPM every").font(small).foregroundStyle(.secondary)
                ScrubNumber(value: $m.cfg.addEvery, range: 1...999, font: small, pxPerStep: 8, width: 36)
                label(m.cfg.addUnit == .bar ? "BAR" : "SEC") {
                    m.cfg.addUnit = (m.cfg.addUnit == .bar) ? .sec : .bar
                }
            }
        }
        .opacity(m.cfg.autoOn ? 1 : 0.7)
    }
}

struct PresetsBar: View {
    @EnvironmentObject var m: MetronomeModel

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<9, id: \.self) { i in
                Button { m.loadPreset(i) } label: {
                    Text("\(i + 1)")
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .frame(width: 30, height: 26)
                        .background(RoundedRectangle(cornerRadius: 6)
                            .fill(m.presets[i] != nil ? Color.white.opacity(0.18) : Color.white.opacity(0.06)))
                        .overlay(RoundedRectangle(cornerRadius: 6)
                            .stroke(m.currentSlot == i ? Color.orange : Color.clear, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
                .overlay(RightClick { m.savePreset(i) })
                .help("Click: load · Right-click or ⌘\(i + 1): save")
            }
            Button("Save") { m.savePreset(m.currentSlot) }
                .controlSize(.small)
                .help("Save to the selected slot (S)")
        }
    }
}

struct Transport: View {
    @EnvironmentObject var m: MetronomeModel

    var body: some View {
        HStack {
            HStack(spacing: 8) {
                Button { m.muted.toggle() } label: {
                    Image(systemName: m.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .frame(width: 24)
                }
                .buttonStyle(.plain)
                .help("Mute (M)")
                Slider(value: $m.volume, in: 0...1)
                    .frame(width: 150)
            }
            .frame(width: 220, alignment: .leading)

            Spacer()

            Button { m.toggle() } label: {
                Image(systemName: m.isPlaying ? "stop.fill" : "play.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(.black)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(Color.orange))
            }
            .buttonStyle(.plain)
            .help("Start / stop (Space)")

            Spacer()

            HStack {
                Spacer()
                Button { m.window?.toggleFullScreen(nil) } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .buttonStyle(.plain)
                .help("Full screen (F)")
            }
            .frame(width: 220)
        }
    }
}

// MARK: - Main view

struct ContentView: View {
    @EnvironmentObject var m: MetronomeModel

    var body: some View {
        VStack(spacing: 16) {
            TopBar()
            TempoView()
            BeatsView()
            Spacer(minLength: 0)
            AutoPanel()
            PresetsBar()
            Transport()
        }
        .padding(20)
        .frame(minWidth: 680, minHeight: 700)
        .foregroundStyle(.white)
        .background(Color(white: 0.09))
        .preferredColorScheme(.dark)
        .background(WindowAccessor { w in m.window = w })
    }
}

struct SettingsView: View {
    @EnvironmentObject var m: MetronomeModel

    private var panLabel: String {
        if abs(m.pan) < 0.05 { return "C" }
        return m.pan < 0 ? "L\(Int(-m.pan * 100))" : "R\(Int(m.pan * 100))"
    }

    var body: some View {
        Form {
            Picker("Sound", selection: $m.sound) {
                ForEach(SoundKind.allCases) { Text($0.rawValue).tag($0) }
            }
            HStack {
                Text("Pan")
                Slider(value: $m.pan, in: -1...1)
                Text(panLabel).frame(width: 36)
            }
            Picker("Output device", selection: $m.outputDeviceName) {
                Text("System default").tag("")
                ForEach(m.outputDevices) { Text($0.name).tag($0.name) }
            }
            Toggle("Keep display awake while playing", isOn: $m.keepDisplayAwake)
            Toggle("Click light", isOn: $m.lightEnabled)
        }
        .padding(20)
        .frame(width: 440)
        .onAppear { m.refreshDevices() }
    }
}
