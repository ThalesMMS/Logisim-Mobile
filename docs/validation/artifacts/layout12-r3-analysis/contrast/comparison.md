# Layout R3 and Keyboard R2: offline contrast

Both producers used exactly the same app 3a, test APK 19ab, snapshot helper 504c and acquisition client. The app/helper triplet hashes and API 35 fingerprint match. The entire frozen `android_ui.py` is byte-identical, SHA256 `8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634`. The shared `wait_editor` and `export_xml` method bodies are also identical. This does not make the test conditions equivalent.

| Observation | Layout L1 R3 | Keyboard-TTY R2 |
| --- | --- | --- |
| Device and harness | `emulator-5558`, harness 68a31383 | `emulator-5556`, harness b56aa75e |
| Size/density | Initial 1080×2400@420; observed configured 320×568@160 | 1080×2400@420, no override |
| Font | Observed font 1.0 in applied gate | No explicit font-scale sample/control retained |
| Result/timing | Failed before baseline; 92.160 s outer duration including cleanup | One keyboard-TTY case passed; 223.79 s scenario duration. Different timing scope. |
| SDK observation | No `focus-snapshot-*` artifacts; failure trace explicitly runs CLI. SDK activation occurs later, when `input_text` is used. | 26 COMPLETE captures, 26 unique nonces, PID 25265 before/after each; 0 ROOT_UNAVAILABLE/INCOMPLETE |
| SDK root/context | Not exercised for the failing interval | 25 active-window and 1 window-active; 26 distinct window IDs. 18 captures include a focused EditText, 6 have unfocused editors, 2 are full editor roots without an editor. |
| Last SDK sample | None | #26, uptime 52328511, 164 nodes, app package only, no EditText; PID 25265, rootSource active-window, windowID 30930. |
| CLI/foreground gap | No retained successful fresh Editor root after Read copied XML before timeout; last call had 1.148 s remaining. | CLI also used outside focused-field acquisition. Complete CLI poll history is not retained. Post-run readback shows MainActivity focused/resumed before force-stop. |
| Screenshots actually inspected | `import-report-3.png` is 320×568 and visibly contains the fixture Import report. `failure.png` is 1080×2400 after restoration. | `io-keyboard-capacity.png` is 1080×2400 and shows the actual editor/IO circuit. It is not an IME-focused screenshot. |
| Retained viewport | Before override, canvas `[0,647][1080,2119]`; no 320 px canvas frame captured after baseline. The 320 px report body tag is `[24,150][296,399]`, not the whole dialog. | Final canvas `[0,655][1080,2119]`, matching its full-resolution screenshots. |

The Keyboard SDK capture metadata ranges 28–378 ms internally, with root wait 2–330 ms. These exclude unrecorded invocation overhead and are not comparable with the Layout CLI timeout or a user-perceived latency measure. SDK calls retain nonce, app PID before/after, root source/windowID and complete XML. The CLI path deletes its remote temporary dumps and retains only selected scenario captures; neither run supports reconstructing every CLI poll from files.

The terminal 1.148-second call does not establish 30 seconds of continuously missing roots: earlier roots without an editor canvas, or report acknowledgements, may have consumed the caller budget. Those preceding roots are not retained.

Keyboard R2 also preserves the complete `io-baseline.circ`, 21,971 bytes with 54 components/64 segments, SHA256 `a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9`. The selected case source calls fresh()/baseline export before enter_keyboard()/first input_text, while acquisition starts inactive. This supports that the shared CLI/receiver export path succeeded in that normal-width condition as well. It does not reconstruct its unretained polls.

The Layout final Import report cannot be assigned as the cause of the timeout because those captures follow setting restoration. No receiver `clipboard-project.circ` payload is archived; app-only private TARs do not prove that the test package's file was absent on-device. The source and stack identify the attempted receiver/readiness boundary, but do not establish that a successful fresh Editor root was observed after Read copied XML. There is no typed ROOT_UNAVAILABLE result to reinterpret.

The successes therefore establish that the shared SDK client worked in the recorded Keyboard contexts, including larger app roots. They do not establish SDK behavior in Layout R3, which failed on a different path and different device/size before any focused-field call. No product, environment, receiver or transport cause is isolated by this contrast. No new adapter, fallback, reset or timeout change is proposed from these facts alone.

The accompanying `comparison.json`, `keyboard-sdk-summary.json` and `source/source-equivalence.json` retain exact paths, hashes and per-SDK sample data. This analysis only read existing files and viewed unaltered local PNGs; it performed no device command, build or source edit.

## Analytical cache cleanup

The parent identified one post-Native analytical bytecode cache, `run/__pycache__/source-e2e-android.cpython-315.pyc`. Its exact SHA256, size and timestamps were saved in `analysis-cache-cleanup.json` before removing only that file and its then-empty directory. The source-equivalence reader confirms that it used AST/text reads rather than importing that module; this task cannot identify which analysis process created the cache. The final read-only inventory contains 83 Native files: 80 raw payloads and 3 APKs. Existing PNG/XML/SDK subset counts never included the cache and remain unchanged. `producer-final-inventory.json` records all remaining paths/hashes. All subsequent analysis uses `python -B`/AST and no device or source action.
