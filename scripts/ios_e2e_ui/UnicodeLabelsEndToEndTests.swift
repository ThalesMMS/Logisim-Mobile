import XCTest
import UIKit

/// iOS counterpart of the Android `unicode` scenario (`unicode_labels` in scripts/e2e-android.py, #321 and #330).
/// The system opens a project whose circuit and pin label Evolution d58a61e0 renames; the exported XML must carry the
/// names Evolution's generateValidVHDLLabel gives them, with Mobile's deterministic suffix, and the pin control must be
/// found by its new label.
final class UnicodeLabelsEndToEndTests: NativeUIHarness {
    private struct RunConfiguration: Decodable {
        let run_id: String
        let documents: String
    }

    static let circuitName = "Visão → \u{202B}اختبار\u{202C}"
    static let pinLabel = "E2E — ligação longa → entrada / \u{202B}مرحبا\u{202C}"
    static let renamedCircuit = "Vis_o_a6e3837c"
    static let renamedLabel = "E2E_liga_o_longa_entrada_dab08e64"

    func testUnicodeAndDirectionalNamesAreRenamedAsEvolutionDoes() throws {
        orient(.portrait)
        let resource = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        let run = try JSONDecoder().decode(RunConfiguration.self, from: Data(contentsOf: resource))
        // The same document the Android scenario builds with ElementTree.
        let source = """
            <?xml version='1.0' encoding='utf-8'?>
            <project version="1.0"><lib name="0" desc="#Wiring" /><main name="\(Self.circuitName)" />\
            <circuit name="\(Self.circuitName)"><comp lib="0" loc="(100,100)" name="Pin">\
            <a name="label" val="\(Self.pinLabel)" /></comp></circuit></project>
            """
        let filename = "unicode-labels-\(run.run_id).circ"
        let url = URL(fileURLWithPath: run.documents, isDirectory: true).appendingPathComponent(filename)
        try Data(source.utf8).write(to: url)
        let written = try UnicodeProjectNames.read(Data(contentsOf: url))
        XCTAssertEqual(written.circuits, [Self.circuitName])
        XCTAssertEqual(written.labels, [Self.pinLabel])

        XCUIDevice.shared.system.open(url)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let opened = try fileOpenedStatus(filename)
        let report = identified("editor-import-report")
        let admitted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in opened.exists || report.exists }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [admitted], timeout: 20), .completed, "\(filename) must open from the system")
        record("unicode-opened")
        try closeImportReportIfPresented()

        let xml = try observedProjectXML(attachmentName: "unicode-labels.circ")
        let exported = try UnicodeProjectNames.read(Data(xml.utf8))
        attach("circuits=\(exported.circuits) main=\(exported.main ?? "nil") labels=\(exported.labels)",
               name: "unicode-exported-names")
        XCTAssertEqual(exported.circuits, [Self.renamedCircuit], "The circuit name was not renamed as Evolution does")
        XCTAssertEqual(exported.main, Self.renamedCircuit, "The main circuit did not follow the rename")
        XCTAssertEqual(exported.labels, [Self.renamedLabel], "The label was not renamed as Evolution does")

        let panel = identified("editor-signal-panel")
        if !panel.waitForExistence(timeout: 3) {
            try tapKey("signals_heading", toolbar: "editor-command-toolbar")
        }
        XCTAssertTrue(panel.waitForExistence(timeout: 10), "The Signals panel must be shown")
        let names = try localized("a11y_toggle_input", ["name": Self.renamedLabel, "value": "0"])
        let toggle = try reveal(names, verticalRegion: "editor-signal-panel")
        XCTAssertTrue(toggle.exists && toggle.frame.width > 0 && toggle.frame.height > 0,
                      "The pin control must be found by its new label")
        attach("label=\(toggle.label) identifier=\(toggle.identifier) frame=\(toggle.frame)", name: "unicode-pin-control")
        let oldNames = try localized("a11y_toggle_input", ["name": Self.pinLabel, "value": "0"])
        XCTAssertFalse(matching(oldNames).firstMatch.exists, "No control may keep the old label")
        record("unicode-pin-control")
    }

    private func localized(_ key: String, _ arguments: [String: String]) throws -> [String] {
        try values(key).map { text in
            arguments.reduce(text) { $0.replacingOccurrences(of: "{\($1.key)}", with: $1.value) }
        }
    }

    private func record(_ name: String) {
        screenshot(name)
        attach(app.debugDescription, name: name + "-tree")
    }
}

/// Circuit names, the main circuit and the component labels of a .circ, read with XMLParser.
private final class UnicodeProjectNames: NSObject, XMLParserDelegate {
    private(set) var circuits: [String] = []
    private(set) var main: String?
    private(set) var labels: [String] = []
    private var depth = 0
    private var inComponent = false

    static func read(_ data: Data) throws -> UnicodeProjectNames {
        let names = UnicodeProjectNames()
        let parser = XMLParser(data: data)
        parser.delegate = names
        guard parser.parse() else { throw parser.parserError ?? NSError(domain: "UnicodeProjectNames", code: 1) }
        return names
    }

    func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String] = [:]) {
        depth += 1
        switch element {
        case "main" where depth == 2: main = attributes["name"]
        case "circuit" where depth == 2: if let name = attributes["name"] { circuits.append(name) }
        case "comp": inComponent = true
        case "a" where inComponent && attributes["name"] == "label": if let value = attributes["val"] { labels.append(value) }
        default: break
        }
    }

    func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
        depth -= 1
        if element == "comp" { inComponent = false }
    }
}
