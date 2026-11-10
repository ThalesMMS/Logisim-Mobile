import UIKit
import UniformTypeIdentifiers

/// A test-only receiver. Content enters the text view through the stock Paste control after a tap.
@main
final class ClipboardReceiverApp: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Clipboard Receiver", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = ClipboardReceiverSceneDelegate.self
        return configuration
    }
}

final class ClipboardReceiverSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = ClipboardReceiverViewController()
        self.window = window
        window.makeKeyAndVisible()
    }
}

final class ClipboardReceiverViewController: UIViewController {
    private let projectText = UITextView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        projectText.accessibilityIdentifier = "clipboard-project-text"
        projectText.accessibilityLabel = "Pasted project XML"
        projectText.isEditable = true
        projectText.isSelectable = true
        projectText.allowsEditingTextAttributes = false
        projectText.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
        projectText.autocapitalizationType = .none
        projectText.autocorrectionType = .no
        projectText.spellCheckingType = .no
        projectText.smartQuotesType = .no
        projectText.smartDashesType = .no
        projectText.smartInsertDeleteType = .no
        projectText.pasteConfiguration = UIPasteConfiguration(acceptableTypeIdentifiers: [UTType.plainText.identifier])
        projectText.text = ""
        projectText.layer.borderColor = UIColor.separator.cgColor
        projectText.layer.borderWidth = 1
        projectText.layer.cornerRadius = 8
        projectText.translatesAutoresizingMaskIntoConstraints = false

        let configuration = UIPasteControl.Configuration()
        configuration.displayMode = .iconAndLabel
        let paste = UIPasteControl(configuration: configuration)
        paste.accessibilityIdentifier = "clipboard-paste"
        paste.target = projectText
        paste.translatesAutoresizingMaskIntoConstraints = false

        let clear = UIButton(type: .system)
        clear.setTitle("Clear", for: .normal)
        clear.accessibilityIdentifier = "clipboard-clear"
        clear.addAction(UIAction { [weak self] _ in
            self?.projectText.text = ""
        }, for: .touchUpInside)
        clear.translatesAutoresizingMaskIntoConstraints = false

        let controls = UIStackView(arrangedSubviews: [paste, clear])
        controls.axis = .horizontal
        controls.alignment = .fill
        controls.spacing = 16
        controls.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(controls)
        view.addSubview(projectText)
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            controls.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 16),
            controls.topAnchor.constraint(equalTo: safe.topAnchor, constant: 12),
            controls.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -16),
            paste.heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
            paste.widthAnchor.constraint(greaterThanOrEqualToConstant: 140),
            clear.heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
            clear.widthAnchor.constraint(greaterThanOrEqualToConstant: 88),
            projectText.topAnchor.constraint(equalTo: controls.bottomAnchor, constant: 12),
            projectText.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 16),
            projectText.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -16),
            projectText.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor, constant: -12),
        ])
    }
}
