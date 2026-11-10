# Selected Missing Files runs: ce3 PASS, prior 6cef FAIL

The selected local Missing Files case passed on ce3 in **169.212 seconds, one test, zero failures, exit 0**. Its raw `qualification_gate` remains **PARTIAL**. The earlier 6cef execution remains **FAIL**, 82.204 seconds, one failure, exit 65. This archive preserves both results and binds each to its own app artifact, device and run-specific fixture.

| Execution | App producer | Device | Result and endpoint |
| --- | --- | --- | --- |
| `ios-final-6cef-missing-files-001` | `6cef0542`, tree `d987d43d…`, executable `198a06dc…` | B25 iPad Pro 11-inch M5, iOS 27.0 Simulator | FAIL at the contextual-error guard; post-acknowledgement checks **NOT_RUN** |
| `ios-ce3-missing-files-phone-001` | `ce3e061d`, tree `f392420c…`, executable `833ac07f…` | Phone21 iPhone 17e, iOS 27.0 Simulator | Selected PASS through acknowledgement, XML equality, control/absence assertions and editor menu checks; suite **PARTIAL** |

The ce3 producer manifest has SHA-256 `3b48f3ca39420de5e36f6ff5e1a8304c1bbb6bfafb953acd57e7adf5dc09fd2d`. No 6cef execution result is assigned to that artifact. The 17 runner-recorded harness source hashes are identical between these executions. App source, device and fixture run IDs differ. The ce3 app includes the initialization/lifecycle change `1532eff9` (mirror `e4699069`) as well as the contextual-error change `b4872a5c` (mirror `ce3e061d`), so these observations are **not a controlled A/B test of one line**.

## Route and observed outcome

The shared test creates a fresh `missing-external.circ` from the run's `documents/external.circ` control. It uses **File → Edit external file → the native Files picker → On My iPad/iPhone → Logisim Mobile → LogisimE2E → run folder → missing-external.circ**. It waits for the exact opened filename/origin and verifies that Save to file is enabled before deletion. The separate Clipboard Receiver control and FileProvider extension are not used by this case.

The test captures the complete project XML, removes only its fresh run-owned file through `FileManager`, then invokes the actual Save to file action. On 6cef, the native alert says only `The file doesn’t exist.`. The test fails at `FilesEndToEndTests.swift:183` because the error does not identify the file or a canonical provider/access failure. It does not reach the later non-conflict assertion, acknowledgement, final XML comparison, post-save absence/control checks or editor-usability check. The archive does not supply those missing results.

On ce3, the alert's actual StaticText says **`Could not access missing-external.circ: The file doesn’t exist.`**. The title and Got it button are retained in the same modal tree. The test verifies that this is not a revision-conflict notice and that Save did not recreate the removed file, acknowledges the error, then captures the complete XML again. Both XMLs have **1,591 identical bytes**, SHA-256 `f1797171784e749359236dfcc86c70b64f71e258416cba76d7e7641f30496894`.

The original/control data has **1,391 bytes**, SHA-256 `b97c12341ff44a655a750f9ec21c2597c2d76478efa93522076b01fc229278ff`. The test and retained independent postflight record that the removed document remains absent and this control remains unchanged. The final canvas geometry is `(0,243,390,484)`, fully visible within the `(0,0,390,844)` app frame. Opening and closing the file menu after Got it completes, and the final canvas remains present. This is a specific usability check, not a new editing scenario.

The runner's `documents/` directory is initial staging. Its lack of the test-created missing file is not a historical deletion oracle. The absence conclusion comes from executed assertions and the ce3 postflight, rather than from that staging directory listing. The exact native `NSError` domain/code remain **UNKNOWN**. The description is retained as literal native text; its wording is not used to infer a Foundation classification.

## Evidence and replay

The [receipt](ios-missing-file-selected-runs.json) records identities, phases, selected attachment roles and scope. The [evidence manifest](artifacts/ios-missing-file-selected-runs/manifest.json) hashes 74 unchanged raw files, about 1.99 MB. It retains raw results, Run, both producer manifests, the correct staged controls and original-data attachments, the before XML in both cases and after XML only in ce3, exact error trees/screenshots, picker controls/setup, final trees/screenshots, geometry, full compact XCTest logs, catalogs and relevant executed Swift sources. The complete original attachment manifests retain pointers to omitted media and diagnostics; the selected file lists identify exactly what this archive includes.

The 6cef historical 13-second process log stays local at `outputs/ios-final-6cef-missing-files-001/diagnostics/app-error-bounded.log`: **2,601,103 bytes**, SHA-256 `96c8b634397fd18eb1ce03bd4091a5fa00a1fb0c0ef5d75197ada11fb0e5b270`. Its capture metadata and stderr are retained; it did not identify the exact `NSError` domain/code. The portable verifier does not require this omitted log or any original absolute path. No xcresult, screen recording, app binary or large Simulator log is included.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 docs/validation/artifacts/ios-missing-file-selected-runs/verify.py
```

The verifier checks raw hashes and source/artifact separation, each selected attachment against its original manifest, the concrete modal messages, the Files route and Save association, XML/control identities, the FAIL stopping point and the ce3 postflight/geometry. It preserves `FAIL` for 6cef and `PARTIAL` for the selected ce3 PASS. Offline replay, relocation, deliberate raw-data/metadata mutations and independent source/evidence review passed; no native test was rerun during packaging.

## Separate work and limits

The original Missing setup is `c893460e63b3c05e4560e42c6d3be11d9e3fa8b9`; the original contextual NSError fix is `b4872a5cfce27fbf7ddbc30e8700d534d206687c`. Full lifecycle/mirror IDs are in the receipt. The test-only FileProvider fixture family **`ff99aadb → c8c8185e → 48412de3`** and bootstrap harness family **`10099e21 → 628562d5`** are separate from this app and this Files test. Their failure archive is commit `f2b4cde813727ee5d5a844cfbf7894bf071abc09`, under `docs/validation/ios-fileprovider-bootstrap-failures-2026-11-05/`. Those fixtures are not copied into, or claimed as part of, the ce3 product. This receipt makes no extension-execution or authentication claim from them.

This case does not qualify System Open, revoked authorization, cloud/remote providers, restart, a physical device or all of issues 26/36. A removed local file is not a revoked security-scoped grant. Earlier results remain attached to their original artifacts. Packaging operated only on retained files in a new worktree; the canonical worktree, app, device and caches were untouched.
