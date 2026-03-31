import SwiftUI

struct StatusMenuView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("DeskLink Server")
                .font(.headline)

            Divider()

            Label("Status: Disconnected", systemImage: "circle.fill")
                .foregroundColor(.secondary)

            Divider()

            Button("Start Server") {
                // Will be connected to use case
            }

            Button("Settings...") {
                // Will open settings window
            }

            Divider()

            Button("Quit DeskLink") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
        }
        .padding()
        .frame(width: 240)
    }
}
