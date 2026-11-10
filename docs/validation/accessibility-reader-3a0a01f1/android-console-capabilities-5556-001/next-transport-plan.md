# One hardware gesture plan, not executed

Status: `PREPARED_NOT_AUTHORIZED`. The console/capability inventory is complete. Later source-backed discovery through `avd grpc` returned `KO: No active gRPC service`; the existing instance has no attributed controller endpoint. No controller RPC, hardware gesture or new reader attempt has run. The discovery did not read our own credential source or change UI. Emulator-5556 remains with the coordinator.

Prefer `EmulatorController.sendTouch` because it maps pixels and owns Protocol B slots. Its endpoint and compatible local authenticated client are still unidentified. Before arming a reader unit, correlate one existing endpoint to the current 5556 PID/AVD without publishing credentials. Use existing authentication only inside process memory, with no token in argv, logs, exceptions or receipts. If that cannot be done, report the missing prerequisite. Do not restart the emulator, create an unauthenticated endpoint, install a client or switch to console automatically.

The console is a possible separately approved route with known guest ranges. It is not a fallback in this plan. Its raw tuples need complete slot/tracking/pressure/SYN/release handling and side-effect-aware error handling. The source comparison is retained in `source-research.md`. The current process is headless, so a Qt/CUA window is unavailable without a separate runtime change.

## Preflight before the one gesture

Obtain an explicit exclusive lease and freeze the controller client source, SDK proto and payload for review. Revalidate the existing app/test/helper bytes and source pin. No app rebuild, install, fixture/import or SystemOpen operation belongs to this unit. Recheck the actual active touchscreen, 1080 by 2400 viewport and orientation 0. The recorded device is `/dev/input/event1`, `virtio_input_multi_touch_1`, mapped to display 0; do not reuse that mapping if the getter result has changed.

Record the initial public reader, speech display, notification, font, rotation, IME and verbosity states. Configure speech display only while the reader is off, then enable TalkBack through public Settings. Preserve ERROR, shortcuts, gesture mappings and all other controls. An unexpected prompt outside the approved plan stops setup.

Publicly launch the existing paused app. Start a bounded video covering the baseline, complete gesture and post-state. Use only the non-suppressing SDK observer for app trees. Immediately before and after each SDK capture, retain the actual TalkBack bound-service component and `touchExplorationEnabled=true`, as well as the public enabled-service setting. Missing live evidence stops the unit. A setting string alone is insufficient.

Require exactly one settled reader-focused Projects container and baseline reader-produced speech matching Projects. Retain the complete tree and screenshot, then freeze the observable target candidates before arming. Do not force focus programmatically or derive a guaranteed TalkBack traversal order from XML child order.

## Fixed contact

Use display 0, one stable logical identifier 28 and pressure 64. The identifier is not a kernel slot number: the public sender maps it to a free internal slot among 0 through 9, even though the guest advertises slots 0 through 10. Its tracking ID follows that internal slot. The map is not namespaced by client, so exclusive ownership and no existing active contact with that logical identifier are preconditions. Send one point per request at recorded monotonic intervals: `(300,800)`, `(350,800)`, `(400,800)`, `(450,800)`, `(500,800)`, `(550,800)`, `(600,800)`, `(650,800)`, `(700,800)`, nominally 20 ms apart. Send the release at the final position with the same identifier and pressure zero, nominally 180 ms after the first point. Record actual request/response times; the RPC acknowledgment proves scheduling only.

Do not place every sample in one repeated-touch request, which would lack the planned time separation. Keep the default contact expiration setting, but do not rely on waiting for release. The source checks expiration only when another event is processed; it is not an independent timer. A `finally` block must issue the single pressure-zero release even if an intermediate call fails after possibly scheduling input. The release itself has a deadline. A failed release keeps the lane reserved for recovery and is reported before another control action. No second swipe, activation or dynamic transport retry is allowed.

A bounded passive `getevent` trace on the already identified device, if separately approved with this unit, can prove the kernel saw the actual slot/tracking/position/SYN/release sequence. Correlate the actual allocated slot and require tracking ID -1 in that same slot; do not search for tracking ID 28 merely because the logical identifier was 28. It sends no events. The source path and an RPC success alone cannot provide that delivery receipt.

## Post-state and acceptance limit

Require a unique new labeled reader focus outside the neutral Canvas contact area, a new TalkBack caption matching that actual focused item, and unchanged circuit viewport geometry in the retained images. A title or toolbar destination can establish this small transport capability; it does not establish the full traversal order. Canvas focus/caption with a pan cannot pass. A new header focus outside the contact area reduces the possibility of ordinary hit testing. It does not independently prove gesture recognition; the conclusion requires the combined hardware trace, live reader state, focus and actual reader output.

Retain the post-capture bound service/touch-exploration evidence and the kernel contact release if observed. Missing or contradictory focus, speech, service, release or viewport evidence is `FAIL_NOT_QUALIFIED`, without attribution to the app or reader. Restore the initial public reader and speech-display controls, verify notifications remain denied and compare all other public settings. Verify app/test/helper hashes again. Report any viewport or opaque-state difference without promising byte identity. Return the lane only after restoration is confirmed.

The unit could qualify one hardware-transported reader transition. It cannot close issue #28 or qualify Tools activation, rotor/actions, creation, wiring, simulation, save/reopen, fonts, contrast, physical hardware or audio quality.
