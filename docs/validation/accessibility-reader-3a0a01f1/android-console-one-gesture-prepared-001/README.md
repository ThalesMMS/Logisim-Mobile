# One console hardware contact, prepared only

Status: `PREPARED_NOT_EXECUTED_ROOT_LEASE_REQUIRED`. This unit uses the existing emulator console. No service, monitor, contact or reader has been started by this preparation. The sender defaults to description only and will need a new explicit Root lease. No authorized lease file is supplied.

`run_contact.py` without arguments makes zero ADB calls. Execution requires a fresh lease tied to the wrapper, contact and plan hashes, with at most 120 seconds of validity and a single contact. The output directory is reserved before any getter, and a private identity claim prevents reuse through a copied lease path. The example lease is deliberately invalid. An execute invocation returns exit code 2 even after collecting a contact: it never represents reader acceptance. Inspect the receipt and restore the public controls rather than rerunning it.

The complete public control sequence is in [reader-cycle-plan.md](reader-cycle-plan.md). The driver covers the contact and passive trace only. The operator owns app preparation, video, preserve-only SDK observations, independent kernel/reader review and verified Settings restoration.

The alternative gRPC startup cannot currently provide the required reversible setup. In the pinned public source, the console command reuses original options, can create authentication material and exposes no demonstrated endpoint-only stop that preserves the AVD. The existing instance previously reported no active gRPC service. This unit therefore selects the already available console without creating a service or using credentials directly.

## Hardware route and limits

The console parses each event tuple, then calls the generic user-event agent with display 0. With VirtioInput enabled, EV_ABS and EV_SYN route to the virtio input device at array index 0. That index is initialized by the device named `virtio_input_multi_touch_1`, which the guest's InputReader currently maps to display 0 and `/dev/input/event1`. The backend can report acceptance even without a device pointer. Source and a console ACK therefore cannot prove delivery. The passive kernel trace is mandatory.

The numeric capability getter confirmed X/Y ranges 0 through 32767, slots 0 through 10, tracking IDs 0 through 65535, pressure 0 through 1024 and finger tool type 0. Selected slot and current pressure were zero. Those capability values and a silent monitor do not prove the initial tracking state of all slots. The unit requires exclusive ownership and records that limitation; it cannot claim a globally neutral start from these getters alone.

## Fixed payload and close

The [payload](contact-plan.json) freezes one raw Linux contact in slot 0 with tracking ID 1 and pressure 64. It does not use the gRPC logical identifier 28. Nine points run from pixel `(300,800)` to `(700,800)`, nominally 20 ms apart, followed by release at 180 ms. Coordinates use the recorded display/range conversion. Every tuple is a canonical integer triple validated before the first send. This matters because the console can send early tuples before rejecting a later one.

Release selects the same slot, sets pressure to zero and tracking ID to -1, then emits SYN_REPORT. `finally` attempts it even after an acknowledgment, logging, timing or interruption failure following possible Down. There is no second gesture, retry or alternate transport. Command ACKs remain separate from observed kernel release. A missing close keeps the lane reserved for Root recovery.

The passive monitor uses only the known device, numeric timestamps/events, unbuffered stdout and a bounded timeout. Device/name headers establish readiness before any contact. Its start and automatic termination remain untested. Independent trace review must see the full slot/ID chain, nine active frames, the complete UP and strictly increasing SYN timestamps over 120 through 300 ms. That interval defines this experiment, not a TalkBack specification. A kernel-only pass cannot prove reader navigation or initial global neutrality.

## Reader proof and public restoration

The sender does not configure TalkBack, click app controls or change the fixture. A later Root-approved setup must record initial settings, enable speech display while the reader is off, enable TalkBack through public Settings and return to the existing paused app. Before and after every preserve-only SDK snapshot, retain the exact TalkBack AMS published-binder/system-client evidence, one actual spoken/touch-capable accessibility Bound record and touch-exploration state. The Bound record has no component field. The [binding helper](binding-README.md) reports non-atomic correlation, not a component join, callback completion or current Binder liveness; actual reader focus and caption remain required. The baseline needs one unique settled reader-focused item A and matching reader-produced text. Projects is a common baseline, not a forced focus. Freeze the actual identity before Down; do not choose a target by XML order.

After the one contact, require a distinct labeled focus B outside the neutral contact region, a matching new TalkBack caption and unchanged circuit viewport geometry. The whole Canvas node cannot be B. Retain video, screenshots, complete SDK trees, raw service state, command receipts and the kernel trace. A pan or Canvas caption alone fails this unit without attributing a defect to the app or reader. No traversal order is inferred from XML.

Restore the original public reader and speech-display controls, verify notifications remain denied, preserve ERROR and every other public setting, compare all three APK hashes and return the lane. An unverified close or restoration keeps the lane with its owner. No full workflow, physical hardware, audio quality or issue #28 acceptance is claimed.

## Offline validation

The integrated sources passed 93 tests with external process and socket boundaries blocked: ten fixed-contact tests, 27 wrapper tests, 23 binding-format tests, 31 kernel-trace tests and two independent regression reproductions. The peer receipts and frozen evidence archives retain the initial failures and corrected sources. These results qualify preparation only; no monitor timeout, virtual hardware event or reader-on binding has been exercised by this unit.

The read-only discovery includes the real numeric capability dump. `timeout --help` exited 125 while printing the installed Toybox usage, so that capture establishes the advertised syntax rather than successful monitor execution. The bounded collector must demonstrate readiness and terminal state during a separately authorized run. Initial tracking remains unknown, and kernel timestamps are retained as emitted; the host scheduler uses its separate monotonic clock.
