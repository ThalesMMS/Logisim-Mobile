import Foundation
import CoreGraphics
    func explorerSearchGeometry(region: CGRect, window: CGRect, inputFrames: [CGRect])
        -> (viewport: CGRect, start: CGPoint, end: CGPoint)? {
        func finite(_ frame: CGRect) -> Bool {
            [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height].allSatisfy { $0.isFinite } &&
                frame.size.width > 0 && frame.size.height > 0
        }
        guard finite(region), finite(window) else { return nil }
        var viewport = region.intersection(window)
        for input in inputFrames {
            guard [input.origin.x, input.origin.y, input.size.width, input.size.height].allSatisfy({ $0.isFinite }),
                  input.size.width >= 0 && input.size.height >= 0 else { return nil }
            if input.size.width == 0 || input.size.height == 0 { continue }
            let obstacle = input.intersection(window)
            if !obstacle.isNull && obstacle.intersects(viewport) {
                viewport = viewport.intersection(CGRect(x: window.minX, y: window.minY,
                    width: window.width, height: max(0, obstacle.minY - window.minY)))
            }
        }
        guard finite(viewport), viewport.width >= 48, viewport.height >= 48 else { return nil }
        let distance = min(CGFloat(96), viewport.height / 4)
        return (viewport, CGPoint(x: viewport.midX, y: viewport.midY - distance / 2),
                CGPoint(x: viewport.midX, y: viewport.midY + distance / 2))
    }


func check(_ condition: @autoclosure () -> Bool, _ label: String) { precondition(condition(), label) }
let region = CGRect(x: 12, y: 182, width: 366, height: 604)
let window = CGRect(x: 0, y: 0, width: 390, height: 844)
let base = explorerSearchGeometry(region: region, window: window, inputFrames: [])!
check(base.viewport == region && base.start == CGPoint(x:195,y:436) && base.end == CGPoint(x:195,y:532), "observed viewport produces a short backward drag")
check(base.end.y - base.start.y == 96 && base.end.y - base.start.y < 588, "new drag does not reuse the failed 588-point traverse")
let keyboard = explorerSearchGeometry(region: region, window: window, inputFrames:[CGRect(x:0,y:553,width:390,height:291)])!
check(keyboard.viewport == CGRect(x:12,y:182,width:366,height:371) && keyboard.start == CGPoint(x:195,y:321.125) && keyboard.end == CGPoint(x:195,y:413.875), "keyboard clips the observed viewport")
let assistant = explorerSearchGeometry(region: region, window: window, inputFrames:[CGRect(x:0,y:509,width:390,height:44)])!
check(assistant.viewport == CGRect(x:12,y:182,width:366,height:327) && assistant.end.y < 509, "assistant alone clips the viewport")
let floating = explorerSearchGeometry(region: region, window: window, inputFrames:[CGRect(x:20,y:470,width:120,height:160)])!
check(floating.viewport == CGRect(x:12,y:182,width:366,height:288) && floating.end == CGPoint(x:195,y:362), "input obstacle need not cover the center line")
let above = explorerSearchGeometry(region: region, window: window, inputFrames:[CGRect(x:0,y:50,width:390,height:40)])!
check(above.viewport == region, "nonoverlapping native input does not cover the list")
check(explorerSearchGeometry(region: region, window: window, inputFrames:[CGRect(x:0,y:150,width:390,height:200)]) == nil, "fully covered list is rejected")
check(explorerSearchGeometry(region:CGRect(x:12,y:182,width:40,height:604), window:window, inputFrames:[]) == nil, "narrow region is rejected")
check(explorerSearchGeometry(region:CGRect(x:12,y:182,width:366,height:44), window:window, inputFrames:[]) == nil, "short region is rejected")
check(explorerSearchGeometry(region:CGRect(x:1000,y:1000,width:366,height:604), window:window, inputFrames:[]) == nil, "region outside app is rejected")
let shifted = explorerSearchGeometry(region:CGRect(x:112,y:382,width:366,height:604), window:CGRect(x:100,y:200,width:390,height:844), inputFrames:[])!
check(shifted.start == CGPoint(x:295,y:636) && shifted.end == CGPoint(x:295,y:732), "coordinates derive from the actual window")
check(explorerSearchGeometry(region:region, window:window, inputFrames:[CGRect(x:CGFloat.nan,y:400,width:390,height:100)]) == nil, "unknown input geometry is rejected")
check(explorerSearchGeometry(region:region, window:window, inputFrames:[CGRect(x:0,y:400,width:CGFloat.infinity,height:100)]) == nil, "infinite input geometry is rejected")
check(explorerSearchGeometry(region:region, window:window, inputFrames:[CGRect(x:300,y:470,width:-120,height:160)]) == nil, "negative raw input width is rejected")
check(explorerSearchGeometry(region:region, window:window, inputFrames:[CGRect(x:20,y:630,width:120,height:-160)]) == nil, "negative raw input height is rejected")
print("15 geometry cases passed; no native execution")
