import Foundation
import CoreGraphics

enum DocumentWindowAnchorError: Error {
    case invalidFilename
    case invalidLabels
}

struct DocumentWindowAnchor {
    let filename: String
    let originLabels: [String]
    private let labels: Set<String>

    init(filename: String, openedLabels: [String], originLabels: [String]) throws {
        let basename = (filename as NSString).lastPathComponent
        let uuidPattern = #"(?<![A-Za-z0-9])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![A-Za-z0-9])"#
        let stem = (filename as NSString).deletingPathExtension
        guard !filename.isEmpty, filename == basename, !filename.contains("\\"),
              !filename.contains("\n"), !filename.contains("\r"),
              stem.range(of: uuidPattern, options: .regularExpression) != nil else {
            throw DocumentWindowAnchorError.invalidFilename
        }
        let expected = openedLabels + originLabels
        guard !openedLabels.isEmpty, !originLabels.isEmpty,
              expected.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.contains(filename) }) else {
            throw DocumentWindowAnchorError.invalidLabels
        }
        self.filename = filename
        self.originLabels = originLabels
        labels = Set(expected)
    }

    func matches(label: String) -> Bool { labels.contains(label) }
    func matchesOrigin(label: String) -> Bool { originLabels.contains(label) }
}

struct DocumentWindowObservation {
    let exists: Bool
    let isWindow: Bool
    let frame: CGRect
    let applicationFrame: CGRect
    let labels: [String]
    let childCount: Int
}

enum DocumentWindowRules {
    static func uniqueIndex(anchor: DocumentWindowAnchor, observations: [DocumentWindowObservation], requiringOrigin: Bool = false) -> Int? {
        let matches = observations.indices.filter { index in
            isLiveWindow(observations[index]) && observations[index].labels.contains(where: anchor.matches)
        }
        guard matches.count == 1 else { return nil }
        let index = matches[0]
        if requiringOrigin && !observations[index].labels.contains(where: anchor.matchesOrigin) { return nil }
        return index
    }

    // These observations establish a usable AX tree, not key-window or hit-test ownership.
    static func isLiveWindow(_ observation: DocumentWindowObservation) -> Bool {
        guard observation.exists, observation.isWindow, observation.childCount > 0,
              hasFiniteArea(observation.frame), hasFiniteArea(observation.applicationFrame) else { return false }
        return hasFiniteArea(observation.frame.intersection(observation.applicationFrame))
    }

    static func canObserveEditorWithoutModal(windowLive: Bool, editorPresent: Bool, modalPresent: Bool) -> Bool {
        windowLive && editorPresent && !modalPresent
    }

    static func canCompleteReturn(windowLive: Bool, originVerified: Bool, editorPresent: Bool, modalPresent: Bool) -> Bool {
        originVerified && canObserveEditorWithoutModal(windowLive: windowLive, editorPresent: editorPresent, modalPresent: modalPresent)
    }

    private static func hasFiniteArea(_ frame: CGRect) -> Bool {
        !frame.isNull && !frame.isInfinite && frame.size.width > 0 && frame.size.height > 0 &&
            [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height,
             frame.minX, frame.minY, frame.maxX, frame.maxY].allSatisfy(\.isFinite)
    }
}
