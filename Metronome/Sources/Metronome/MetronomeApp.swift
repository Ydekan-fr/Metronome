import SwiftUI
import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

@main
struct MetronomeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = MetronomeModel()

    init() {
        NSWindow.allowsAutomaticWindowTabbing = false
    }

    var body: some Scene {
        WindowGroup("Metronome") {
            ContentView()
                .environmentObject(model)
        }
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {}

            CommandMenu("Metronome") {
                Button(model.isPlaying ? "Stop" : "Start") { model.toggle() }

                Divider()

                Picker("Sound", selection: $model.sound) {
                    ForEach(SoundKind.allCases) { Text($0.rawValue).tag($0) }
                }

                Menu("Pan") {
                    Button("Left") { model.pan = -1 }
                    Button("Center") { model.pan = 0 }
                    Button("Right") { model.pan = 1 }
                }

                Toggle("Mute", isOn: $model.muted)

                Divider()

                Toggle("Click Light", isOn: $model.lightEnabled)
                Toggle("Keep Display Awake", isOn: $model.keepDisplayAwake)
                Toggle("Float on Top", isOn: $model.floatOnTop)
            }
        }

        Settings {
            SettingsView()
                .environmentObject(model)
        }
    }
}
