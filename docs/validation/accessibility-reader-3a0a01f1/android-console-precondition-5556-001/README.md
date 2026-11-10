# Console reader unit stopped before contact

Status: `ABORTED_PRECONDITION_NO_CONTACT`. Root authorized one exclusive emulator-5556 unit using controller `8dcede427cd594f65704a9827276a19506c464ea` and the existing 3a product. The operator enabled speech display and TalkBack through public Settings, then stopped when the frozen binding checker rejected both actual reader-on dumps. No lease was created, no passive getevent monitor started, and no console contact was sent. This result qualifies neither transport nor reader navigation. Issue #28 stays open.

## The binding precondition failed

The real Bound record contains `eventTypes=TYPES_ALL_MASK`. The frozen helper expects `eventTypes[...]`, without an equals sign. Its synthetic positive fixture used that incorrect grammar. Both dumps produced `unknown_bound_format: Bound record differs from the source-derived profile`. The error belongs to this validation helper. It does not establish an app or TalkBack defect.

The separate AMS checker accepts the exact published system-client binding before and after the preserve-only SDK capture. Both observations retain service process 29352, system_server PID 582, the same ServiceRecord, BinderProxy, and ConnectionRecord. The accessibility dumps each show one spoken, touch-capable Bound record and `touchExplorationEnabled=true`. These are separate non-atomic observations. They do not prove callback completion, present Binder liveness, or a processed reader gesture.

The complete raw dumps and the original rejection remain in this directory and its archive. The prepared sources in `../android-console-one-gesture-prepared-001/` remain unchanged. A corrected parser needs official source review, a failing expectation over these exact bytes, and negative controls before a separate runtime authorization.

## The baseline was incomplete

The SDK tree `captures002/011-baseline-a-reader-on.xml` has exactly one accessibility-focused node at `[21,137][222,263]`. Its child text is **Projects**. Screenshot 012 shows the corresponding green reader ring. The app is paused at **Steps 0**, **Saved · revision 34**, and **320%** zoom. The proposed `(300,800)` through `(700,800)` region is visibly blank grid. The operator did not move or force focus or choose a successor from XML order.

A matching reader-produced Projects caption was not demonstrated. The silent private video also reveals a notification prompt after screenshot 010 and before the public app launch. Frame 055 shows **Allow Android Accessibility Suite to send you notifications?**, with the ring around **Allow**. No notification button was pressed during this unit. Bringing the app forward does not prove that the prompt was dismissed. The final notification grant and public flags remained unchanged and denied. This asynchronous prompt and the missing caption are additional setup gaps, even if the binding parser is corrected offline.

## Restoration completed

The operator returned through public Settings, switched TalkBack off, confirmed **Turn off**, and restored **Display speech output** off. Screenshot 022 shows speech display off, **ERROR** logging, **Explore by touch** on, and **Handle gestures in TalkBack** on. The other displayed developer toggles retain their initial values. Screenshot 015 shows **Use TalkBack** and its shortcut off. Single-tap activation remained off in screenshot 019.

The final read-only checks at 12:30:21 UTC record accessibility disabled, an empty enabled-service setting, touch exploration false, and an empty Bound list. Font scale, rotation, IME, display size, and density match the initial public values. Notifications remain denied with `USER_SET|USER_SENSITIVE_WHEN_GRANTED`. All three installed APK hashes match the approved app, test, and snapshot helper hashes. These checks make no claim about byte-identical opaque preferences or tutorial state.

The app returned to the foreground in portrait, paused at revision 34 with the same component geometry, zoom, and visible signal values. SDK snapshots 001 and 023 differ in one text node: the transient status changed from **Circuit Sem título.** to **Operation cancelled.** No document-edit or input-value action was issued. This is a visible-state comparison, not an internal document-byte oracle.

Observer session 6428 ended with exit 0 after the final screenshot and explicit finish operation. Screenrecord session 52513 ended with exit 0. No contact or monitor was pending, and emulator-5556 was explicitly returned to Root.

## Retained evidence

`evidence.tar.gz` preserves the raw command receipts, screenshots, SDK trees and helper receipts, original service dumps, restoration checks, and two video frames. `evidence-manifest.json` records each file hash. `result.json` records the unqualified gates. The first observer invocation ended at stdin EOF without performing an operation; the subsequent PTY session is the one recorded above.

The complete screen video stays private at the local path in `private-video.json`, outside the archive and Git. Its SHA-256 is `a3c195f54956b90425740c49d7205731309c1c2f63ac4a009aaf9633cc6fe7fb`. FFprobe reports one 1080×2400 video stream, two data streams, no audio stream, and duration 185.619978 seconds. Speech-display subtitles, when observed, would be reader text rather than an audio recording. No caption or spoken-output pass is claimed here.

No build, installation, fixture import, document edit, second gesture, alternative transport, CLI UIAutomator capture, or fallback occurred in this unit. No further device or reader operation is authorized by this receipt.
