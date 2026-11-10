import Foundation
import CoreGraphics

    private func safePopoverDismissPoint(content: CGRect, dismissal: CGRect, popover: CGRect, controls: [CGRect]) -> CGPoint? {
        func valid(_ rect: CGRect) -> Bool {
            !rect.isNull && !rect.isInfinite && rect.minX.isFinite && rect.minY.isFinite && rect.width.isFinite && rect.height.isFinite &&
                rect.width > 0 && rect.height > 0
        }
        guard valid(content), valid(dismissal), valid(popover), controls.allSatisfy(valid) else { return nil }
        let area = content.intersection(dismissal).insetBy(dx: 4, dy: 4)
        guard valid(area) else { return nil }
        let obstacles = ([popover] + controls).filter(valid).map { $0.insetBy(dx: -4, dy: -4).intersection(area) }
            .filter { !$0.isNull }
        let xs = Array(Set([area.minX, area.maxX] + obstacles.flatMap { [$0.minX, $0.maxX] })).sorted()
        let ys = Array(Set([area.minY, area.maxY] + obstacles.flatMap { [$0.minY, $0.maxY] })).sorted()
        var best: (point: CGPoint, area: CGFloat)?
        for x in 0..<(xs.count - 1) {
            for y in 0..<(ys.count - 1) {
                let cell = CGRect(x: xs[x], y: ys[y], width: xs[x + 1] - xs[x], height: ys[y + 1] - ys[y])
                guard cell.width >= 8, cell.height >= 8 else { continue }
                let point = CGPoint(x: cell.midX, y: cell.midY)
                guard !obstacles.contains(where: { $0.contains(point) }) else { continue }
                let size = cell.width * cell.height
                if size > (best?.area ?? 0) { best = (point, size) }
            }
        }
        return best?.point
    }


let content=CGRect(x: 109.0, y: 550.0, width: 616.0, height: 628.0)
let dismissal=CGRect(x: 0.0, y: 0.0, width: 834.0, height: 1210.0)
let popover=CGRect(x: 229.0, y: 425.0, width: 375.0, height: 359.0)
let controls=[CGRect(x: 121.0, y: 650.0, width: 62.5, height: 48.0),CGRect(x: 191.5, y: 650.0, width: 61.5, height: 48.0),CGRect(x: 261.0, y: 650.0, width: 60.0, height: 48.0),CGRect(x: 121.0, y: 746.0, width: 592.0, height: 64.0),CGRect(x: 121.0, y: 818.0, width: 292.0, height: 64.0),CGRect(x: 421.0, y: 818.0, width: 292.0, height: 64.0),CGRect(x: 121.0, y: 890.0, width: 592.0, height: 48.0),CGRect(x: 121.0, y: 946.0, width: 592.0, height: 46.0),CGRect(x: 121.0, y: 1014.0, width: 592.0, height: 48.0),CGRect(x: 121.0, y: 1070.0, width: 592.0, height: 48.0),CGRect(x: 121.0, y: 1126.0, width: 592.0, height: 48.0),CGRect(x: 662.0, y: 502.0, width: 63.0, height: 48.0)]

func verify(_ point: CGPoint?, _ body: CGRect, _ region: CGRect, _ popup: CGRect, _ blocked: [CGRect]) -> CGPoint {
    guard let point else { fatalError("Expected a safe point") }
    precondition(body.contains(point) && region.contains(point))
    precondition(!popup.insetBy(dx: -4, dy: -4).contains(point))
    precondition(blocked.allSatisfy { !$0.insetBy(dx: -4, dy: -4).contains(point) })
    return point
}
let point=verify(safePopoverDismissPoint(content: content, dismissal: dismissal, popover: popover, controls: controls), content, dismissal, popover, controls)
let shift=CGVector(dx: 1100, dy: 700)
let moved=verify(safePopoverDismissPoint(content: content.offsetBy(dx: shift.dx,dy: shift.dy), dismissal: dismissal.offsetBy(dx: shift.dx,dy: shift.dy), popover: popover.offsetBy(dx: shift.dx,dy: shift.dy), controls: controls.map { $0.offsetBy(dx: shift.dx,dy: shift.dy) }), content.offsetBy(dx: shift.dx,dy: shift.dy), dismissal.offsetBy(dx: shift.dx,dy: shift.dy), popover.offsetBy(dx: shift.dx,dy: shift.dy), controls.map { $0.offsetBy(dx: shift.dx,dy: shift.dy) })
precondition(moved == CGPoint(x: point.x+shift.dx,y: point.y+shift.dy))
precondition(safePopoverDismissPoint(content: content,dismissal: dismissal,popover: content,controls: []) == nil)
precondition(safePopoverDismissPoint(content: content,dismissal: dismissal,popover: popover,controls: [content]) == nil)
precondition(safePopoverDismissPoint(content: content,dismissal: .null,popover: popover,controls: controls) == nil)
precondition(safePopoverDismissPoint(content: content,dismissal: dismissal,popover: .null,controls: controls) == nil)
precondition(safePopoverDismissPoint(content: content,dismissal: dismissal,popover: popover,controls: [.zero]) == nil)
precondition(safePopoverDismissPoint(content: CGRect(x:0,y:0,width:6,height:6),dismissal: dismissal,popover: popover,controls: []) == nil)
precondition(safePopoverDismissPoint(content: .infinite,dismissal: dismissal,popover: popover,controls: []) == nil)
precondition(safePopoverDismissPoint(content: content,dismissal: CGRect(x:2000,y:2000,width:100,height:100),popover: popover,controls: []) == nil)
print("PASS 10 geometry cases; captured point=\(point), translated point=\(moved)")
