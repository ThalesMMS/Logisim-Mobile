import XCTest

final class Probe366Tests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "thalesmms.logisim.mobile")
    let labels = ["Select All", "Select", "Cut", "Copy", "Paste", "Look Up", "Translate", "Share…", "Replace…"]

    func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }
    func menu() -> [String] {
        let items = app.menuItems.allElementsBoundByIndex.filter { $0.exists && $0.frame.width > 0 }
        if !items.isEmpty { return items.map { $0.label } }
        let predicate = NSPredicate(format: "label IN %@", labels)
        let kb = app.keyboards.firstMatch
        let kbFrame = kb.exists ? kb.frame : .zero
        return app.buttons.matching(predicate).allElementsBoundByIndex
            .filter { $0.exists && $0.frame.width > 0 && !kbFrame.intersects($0.frame) }.map { $0.label }
    }
    func waitMenu(_ timeout: TimeInterval) -> (Bool, Double, [String]) {
        let t0 = Date()
        while Date().timeIntervalSince(t0) < timeout {
            let m = menu()
            if !m.isEmpty { return (true, Date().timeIntervalSince(t0), m) }
            usleep(150_000)
        }
        return (false, timeout, [])
    }
    func reset() {
        element("native-reset").tap(); usleep(250_000)
        element("compose-reset").tap(); usleep(450_000)
    }
    func trial(_ scope: String, _ id: String, _ kind: String, _ dx: CGFloat, _ dy: CGFloat, _ round: Int, shot: Bool) {
        reset()
        let f = element(id)
        let frameBefore = f.frame
        f.tap(); usleep(900_000)
        let preMenu = menu()
        if !preMenu.isEmpty { reset(); f.tap(); usleep(900_000) }
        f.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: dy)).press(forDuration: 1.0)
        let (ok, t, m) = waitMenu(4)
        print("PROBE366 \(scope) field=\(id) kind=\(kind) dx=\(dx) dy=\(dy) round=\(round) menu=\(ok) t=\(String(format: "%.2f", t)) items=\(m.joined(separator: "|")) pre=\(preMenu.count) frame=\(frameBefore)")
        if shot {
            let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "\(scope)-\(id)-\(kind)-\(dx)"; a.lifetime = .keepAlways; add(a)
        }
    }
    func testSingle() throws {
        continueAfterFailure = true
        app.launchEnvironment["PROBE366"] = "1"
        app.launch()
        XCTAssertTrue(element("compose-multiline").waitForExistence(timeout: 30))
        for round in 0..<2 {
            for id in ["native-field", "compose-outlined", "compose-basic", "compose-state", "native-multiline", "compose-multiline"] {
                let dy: CGFloat = id.hasSuffix("multiline") ? 0.18 : 0.5
                for dx in [CGFloat(0.12), 0.2, 0.3, 0.4] { trial("portrait", id, "glyph", dx, dy, round, shot: round == 0 && dx == 0.2) }
                trial("portrait", id, "past", 0.95, dy, round, shot: round == 0)
            }
        }
    }
    func kinds(_ scope: String, rounds: Int) {
        let dys: [String: [(String, CGFloat, CGFloat)]] = [
            "native-multiline": [("line1end", 0.95, 0.10), ("line3end", 0.95, 0.33), ("lastend", 0.95, 0.55), ("below", 0.5, 0.92)],
            "compose-multiline": [("line1end", 0.95, 0.19), ("line3end", 0.95, 0.46), ("lastend", 0.95, 0.72), ("below", 0.5, 0.93)],
        ]
        for round in 0..<rounds {
            for id in ["native-multiline", "compose-multiline"] {
                for (kind, dx, dy) in dys[id]! { trial(scope, id, kind, dx, dy, round, shot: round == 0) }
            }
        }
    }
    func testMulti() throws {
        continueAfterFailure = true
        app.launchEnvironment["PROBE366"] = "multi"
        app.launch()
        XCTAssertTrue(element("compose-multiline").waitForExistence(timeout: 30))
        kinds("portrait", rounds: 3)
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(3)
        kinds("landscape", rounds: 3)
        let harness: [(CGFloat, CGFloat)] = [(0.97, 0.6), (0.5, 0.5), (0.97, 0.88)]
        for seq in 0..<4 {
            for id in ["native-multiline", "compose-multiline"] {
                for (i, g) in harness.enumerated() { trial("landscape", id, "harness\(i)", g.0, g.1, seq, shot: seq == 0) }
            }
        }
        XCUIDevice.shared.orientation = .portrait
    }
}
