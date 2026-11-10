# iOS mounted visual export attempt

**FAIL. Issue #34 remains unqualified.** The selected case failed in 224.988 seconds with one failure and no unexpected failures. The receipt pins the Beta application tree `0b661a20…`, source `b9bd71ae…`, canonical harness `89c94446…`, test Swift `74d896ae…` and shared contract `4724c4e0…`. See [ios-export34-beta-001.json](ios-export34-beta-001.json).

The real editor imported three circuits, 78 main components and four frame wires, exported its XML and opened Export drawing. The video at 37 seconds visibly shows File name and its initial value. The test did not locate that field as a reachable scoped match and continued scrolling. No complete accessibility tree of the panel before input was retained, so its precise native label, viewport membership and hittability remain unproven.

The last logged gesture starts at application point `(417,8)` and drags to `(417,760)` at test time 83.62. The final screenshot shows Notification Center and its clock. The diagnostic stackshot marks the application suspended throughout 124 samples; its main thread is in `CFRunLoop → mach_msg`, with its last execution near test time 87.824. The runner then waits in `setField → reveal → waitForExistence`. These observations do not demonstrate a renderer deadlock or contemporary Metal/GPU failure.

The native text replacement helper was not reached. Save, PNG pixels and all 78 regions, selection PDF, sharing, print preview and final document comparison remain **NOT_RUN**. The case ended with the literal failure `Failed to get matching snapshots: Timed out while evaluating UI query` at `NativeUIHarness.swift:145`.

The test teardown terminated app 18832 and restored portrait. The executor explicitly returned the B25 Simulator and shared test cache to `ios_files`, then performed no Simulator action or gesture. A private alpha-only census now captures the panel tree, fields and frames before attempting any scroll; it passed SDK/XCTest typechecking but has not run natively. No shared helper, product, timeout or coordinate fallback changed.

Selected original artifacts and video frames are retained under [artifacts/ios-export34-beta-001](artifacts/ios-export34-beta-001/). The complete 88 MB screen recording and 14 MB stackshot remain in the local original output, with their paths and SHA-256 recorded in the receipt. The retained excerpts identify their original line numbers and source hash.
