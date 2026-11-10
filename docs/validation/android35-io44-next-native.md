# Android editing and I/O preparation

This is a source preparation and an audit of existing evidence. No Android command, build, configuration change or native case ran for this preparation. The existing results retain their original producers. Issues #35 and #44 remain open.

## Editing acceptance

| #35 requirement | Existing evidence | Remaining work |
| --- | --- | --- |
| Editing, wires, selection, history and zoom | The four groups in `run_editing` implement the shared editor contract. The retained 04a and df276e attempts failed during their first group. | Execute those four groups successfully, retaining their full XML checks and touch/Explorer insertion comparison. |
| Sequential simulation and recovery | `android-sap1-beta-85da031e.md` records PASS in 245.35 seconds, 56 steps, output 08, Run/Pause and identical XML after restart. | No repeat is required to retain this result. |
| Files, cancellation, invalid and incompatible inputs | Issue #25 is completed. `docs/android-documents.md` and its original `4ac12cc4` record real editor Save As, reopening and recovery. Separate native import, share, cancellation and rejection proofs remain valid. | The later integrated Files workflow that encountered HWUI ANR still needs qualification. This does not reopen #25 or establish that Save As is absent. |
| Isolation and failure evidence | The `.e2e` runner retains manifests, screenshots, hierarchies, logs and documents, including its failed attempts. | Preserve those records in the next units. |

The original editor document evidence under the Android Files owner's `outputs/android-documents` includes imported, saved and recovered documents with identical SHA256 `23336403d9c646e1ffb229c4d44f8ca777b460a7f601c690feaff55f56bc506a`, 48,741 bytes, seven circuits, 156 components and 318 wires. Missing newer scenario names in `results.json` do not invalidate that earlier result.

The next editing run uses the existing `--scenario editing` to execute all four groups in order. It does not add TalkBack, pen, mouse, physical hardware or the separate layout matrix as acceptance conditions for #35.

## Export order

`_Editing.export` previously revealed the XML action before calling the complete `Scenarios.export_xml` route. Revealing an overflow action can open its menu. `export_xml` then prepares its foreground Clipboard Reader and waits for an unobstructed editor before selecting XML itself. The redundant earlier reveal can leave a menu open across this activity transition. Layout12 identified the same preparation order in `68a31383`.

This preparation removes that one earlier reveal. Receiver preparation, editor readiness, XML action routing, Copy, Close, foreground reading and complete XML validation remain in `Scenarios.export_xml`. No production code, fixture, action contract, probe oracle, input injector or deadline changes.

The 22 editing selector tests pass. Two new checks exercise receiver preparation without an inherited toolbar route and preservation of the receiver's failure. Restoring the original pre-reveal method in memory makes both new checks fail. An independent source review confirmed the route and unchanged oracles. These checks do not qualify native execution.

## Smallest I/O unit

The next unit is exactly `dip-last`. It uses the existing Inspector actions to change switches 1, 2, 1, then checks outputs 0 and 1, `resetbehavior=last` and `positions=2`. It changes the switch count 8 to 4, rejects 0, restores 8, resets and reopens the complete exported document. No canvas pointer coordinates are needed.

The current Keyboard/TTY case already passed in `android-io-keyboard-3a0a01f1-r2` with app producer 3a. Its final `buflen=4` and `ABCD` are explicit. A return to buffer length 16 was not performed and is not an additional requirement of that selected case. DIP restores count 8, not 16.

Button has a separate real held/focus-loss result in `android-native-beta-followups-85da031e.json`: `io-release` passed in 7.839 seconds, observed HIGH while held and LOW after loss of focus before UP, and preserved its 789-byte document. That result is not a claim about the showcase's later failed pointer case.

Do not repeat the existing showcase `pointer-controls` unchanged. `canvas_pointer` uses the AX component rectangle as the drawing transform, but `accessibilityTargetBounds` expands and clamps these targets. Snapshot 4 from the historical `290cecfe` failure exposes `IO_BUTTON` at `[155,880][164,1006]`, which is not the logical 20 by 20 drawing. The failed run did not retain the injected point, so this source defect does not establish its historical cause. Keypad and Joystick need a physical drawing transform and retained points before the next pointer case.

## Planned command after the root lease

The parent owns scheduling. Use a new output directory, the three immutable approved APKs and their app receipt. The existing runner installs these test artifacts, clears only its isolated app/test data, imports the fixture and runs the selected case. This command has not run as part of this preparation.

```sh
python3 scripts/e2e-android.py \
  --serial emulator-5556 \
  --scenario io-showcase --io-case dip-last \
  --artifacts /Users/thales/GitHub/logisim-mobile-worktrees/android-io-keyboard-3a-exec/outputs/artifacts-5c-keyboard \
  --build-receipt /Users/thales/GitHub/logisim-mobile-worktrees/android-io-keyboard-3a-exec/outputs/artifacts-5c-keyboard/app-build-receipt.json \
  --language en \
  --output outputs/android-io-dip-3a0a01f1-001
```

Before execution, verify the app/test/helper hashes, installed identities, English system locale and reader settings. TalkBack and speech display must remain off, with no bound accessibility service. Record original settings, ANR status, process and source identities. Do not infer that the viewport from the earlier TalkBack attempt was restored; this unit imports its own fresh fixture. Retain every action, SDK result, full XML export and failure. On return, confirm identities and settings, preserve the primary error and stop only the owned isolated app. No retry or fallback is part of this unit.

`android35-io44-preparation.json` pins the prepared sources, unchanged oracle inputs and approved local artifacts. Its source preparation status is independent of any future native result.
