import XCTest
import UIKit
import ImageIO
import CoreGraphics
import CryptoKit

/// Drives the shipping editor and system document controllers with a supplied fixture.
final class VisualExportEndToEndTests: NativeUIHarness {
    private var run: [String: Any] = [:]
    private var config: [String: String] = [:]

    override func setUpWithError() throws {
        try super.setUpWithError()
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "Run", withExtension: "json"))
        run = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        config = try XCTUnwrap(run["visualExport"] as? [String: String], "Runner must supply the visualExport contract")
    }

    /// Read-only diagnostic. Run with the runner's alpha flag; this does not qualify export.
    func testVisualExportFieldCensus() throws {
        let fixtures = try XCTUnwrap(run["fixtures"] as? [String: String])
        let fixture = URL(fileURLWithPath: try XCTUnwrap(fixtures["visualExport"]))
        let digest = SHA256.hash(data: try Data(contentsOf: fixture)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, try setting("fixtureSHA256"))
        XCUIDevice.shared.system.open(fixture)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let report = identified("editor-import-report")
        let reportForFixture = report.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@", fixture.lastPathComponent))
        let imported = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            report.exists && reportForFixture.firstMatch.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [imported], timeout: 20), .completed)
        try closeImportReportIfPresented()
        _ = try observedProjectXML(attachmentName: "visual-census-before.circ")
        try openExport()
        screenshot("visual-export-panel-before-input")
        attach(app.debugDescription, name: "visual-export-panel-before-input-tree")
        let sheet = identified("editor-tool-sheet")
        let labels = try values("visual_export_filename")
        var observations = ["alpha diagnostic only", "appFrame=\(app.frame)", "sheetFrame=\(sheet.frame)"]
        for (scope, container) in [("app", app as XCUIElement), ("sheet", sheet)] {
            let fields = container.textFields.allElementsBoundByIndex + container.textViews.allElementsBoundByIndex
            observations.append("\(scope) editableCount=\(fields.count) canonicalLabelMatchCount=\(matching(labels, within: container).count)")
            for field in fields {
                let frame = field.frame
                let valid = frame.width > 0 && frame.height > 0 && frame.minX.isFinite && frame.minY.isFinite
                let inWindow = valid && !frame.intersection(app.frame).isNull
                let hittable = inWindow ? field.isHittable : false
                observations.append("scope=\(scope) role=\(field.elementType.rawValue) id=\(field.identifier) label=\(field.label) value=\(String(describing: field.value)) placeholder=\(String(describing: field.placeholderValue)) frame=\(frame) hittable=\(hittable)")
            }
        }
        attach(observations.joined(separator: "\n"), name: "visual-export-panel-field-census")
        screenshot("visual-export-panel-after-readonly-census")
    }

    func testEditorExportsPixelsAndCancelsNativeDelivery() throws {
        let fixtures = try XCTUnwrap(run["fixtures"] as? [String: String])
        let titles = try XCTUnwrap(run["titles"] as? [String: String])
        let fixture = URL(fileURLWithPath: try XCTUnwrap(fixtures["visualExport"]))
        let fixtureData = try Data(contentsOf: fixture)
        let digest = SHA256.hash(data: fixtureData).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, "791508747474976204e73586a944caa1b0054801d7ab3306f6a02db7033f59d1")
        XCTAssertEqual(digest, try setting("fixtureSHA256"))
        attach("Runner-reported sourcePin=\(try setting("sourcePin"))\nbuildSHA256=\(try setting("buildSHA256"))\nfixtureSHA256=\(digest)", name: "visual-export-provenance")
        XCUIDevice.shared.system.open(fixture)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        let title = try XCTUnwrap(titles["visualExport"])
        let report = identified("editor-import-report")
        let reportForFixture = report.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@", fixture.lastPathComponent))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            report.exists && reportForFixture.firstMatch.exists
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed,
                       "The import report must identify the requested unique fixture file")
        try closeImportReportIfPresented()
        XCTAssertTrue(matching([title]).firstMatch.waitForExistence(timeout: 20), "Fixture circuit must be visible")
        let before = try observedProjectXML(attachmentName: "visual-export-before.circ")
        XCTAssertTrue(before.contains("EXPORT34_IMAGE"))

        try openExport()
        try setField("visual_export_filename", "qualification-whole")
        try setField("visual_export_scale", "0.25")
        try setField("visual_export_margin", "10")
        let pngURL = try output("qualification-whole.png")
        try save(to: pngURL)
        let png = try Data(contentsOf: pngURL)
        try checkPNG(png)
        attachData(png, name: "qualification-whole.png", type: "public.png")
        screenshot("visual-png-saved")
        try closeTool()

        try selectImage()
        try openExport()
        try tapInSheet(["PDF"])
        try setField("visual_export_filename", "qualification-selection")
        try setField("visual_export_scale", "1")
        try setField("visual_export_margin", "10")
        try tapInSheet(values("visual_export_selection"))
        let pdfURL = try output("qualification-selection.pdf")
        try save(to: pdfURL)
        let pdf = try Data(contentsOf: pdfURL)
        try checkPDF(pdf)
        attachData(pdf, name: "qualification-selection.pdf", type: "com.adobe.pdf")
        screenshot("visual-pdf-saved")

        let shareBaseline = try stagedAssetDirectories()
        try tapInSheet(values("visual_export_share"))
        let shareMarker = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["AirDrop", "Copy", "Copiar", "Save to Files", "Salvar em Arquivos"])).firstMatch
        XCTAssertTrue(shareMarker.waitForExistence(timeout: 15), "The native share interface must be presented")
        let shareAsset = try observeStagedPDF(pdf, excluding: shareBaseline, name: pdfURL.lastPathComponent, action: "share")
        try cancelController(kind: "share", marker: shareMarker)
        try expectStagedAssetRemoved(shareAsset, action: "sharing")
        let printBaseline = try stagedAssetDirectories()
        try tapInSheet(values("visual_export_print"))
        let printAsset = try observeStagedPDF(pdf, excluding: printBaseline, name: pdfURL.lastPathComponent, action: "print")
        try expectPrintPreviewAndCancel()
        try expectStagedAssetRemoved(printAsset, action: "print preview")
        try closeTool()
        XCTAssertTrue(matching([title]).firstMatch.waitForExistence(timeout: 10))
        XCTAssertEqual(try observedProjectXML(attachmentName: "visual-export-after.circ"), before,
                       "Export and cancellation must preserve the editor document")
    }

    private func setting(_ name: String) throws -> String {
        let value = try XCTUnwrap(config[name], "Missing visualExport.\(name)")
        XCTAssertFalse(value.isEmpty, "Empty visualExport.\(name)")
        return value
    }

    private func openExport() throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("visual_export_title"), verticalRegion: "editor-tool-menu").tap()
        XCTAssertTrue(identified("editor-tool-sheet").waitForExistence(timeout: 5))
        XCTAssertTrue(matching(try values("visual_export_heading")).firstMatch.waitForExistence(timeout: 5))
    }

    private func tapInSheet(_ labels: [String]) throws {
        let element = try reveal(labels, verticalRegion: "editor-tool-sheet")
        XCTAssertTrue(element.isEnabled, "The requested action must be enabled: \(labels)")
        element.tap()
    }

    private func setField(_ key: String, _ value: String) throws {
        let names = try values(key)
        _ = try reveal(names, verticalRegion: "editor-tool-sheet")
        let sheet = identified("editor-tool-sheet")
        let fields = sheet.textFields.allElementsBoundByIndex + sheet.textViews.allElementsBoundByIndex
        let matches = fields.filter { field in field.exists && names.contains { nativeLabel(field.label, matches: $0) } }
        XCTAssertEqual(matches.count, 1, "One field is required for \(key)")
        let field = try XCTUnwrap(matches.first)
        try replacePresentedText(field, with: value)
        for name in ["Done", "Hide keyboard", "Concluído", "Ocultar teclado", "Return", "Retorno"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable { button.tap(); break }
        }
    }

    private func closeTool() throws {
        let sheet = identified("editor-tool-sheet")
        let close = try reveal(values("action_close"), verticalRegion: "editor-tool-sheet")
        close.tap()
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !sheet.exists }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: 10), .completed)
    }

    private func selectImage() throws {
        try tapKey("a11y_project_tools", toolbar: "editor-project-toolbar")
        try reveal(values("a11y_explorer_title"), verticalRegion: "editor-tool-menu").tap()
        try reveal(values("a11y_explorer_components"), verticalRegion: "a11y-circuit-list").tap()
        let search = identified("a11y-circuit-search")
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        try replacePresentedText(search, with: "EXPORT34_IMAGE")
        for name in ["Done", "Hide keyboard", "Concluído", "Ocultar teclado", "Return", "Retorno"] {
            let button = app.keyboards.buttons[name]
            if button.exists && button.isHittable { button.tap(); break }
        }
        let labels = try values("a11y_explorer_select").map { $0.replacingOccurrences(of: "{name}", with: "EXPORT34_IMAGE") }
        let item = try reveal(labels, verticalRegion: "a11y-circuit-list")
        XCTAssertTrue(item.identifier.hasPrefix("component:"))
        item.tap()
        XCTAssertTrue(item.isSelected, "Select only the fixture image")
        try tapKey("action_close")
    }

    private func output(_ name: String) throws -> URL {
        let directory = URL(fileURLWithPath: try setting("outputDirectory"), isDirectory: true)
        let url = directory.appendingPathComponent(name)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "Output must not pre-exist")
        return url
    }

    private func save(to output: URL) throws {
        let documents = URL(fileURLWithPath: try XCTUnwrap(run["documents"] as? String), isDirectory: true)
        XCTAssertEqual(documents.standardizedFileURL, output.deletingLastPathComponent().standardizedFileURL,
                       "The Files navigator and artifact observer must use the same fresh run directory")
        let runID = try XCTUnwrap(run["run_id"] as? String)
        try tapInSheet(values("visual_export_save"))
        let destination = try NativeFilesDestinationNavigator(harness: self, documentsDirectory: documents, runID: runID)
            .completeExport(expectedFilename: output.lastPathComponent)
        XCTAssertEqual(destination.standardizedFileURL, output.standardizedFileURL)
        let written = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            FileManager.default.fileExists(atPath: output.path)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [written], timeout: 20), .completed, "The selected destination must contain the exported file")
    }

    private func checkPNG(_ data: Data) throws {
        XCTAssertEqual(Array(data.prefix(8)), [137, 80, 78, 71, 13, 10, 26, 10])
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(image.width, 1508)
        XCTAssertEqual(image.height, 908)
        let pixels = try rgba(image)
        func pixel(_ x: Int, _ y: Int) -> (Int, Int, Int, Int) {
            let offset = (y * image.width + x) * 4
            return (Int(pixels[offset]), Int(pixels[offset + 1]), Int(pixels[offset + 2]), Int(pixels[offset + 3]))
        }
        let samples: [(Int, Int, [Int])] = [(0, 0, [255, 255, 255, 255]),
            (32, 56, [255, 0, 0, 255]), (55, 56, [127, 255, 127, 255])]
        for (x, y, expected) in samples {
            let actual = pixel(x, y)
            for (channel, value) in zip([actual.0, actual.1, actual.2, actual.3], expected) {
                XCTAssertLessThanOrEqual(abs(channel - value), 2, "Unexpected pixel at (\(x), \(y))")
            }
        }
        try checkWholeDrawingInk(pixels, width: image.width, height: image.height)
    }

    private struct ExportContract: Decodable {
        let whole: Whole
        struct Whole: Decodable { let png: PNG }
        struct PNG: Decodable { let ink: Ink }
        struct Ink: Decodable {
            let backgroundRGB: [Int]
            let channelDifference: Int
            let regions: [Region]
        }
        struct Region: Decodable {
            let id: String
            let kind: String
            let worldBounds: [Int]
            let pixelBounds: [Int]
            let minimumInkPixels: Int
        }
    }

    private func checkWholeDrawingInk(_ pixels: [UInt8], width: Int, height: Int) throws {
        let contractData = Data(try setting("contractJSON").utf8)
        let digest = SHA256.hash(data: contractData).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, try setting("contractSHA256"), "The staged contract must match the runner's recorded source")
        let ink = try JSONDecoder().decode(ExportContract.self, from: contractData).whole.png.ink
        XCTAssertEqual(ink.backgroundRGB, [255, 255, 255])
        XCTAssertEqual(ink.regions.count, 78, "The fixture contract covers both texts, all 72 symbols and four frame edges")
        guard ink.backgroundRGB.count == 3, ink.channelDifference >= 0,
              !ink.regions.isEmpty, pixels.count == width * height * 4 else { throw HarnessFailure.missingControl }
        var observations: [String] = []
        for region in ink.regions {
            let b = region.pixelBounds
            guard b.count == 4, 0 <= b[0], b[0] < b[2], b[2] <= width,
                  0 <= b[1], b[1] < b[3], b[3] <= height, region.minimumInkPixels > 0 else {
                XCTFail("Invalid ink region \(region.id)")
                throw HarnessFailure.missingControl
            }
            var count = 0
            for y in b[1]..<b[3] {
                for x in b[0]..<b[2] {
                    let offset = (y * width + x) * 4
                    if (0..<3).contains(where: { abs(Int(pixels[offset + $0]) - ink.backgroundRGB[$0]) > ink.channelDifference }) {
                        count += 1
                    }
                }
            }
            observations.append("\(region.id) kind=\(region.kind) worldBounds=\(region.worldBounds) pixelBounds=\(b) ink=\(count) minimum=\(region.minimumInkPixels)")
            XCTAssertGreaterThanOrEqual(count, region.minimumInkPixels, "The whole drawing omits or clips \(region.id)")
        }
        attach(observations.joined(separator: "\n"), name: "visual-export-whole-ink-regions")
        attach(digest, name: "visual-export-contract-sha256")
    }

    private func stagedAssetRoot() throws -> URL {
        let root = URL(fileURLWithPath: try setting("assetCacheDirectory"), isDirectory: true).standardizedFileURL
        let documents = URL(fileURLWithPath: try XCTUnwrap(run["documents"] as? String), isDirectory: true).standardizedFileURL
        let container = documents.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        XCTAssertEqual(root, container.appendingPathComponent("Library/Caches/LogisimAssetExports", isDirectory: true))
        guard root == container.appendingPathComponent("Library/Caches/LogisimAssetExports", isDirectory: true) else {
            throw HarnessFailure.missingControl
        }
        return root
    }

    private func stagedAssetDirectories() throws -> Set<String> {
        let root = try stagedAssetRoot()
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        return Set(try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey])
            .filter { try $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true }
            .map(\.lastPathComponent))
    }

    private func observeStagedPDF(_ expected: Data, excluding baseline: Set<String>, name: String, action: String) throws -> URL {
        let root = try stagedAssetRoot()
        var candidates: [URL] = []
        let prepared = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            guard let directories = try? self.stagedAssetDirectories() else { return false }
            candidates = directories.subtracting(baseline).filter {
                UUID(uuidString: $0) != nil
            }.map { root.appendingPathComponent($0, isDirectory: true).appendingPathComponent(name) }
                .filter { FileManager.default.fileExists(atPath: $0.path) }
            return candidates.count == 1
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [prepared], timeout: 15), .completed,
                       "The \(action) adapter must prepare one new asset with the requested filename")
        guard candidates.count == 1 else { throw HarnessFailure.missingControl }
        let asset = candidates[0]
        let data = try Data(contentsOf: asset)
        XCTAssertEqual(data, expected, "The native \(action) asset differs from the verified selected PDF")
        try checkPDF(data)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        attach("action=\(action)\npath=\(asset.path)\nbytes=\(data.count)\nsha256=\(digest)\ncomparison=saved PDF\nrecipient delivery=not attempted", name: "visual-export-\(action)-asset")
        attachData(data, name: "qualification-\(action)-staged.pdf", type: "com.adobe.pdf")
        return asset
    }

    private func expectStagedAssetRemoved(_ asset: URL, action: String) throws {
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !FileManager.default.fileExists(atPath: asset.path)
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 5), .completed,
                       "Cancelling \(action) must remove its temporary asset")
    }

    private func checkPDF(_ data: Data) throws {
        XCTAssertEqual(String(decoding: data.prefix(5), as: UTF8.self), "%PDF-")
        let provider = try XCTUnwrap(CGDataProvider(data: data as CFData))
        let document = try XCTUnwrap(CGPDFDocument(provider), "Native CoreGraphics must open the exported PDF")
        XCTAssertEqual(document.numberOfPages, 1)
        let page = try XCTUnwrap(document.page(at: 1))
        let bounds = page.getBoxRect(.mediaBox)
        XCTAssertEqual(bounds.width, 595, accuracy: 0.1)
        XCTAssertEqual(bounds.height, 842, accuracy: 0.1)
        XCTAssertNotNil(data.range(of: Data("/Subtype /Image /Width 140 /Height 100".utf8)), "Selection raster dimensions must be 140 × 100")
        var bytes = [UInt8](repeating: 255, count: 595 * 842 * 4)
        try bytes.withUnsafeMutableBytes { raw in
            let context = try XCTUnwrap(CGContext(data: raw.baseAddress, width: 595, height: 842, bitsPerComponent: 8, bytesPerRow: 595 * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.setFillColor(UIColor.white.cgColor); context.fill(CGRect(x: 0, y: 0, width: 595, height: 842))
            context.drawPDFPage(page)
        }
        var reds = 0, greens = 0
        for offset in stride(from: 0, to: bytes.count, by: 4) {
            let r = Int(bytes[offset]), g = Int(bytes[offset + 1]), b = Int(bytes[offset + 2])
            if r > 220 && g < 40 && b < 40 { reds += 1 }
            if g > 220 && (90...165).contains(r) && (90...165).contains(b) { greens += 1 }
        }
        XCTAssertGreaterThan(reds, 100, "The PDF page must contain the red image region")
        XCTAssertGreaterThan(greens, 100, "The PDF page must contain the green image region composited over white")
    }

    private func rgba(_ image: CGImage) throws -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
        try bytes.withUnsafeMutableBytes { raw in
            let context = try XCTUnwrap(CGContext(data: raw.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8,
                bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        return bytes
    }

    private func expectPrintPreviewAndCancel() throws {
        let printer = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["Printer", "Impressora", "Print Options", "Opções de Impressão", "Opções de impressão"])).firstMatch
        XCTAssertTrue(printer.waitForExistence(timeout: 15), "A native print interface must be presented")
        var observed: (CGImage, Int, Int, CGRect)?
        let previewReady = XCTNSPredicateExpectation(predicate: NSPredicate { [unowned self] _, _ in
            guard printer.exists else { return false }
            observed = try? self.findColoredPrintPreview()
            return observed != nil
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [previewReady], timeout: 20), .completed,
                       "The presented native print interface must expose a preview containing the exported image colors")
        let evidence = try XCTUnwrap(observed)
        let crop = XCTAttachment(image: UIImage(cgImage: evidence.0))
        crop.name = "visual-native-print-preview-crop"
        crop.lifetime = .keepAlways
        add(crop)
        attach("previewFrame=\(evidence.3), redPixels=\(evidence.1), greenPixels=\(evidence.2)", name: "visual-native-print-preview-pixels")
        screenshot("visual-print-preview")
        try cancelController(kind: "print", marker: printer)
    }

    private func findColoredPrintPreview() throws -> (CGImage, Int, Int, CGRect)? {
        let screen = XCUIScreen.main.screenshot().image
        let image = try XCTUnwrap(screen.cgImage)
        let sx = CGFloat(image.width) / screen.size.width
        let sy = CGFloat(image.height) / screen.size.height
        for element in app.images.allElementsBoundByIndex where element.exists {
            let frame = element.frame.intersection(app.frame)
            guard !frame.isNull, frame.width >= 80, frame.height >= 80 else { continue }
            let area = CGRect(x: frame.minX * sx, y: frame.minY * sy, width: frame.width * sx, height: frame.height * sy).integral
                .intersection(CGRect(x: 0, y: 0, width: image.width, height: image.height))
            guard !area.isNull, area.width > 0, area.height > 0, let crop = image.cropping(to: area) else { continue }
            let bytes = try rgba(crop)
            var red = 0, green = 0
            for offset in stride(from: 0, to: bytes.count, by: 4) {
                let r = Int(bytes[offset]), g = Int(bytes[offset + 1]), b = Int(bytes[offset + 2])
                if r > 210 && g < 65 && b < 65 { red += 1 }
                if g > 190 && (75...180).contains(r) && (75...180).contains(b) { green += 1 }
            }
            if red >= 15 && green >= 15 { return (crop, red, green, frame) }
        }
        return nil
    }

    private func cancelController(kind: String, marker: XCUIElement) throws {
        screenshot("visual-\(kind)-presented")
        attach(app.debugDescription, name: "visual-\(kind)-tree")
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["Cancel", "Cancelar", "Close", "Fechar"])).firstMatch
        if cancel.waitForExistence(timeout: 10) {
            cancel.tap()
        } else {
            let outside = identified("PopoverDismissRegion")
            XCTAssertTrue(outside.exists && UIDevice.current.userInterfaceIdiom == .pad, "A native cancellation control must exist")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.97)).tap()
        }
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !marker.exists }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed, "Native interface must be dismissed")
        XCTAssertTrue(identified("editor-tool-sheet").waitForExistence(timeout: 10), "The editor tool must remain available after cancellation")
    }

    private func attachData(_ data: Data, name: String, type: String) {
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: type)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
