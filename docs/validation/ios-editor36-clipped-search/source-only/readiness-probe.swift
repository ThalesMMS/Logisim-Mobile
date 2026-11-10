import Foundation
import CoreGraphics
    func explorerHeaderReadiness(frame: CGRect, viewport: CGRect, canonical: Bool, enabled: Bool)
        -> (fullyVisible: Bool, canSkipSelected: Bool)? {
        guard enabled,
              [frame.origin.x, frame.origin.y, frame.size.width, frame.size.height].allSatisfy({ $0.isFinite }),
              frame.size.width > 0 && frame.size.height > 0 else { return nil }
        let matchesControlRequirements = canonical && frame.size.width >= 48 && frame.size.height >= 48
        let contained = viewport.contains(frame)
        guard !contained || matchesControlRequirements else { return nil }
        return (contained && matchesControlRequirements, matchesControlRequirements)
    }


let viewport = CGRect(x: 24.0, y: 182.0, width: 342.0, height: 604.0)
let clipped = CGRect(x: 24.0, y: 158.0, width: 342.0, height: 24.0)
let visible = CGRect(x: 24, y: 262, width: 342, height: 88)
let labels = ["Find a component, terminal, or identifier", "Buscar componente, terminal ou identificação"]
var checks = 0
func expect(_ name: String, _ frame: CGRect, _ label: String, _ enabled: Bool,
            _ expected: (Bool, Bool)?) {
    let result = explorerHeaderReadiness(frame: frame, viewport: viewport,
                                        canonical: labels.contains(label), enabled: enabled)
    if let expected {
        precondition(result?.fullyVisible == expected.0 && result?.canSkipSelected == expected.1, name)
    } else { precondition(result == nil, name) }
    checks += 1
}
expect("literal R3 clipped Search only prepares", clipped, "", true, (false, false))
expect("revealed canonical Search at 48 points admits", CGRect(x:24,y:262,width:342,height:48), labels[0], true, (true, true))
expect("visible empty label rejects", visible, "", true, nil)
expect("visible wrong label rejects", visible, "wrong label", true, nil)
expect("visible undersized canonical field rejects", CGRect(x:24,y:262,width:342,height:24), labels[0], true, nil)
expect("disabled clipped target rejects", clipped, "", false, nil)
expect("NaN origin rejects", CGRect(x:CGFloat.nan,y:158,width:342,height:24), "", true, nil)
expect("infinite width rejects", CGRect(x:24,y:158,width:CGFloat.infinity,height:24), "", true, nil)
expect("negative raw width rejects", CGRect(x:24,y:158,width:-342,height:24), "", true, nil)
expect("zero height rejects", CGRect(x:24,y:158,width:342,height:0), "", true, nil)
expect("canonical offscreen selection eligibility preserved", CGRect(x:24,y:120,width:135,height:48), labels[0], true, (false, true))
expect("offscreen wrong label cannot skip", CGRect(x:24,y:120,width:342,height:48), "wrong label", true, (false, false))
expect("offscreen undersized canonical target cannot skip", clipped, labels[0], true, (false, false))
expect("partial clipped target only prepares", CGRect(x:24,y:170,width:342,height:24), "", true, (false, false))
expect("minimum full target admits", CGRect(x:24,y:262,width:48,height:48), labels[0], true, (true, true))
expect("contained narrow target rejects", CGRect(x:24,y:262,width:44,height:48), labels[0], true, nil)
expect("disabled visible target rejects", visible, labels[0], false, nil)
print("\(checks) readiness cases passed; no native execution")
