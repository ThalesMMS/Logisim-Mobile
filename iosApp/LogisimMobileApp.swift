import SwiftUI
import ComposeApp

@main
struct LogisimMobileApp: App {
    var body: some Scene {
        WindowGroup {
            DocumentWindow()
        }
    }
}

private struct DocumentWindow: View {
    @State private var incomingDocument: IncomingDocument?
    @SceneStorage("externalDocumentSessionID") private var sessionID = UUID().uuidString

    var body: some View {
        CircuitView(sessionID: sessionID, incomingDocument: incomingDocument)
            .ignoresSafeArea()
            .onOpenURL { incomingDocument = IncomingDocument(url: $0) }
            // Deliver documents opened from Files or another app to an existing window. Without this,
            // iPadOS creates a new scene per Open With, and windows accumulate behind the active one.
            .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
    }
}

private struct IncomingDocument: Identifiable {
    let id = UUID()
    let url: URL
}

private struct CircuitView: UIViewControllerRepresentable {
    let sessionID: String
    let incomingDocument: IncomingDocument?

    final class Coordinator {
        let session: IosDocumentSession
        var lastDocumentID: UUID?

        init(sessionID: String) { session = IosDocumentSession(sessionId: sessionID) }
    }

    func makeCoordinator() -> Coordinator { Coordinator(sessionID: sessionID) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.session.makeViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        guard let request = incomingDocument,
              request.id != context.coordinator.lastDocumentID else { return }
        context.coordinator.lastDocumentID = request.id
        context.coordinator.session.openURL(url: request.url)
    }
}
