# Explicit Files Copy setup for the I/O cases

This source-only alternative selects `--io-setup files-copy` before execution. It opens the app's Files menu, chooses Import Copy, and selects a run-owned source in the separate receiver's shared Documents folder. It never switches to System Open after a failure. The default setup remains System Open.

The option accepts only explicit selections from the four approved I/O methods. Their method bodies are unchanged from `0732f74f`, including Event Step, all 19 literal baseline probes, and the 18 expected full-XML checkpoints. Fixture bytes, targets, contract, and document oracle are unchanged. The timeout remains 600 seconds.

## Staging and identity

The runner reuses the existing receiver validation and stages exactly `io-showcase-source.circ` in `Documents/LogisimE2E/runID`. It requires the approved fixture hash, a canonical run directory in a separate receiver container, and no existing source file. Only the opt-in route adds `Run.ioSetup`. Preparation uses isolated virtual containers and records `NATIVE_NOT_RUN`.

`FilesCopySource` validates the configured source and produces a fresh UUID filename in that same directory. It requires identical bytes, refuses overwrites, and rechecks the source and path resolution at staging and return. It rejects symlinks, including a fixture replaced by an equal-byte symlink after initialization. Foundation-normalized paths accept the system's `/private/var` representation while explicit `.` and `..` components remain invalid.

Before touching Files, the I/O suite requires one usable public editor Window for bootstrap. Two candidates fail preparation. This reference has no current fixture identity and does not establish key-window or hit ownership. Editor controls remain scoped to it. `NativeFilesBrowser` and `NativeFilesPeerNavigator` retain their existing app-wide native-picker queries, directory headings, unique filename or stem selection, and observed Open handling.

After picker dismissal, the suite verifies the source bytes and clears the bootstrap reference. Initial document admission requires the exact import-report origin for the new UUID filename. A second Window with that filename's status or origin remains ambiguous; the origin requirement does not prefer one of them. The subsequent scoped XML return keeps the persistent origin verification from `0732f74f`.

## Shared source API

The configuration contains these literal keys: `route`, `peerDisplayName`, `runID`, `documents`, `fixture`, and `fixtureSHA256`. The route is `files-copy`. `runID` has 12 lowercase hexadecimal characters. `documents` is the complete run directory, not the root Documents directory. The source fixture and fresh UUID files are direct children of it.

`FilesCopySource.init(configuration:)` throws. Its `stage(_:filename:)` returns the staged URL, and `verify(_:)` checks its preserved bytes. `NativeFilesCopySetup(source:)` wraps the producer. Its static `uniqueEditorWindow(in:)` returns one bootstrap Window or throws. Its instance `select(_:in:editorWindow:)` requires the caller's `nativeQueryRoot` to be that exact retained reference.

The caller must perform its exact-origin Window admission after selection; the picker helper does not qualify an imported project or its outputs. `NativeFilesCopySetup.read(from:)` is only a convenience for `Run.ioSetup`. Other suites can construct a source from their own explicitly approved configuration slot. The Swift producer validates the shared directory shape; receiver-versus-primary container separation is enforced by the I/O runner. A caller with multiple approved nonce-bearing fixtures needs one source configuration per fixture rather than editing bytes after their hashes are approved.

## Source verification and limits

The [receipt](receipt.json) records 35 Foundation producer checks, 111 Window checks, 32 Python tests, and a simulator-target typecheck of 21 staged Swift files. It also records unchanged hashes for the four case bodies, XML helper, Base, picker helpers, fixture, contract, and oracle. The existing `stage_external_peer` function is byte-identical.

Independent source review found the post-initialization symlink defect. The archived RED control reconstructs the earlier source by removing only the two new guards. It is a derived control, not a retained original production file. Both corresponding negatives pass against the final producer.

The [unexecuted native plan](next-run-plan.json) needs root review, complete source integration, a fresh artifact preflight, and an explicit simulator lease. Prior Inspector and receiver passes establish a reusable route, not I/O parity for this fixture in app 3a. The first-call four failures and the second-call backboardd abort remain preserved. This alternative does not prove or fix Open With issue #26, and it has no native qualification for issue #44.
