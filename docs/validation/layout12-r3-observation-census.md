# Layout R3 observation census and Keyboard R2 contrast

The retained files do not isolate a product, transport, receiver or environment cause for the [R3 failure](layout12-small-phone-font1-5558-003.md). R3 never entered the SDK snapshot path. Its final CLI acquisition timed out with 1.148 s remaining in an editor-readiness wait; preceding query roots were not preserved. The successful Keyboard R2 proves that both the shared export route and the SDK client worked in its recorded contexts, but is not a matched control for R3.

This analysis read existing files and viewed unmodified screenshots. It used no device getter, UI action, build, process control or new Native attempt. The original 80 R3 payloads remain byte-identical. Derived [census JSON](artifacts/layout12-r3-analysis/census/census.json), [retained roots](artifacts/layout12-r3-analysis/census/roots.csv), [source-route table](artifacts/layout12-r3-analysis/census/query-route.csv) and [Keyboard contrast](artifacts/layout12-r3-analysis/contrast/comparison.md) are separate from that raw archive. The source-route table does not present missing query history as a runtime log.

## Conditions and acquisition routes

| Observation | Layout L1 R3 | Keyboard-TTY R2 |
| --- | --- | --- |
| Producer identity | App 3a, test 19ab, helper 504c; harness 68a31383 | Same three APK hashes; harness b56aa75e |
| Acquisition source | `android_ui.py` SHA256 `8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634` | Byte-identical client; shared `wait_editor` and `export_xml` method bodies also agree |
| Device/OS | emulator-5558, API 35 | emulator-5556, same API/build fingerprint; a different device |
| Geometry/font | Initial 1080×2400 at 420 dpi; display gate observed 320×568 at 160 dpi/font 1.0 | Physical 1080×2400 at 420 dpi, no display overrides; fontScale was not explicitly sampled |
| Fixture | Two embedded Image components plus Reset Pin | IO_Showcase, 54 components/64 segments; a different circuit and task history |
| SDK requests | None invoked or retained before this failed baseline |26 COMPLETE, unique nonces, PID 25265 before/after each; no ROOT_UNAVAILABLE/INCOMPLETE |
| SDK root sources | No SDK verdict, including no availability verdict |25 active-window and 1 window-active; 18 captures with focused EditText, six with unfocused editors, two without an editor |
| Last SDK sample | None |#26, 164 nodes, active-window, windowID 30930, no EditText; full-size canvas `[0,655][1080,2119]` |
| Shared baseline | No local baseline file produced | Complete 21,971-byte `io-baseline.circ`, SHA256 `a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9`; source orders this export before the first `input_text`/SDK activation |

The Keyboard baseline therefore also supports a successful CLI/receiver export at its normal width. The client activates SDK acquisition only through its focused text-input path. R3 failed before either layout text-input case, so typed ROOT error handling was never exercised. The eight Keyboard SDK samples without focused editors are transitions from the already active SDK path, not evidence that R3 invoked that backend.

## Every retained R3 hierarchy

All six saved XMLs contain only the editor package and have rotation 0. There are five distinct XML hashes. These are scenario captures, not a complete history of `screen()` results.

| Retained file | Nodes | Observed stage and limit |
| --- | ---: | --- |
| `import-report-1-ui.xml` | 17 | Startup report at 1080×2400, before the selected display cell |
| `layout12-001-window-before-layout-ui.xml` | 101 | Initial editor/default AND document; canvas `[0,647][1080,2119]`, before the override/fixture |
| `import-report-2-ui.xml` | 17 | Default-document report at 320×568, before layout-fixture import |
| `import-report-3-ui.xml` | 17 | Layout-fixture report at 320×568, before baseline export |
| `layout12-small-phone-ui.xml` | 16 | Failure collection after inner restoration; fixture Import report at 1080×2400 |
| `failure-ui.xml` | 16 | Later failure collection after restoration; same XML hash as the preceding file |

The source deletes each remote CLI dump after reading it and saves only selected scenario captures. The remote suffix 36 identifies the terminal numbered dump attempt, not 36 successful roots or a complete final poll count. Its remaining 1.148 s does not prove 30 s of continuously absent roots. Earlier polls could have returned another activity, a root without a canvas, or a report requiring acknowledgement. Their contents and outcomes are not recoverable from these files.

## PID, foreground, receiver and pictures

The initial window/PID capture identifies the editor's MainActivity and PID 23833. Both captured app logcats contain that PID and no FATAL/ANR marker in their retained lines. The two filtered last-ANR files report no recorded ANR belonging to the isolated application. These bounded observations do not prove continuous responsiveness or a stable foreground activity during the blocked wait.

The source and stack show that the Read copied XML lookup returned and its tap command was sent before `wait_editor`. The returned pre-tap root, hit point, active activity and exact phase time were not retained; sending the tap does not prove its callback executed. There is no saved root or foreground/PID sample for the interval immediately after that action. The after-run inventory observes MainActivity. The final inventory, after force-stop, observes `ChooserActivityLauncher`; this later state cannot identify the activity during the wait.

The private TARs were collected from the editor package, not its separate `.test` receiver. No receiver `clipboard-project.circ` payload was sampled. Its absence from the run directory is not evidence that the receiver file was absent on-device: the script's structural payload read follows the failed readiness check. The receiver source writes clipboard text on its public button and then finishes; the retained artifacts do not establish which clipboard bytes it received.

The pictures were compared directly. The initial full-size editor shows the default AND circuit. The 320×568 images show import-report bodies, including the layout fixture, without a retained small-size canvas transform. The final 1080×2400 failure image shows a report over the fixture's colored images/Reset and a 103% control after restoration. That late drawing is not a viewport-retention measurement or the scene during the failed wait. Keyboard's full-size capacity image shows its IO circuit, not an equivalent receiver/IME scene. Screenshots and XML were captured sequentially; host file-write times are not device event timestamps or portable capture clocks.

## Limits and the next justified diagnostic

An app window is observed before the cell and again after cleanup. The decisive post-Read activity/root/payload was not sampled, so the files cannot select among missing editor readiness, receiver return state, CLI acquisition latency or another cause. Identical APK/client bytes and a successful different run do not remove differences in device, window, circuit, font control or task history. Internal SDK capture durations are not comparable to the terminal CLI budget or a user's perceived latency.

No adapter, fallback, reset, deadline increase or product change follows from this census. If a separate diagnostic is authorized, the smallest useful unit is the existing baseline receiver transition with additional evidence retention:

1. Retain stdout/stderr, exit/timeout status, the original absolute budget and elapsed/remaining times for each existing CLI command, plus every returned XML root and its package/visibility summary.
2. Correlate the existing Prepare, XML open/Copy/Close, receiver lookup, Read tap and readiness stages. Record the current pre-tap tree and actual target bounds instead of inferring that a callback ran.
3. Under that authorization, sample foreground/PID and the owned `.test` payload at the blocked boundary. Keep payload inspection diagnostic; require the same editor-readiness and complete-document acceptance checks afterward.
4. Preserve the same artifact identities and bounded failure policy. Do not substitute the 26 SDK success for a missing R3 root or switch backends automatically.

The accompanying local census is reproducible against the original producer directory. Replaying a copied archive can reproduce byte/content facts, but cannot reconstruct original host mtimes or omitted polls. All original Native verdicts and the open #12 scope remain unchanged.

The final producer inventory has 83 files: 80 original raw files plus the three independently identified APK binaries. An additional bytecode cache with a post-run mtime was identified separately, excluded from Native evidence and removed without changing any captured payload. Its originating import was not identified by this trail. The [cleanup record](artifacts/layout12-r3-analysis/contrast/analysis-cache-cleanup.json) retains its exact hash and timing; that administrative file count is not a native observation.
