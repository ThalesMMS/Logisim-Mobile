# Android scenarios for issue 35

The runner must use the test APK with an application ID ending in `.e2e`. This package has its own files, preferences, permissions, and provider authorities. Leave the regular app's data untouched. Require an explicit serial and record the APK hash, revision, API level, screen size, density, language, and any changed settings.

## Driver and results

`scripts/android_ui.py` provides hierarchy reads, selectors, taps, ASCII text entry, and failure capture. Each dump uses a fresh remote path. A null or invalid dump triggers a retry until the deadline, without reusing earlier XML. Ambiguous selectors fail. The runner translates selectors using the active catalog because visible names vary by language.

Each run must create `outputs/android-e2e/<run-id>/`. Record each scenario and step in JSON with its duration, expected predicate, and result. Tool errors count as infrastructure failures. Failure capture must preserve the original exception even if screenshots, XML, or logcat fail as well.

`fail_artifacts()` stores a screenshot, hierarchy, process logcat, and private files only from the `.e2e` package. It also looks for XML in visible fields and checkpoints. A checkpoint may predate the edit on screen. The manifest records its source, and a current UI export requires the export action.

## Implemented scenarios

The table describes what the runner does. Each run and its status are recorded in `results.json`. If a run stops early, any groups it has yet to execute remain unverified.

| Scenario | UI actions | Exact predicate |
| --- | --- | --- |
| Editing and properties | Create an empty project, insert an input, NOT, and output, rename, turn the output west, and change and restore the NOT orientation | Complete exports match the exact checkpoints in the shared iOS contract |
| Wires and combinational simulation | Connect both terminal pairs, toggle the input, and remove and restore the first wire | `0 → 1`, `1 → 0`. XML confirms endpoints and undo and redo of removal |
| Selection and history | Select components and wires, copy, paste, and delete. Undo and redo each operation | New copy offset by `(20,20)`, attributes and internal wires preserved. History restores the exact document |
| Viewport and touch | Zoom in and out, frame the selection and circuit, insert an OR gate by touch, and repeat in Explorer | Zoom changes. Framing preserves the document. Both routes create the same component at the same grid position |
| Sequential | Load SAP-1, pulse Reset, step the clock, and run and pause | Display reads `08` from 56 half-cycles onward. The counter stays stable when paused |
| Documents | Open a `.circ` through SAF, export to a new URI, and receive Open With and Share | The imported project and exported document are equivalent. Sharing uses `content://` |
| Cancellations | Cancel opening, creating, and sharing | The document and original file remain intact |
| Rejected file | Import invalid XML, an unsupported component, and invalid UTF-8 | An error is visible. The previous project and external original remain intact |
| Recovery | Edit, terminate the process, and reopen. Kill the process while SAF is open and complete the selection | XML is restored without loss. The pending export completes or shows a recoverable error |
| Layout | Rotate to landscape at 1.6× font scale, reach the library and Save As, and restore settings | The actions remain reachable and the document is preserved |
| Unicode | Import a long circuit name or label with accents, Arabic, and directional controls | Export preserves code points. The input control remains visible and in frame |

Start each scenario with its own fixture or an empty project in the isolated package. Compare the before and after documents for cancellation and rejection cases. Save the selected and exported files with their hashes so verification can go beyond component counts.

Group movement, branch splitting, instance hierarchy, mouse and stylus, TalkBack, 320dp phones, and 2× font scale are covered by additional qualifications. The basic runner requires separate evidence for these results.

## Infrastructure risks

- The existing smoke tests use the regular package, overwrite the open project, and depend on Portuguese labels. They can serve as scenario references. Issue 35 requires the isolated package.
- UiAutomator can return a null root during startup and continuous simulation. Wait for the relevant control with an explicit deadline. For exact sequential results, prefer manual steps. For continuous runs, pause and check the actual counter.
- Fetch fresh nodes after navigation, sheet changes, scrolling, or rotation. Coordinates derived from a current node apply to that action on the current device.
- Restore rotation, font, or language changes in `finally`, preserving the original error if restoration also fails.
- `am instrument` can exit with status 0 even if tests fail. Require the success markers and counts from document instrumentation.
- The test provider runs in a process and UID separate from the app. It covers external-provider behavior for that fixture. Specific cloud services and offline networks require separate evidence.
- The file criterion depends on integration from issue 25. Advanced wire controls depend on issue 8. Explicitly mark scenarios pending until the UI provides those actions.
