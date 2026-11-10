import Foundation
import CoreGraphics
import Darwin
    func explorerSearchGeometry(region: CGRect, window: CGRect, inputFrames: [CGRect])
        -> (viewport: CGRect, start: CGPoint, end: CGPoint)? {
        func finite(_ frame: CGRect) -> Bool {
            [frame.minX, frame.minY, frame.width, frame.height].allSatisfy { $0.isFinite } &&
                frame.width > 0 && frame.height > 0
        }
        guard finite(region), finite(window) else { return nil }
        var viewport = region.intersection(window)
        for input in inputFrames {
            guard [input.minX, input.minY, input.width, input.height].allSatisfy({ $0.isFinite }),
                  input.width >= 0 && input.height >= 0 else { return nil }
            if input.width == 0 || input.height == 0 { continue }
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


let region=CGRect(x:12,y:182,width:366,height:604)
let window=CGRect(x:0,y:0,width:390,height:844)
let input=CGRect(x:300,y:470,width:-120,height:160)
if explorerSearchGeometry(region:region,window:window,inputFrames:[input]) != nil {
 print("FAIL: expected nil for negative raw input size; received a geometry")
 exit(1)
}
print("PASS: negative raw input size rejected")
