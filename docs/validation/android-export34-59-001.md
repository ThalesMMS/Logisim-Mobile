# Android mounted visual export attempt

**FAIL. Issue #34 remains unqualified.** One run used the real editor and the frozen `59d5606b` APK on emulator-5556. The full receipt is [android-export34-59-001.json](android-export34-59-001.json).

The editor imported the shared fixture with three circuits, 78 main-circuit components and four frame wires. It exposed the complete document through Copy XML. The mounted export tool accepted the fresh filename, scale `0.25`, margin `10`, selection disabled and white background. Save drawing was invoked.

While waiting for the native Create Document controller, the separate accessibility snapshot helper reported `IllegalStateException: Accessibility child is unavailable at index 7`. The runner failed immediately. Its first failure screenshot shows rendering in progress; the final failure screenshot and hierarchy show the native Downloads picker with `export34-b7514741c0ce.png` and the SAVE button. Thus the picker did open during collection. The error does not establish a renderer hang or invalid PNG.

The scenario did not commit native SAVE or inspect saved PNG bytes, ink regions, selected PDF, sharing or printing. It did not reach the final document comparison. No second run or observer-budget change was made. The complete failing helper result was not retained by the existing observer; its literal error and Python traceback are retained.

The reset barrier was present in harness `bc98f934`. There were no pre-reset isolated-app task IDs, so this run does not prove the nonempty-task wait branch. Concurrent portable-project work used emulator-5558; this receipt makes no performance claim. After terminal exit1, the executor explicitly returned emulator-5556 to its owner and performed no further ADB operation or gesture.

Screenshots, UI trees, initial editor XML, logcat, manifest and checksums are retained under [artifacts/android-export34-59-001](artifacts/android-export34-59-001/).
