# Android independent-16 preparation failed before sampling

The single authorized Android pilot on 2026-11-05 ended with `FAIL_SETUP`. The passive collector never started. There are no CPU, PSS, RSS, frame, or 20-second window results from this attempt. This receipt does not close issue #48.

The producer was clean at `2827b19b2041ed98dc5e10f2c9d4a94e07911869`. The app source was `3a0a01f1af05dd3fea75d5a1499c762d039c478a`. Installed app and helper APK hashes matched the frozen `10e2`, `19ab`, and `504c` artifacts before and after the attempt. The driver did not build, install, clear app data, or retry the case.

The existing `independent-16.circ` file supplied the exact 16,109 historical bytes, SHA-256 `19198a15e4880baccd3d00391f257c766a8be0859daae1ca721d3d8ed7c12a31`. The driver checked all ten historical circuit files and their manifest before and after. The fixture provider's initial readback matched the input bytes. The post-window provider readback was not reached.

## Observed failure

`Scenarios.incoming` timed out while waiting for the UUID-specific `status_file_opened` message:

```text
AssertionError('The status_file_opened control did not appear within 30s')
```

The captured import report identifies the exact fixture UUID, source version `3.8.0`, circuit `gates-16`, and 48 applied Pin defaults. After the report closed, the last complete UI tree showed `gates-16`, 64 component IDs, the 48 expected pin labels, `Steps 0`, and `▶ Run`. Its status line read `Operation cancelled.`. These observations establish visible imported content. They do not establish canonical XML equivalence or the complete circuit model, because `export_xml` was never reached.

The host predicate depends on a mutable status message. The source permits cancellation to replace that message. The captured files do not identify a cancellation callback or prove that the file-opened message appeared earlier. This receipt preserves the failure without assigning an unobserved event sequence to the app.

## Returned device state

The driver force-stopped only the owned app, test provider, and snapshot helper packages. All three `pidof` observations were empty after cleanup. All recorded configuration values matched their initial values, with no restoration writes. The accessibility service and touch exploration were off. The display remained 1080 × 2400 at density 420, with font scale 1.0 and rotation 0.

The driver returned emulator 5558 to the next owner after the process exited with code 1. There was no second Native attempt. Shared-host covariates were not collected because preparation stopped before `host_state`. No quiet-host, physical-device, energy, drawing, viewport, or cancellation-latency result follows from this attempt.

## Preserved files

`raw-attempt.zip` contains all 243 original files, totaling 1,332,430 uncompressed bytes. The archive preserves 118 ADB envelopes and 234 full stdout and stderr files. Query 79 timed out within the remaining 0.1316 seconds of the readiness deadline and has an exception envelope without output files. That timeout does not establish an app hang. The archive also contains the input bytes, the executed driver, the report screenshot and tree, the failure traceback, and the final attempt manifest. No host or app process-argument census, environment dump, JFR recording, or APK binary is included.

`attempt.json`, `failure.txt`, `driver.py`, and `import-report-1-ui.xml` are exact convenience copies from the archive. `receipt.json` records archive identities and the terminal observation. The terminal line was transcribed from the final exec tool result for handle 89831. The process did not redirect its own stdout to a file.

The archived driver is the executed snapshot, including its local paths and fixed output name. Its output guard rejects reuse. It is preserved for review, not as authorization for another device attempt. The source plan remains [the frozen-app plan](../../validation/native-performance-source-plan-3a/README.md).

The offline verifier checks both file integrity and the literal failed outcome:

```sh
python3 docs/performance-evidence/android-3a-input-idle16-001/verify.py
```

Expected output contains `VERIFIED_FAIL_SETUP`, 243 raw files, 118 ADB envelopes, and zero sample windows. Rehashed negative controls test rejection of a relabeled pass, an invented collector invocation, an unclean return, and a different final document. No Android, JVM, or build command runs during verification.

## Next software unit

A host helper can qualify this workload without relying on the status message. It must observe the import report for the exact fixture UUID and circuit, preserve errors, close the report, wait for the editor, and export the complete model. Before any future collector starts, the exported model must contain 64 components, 16 wires, and the 48 expected labels. Offline tests can use these archived trees to reject a different UUID, an error dialog, and a missing editor. A new Native attempt still requires its own lane and authorization.
