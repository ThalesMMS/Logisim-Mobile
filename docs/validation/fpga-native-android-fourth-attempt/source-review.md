# FPGA Android board-file source review

Reviewed worktree: `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-board-file`.

Scope: source and documentation only. No ADB, UI, host, Gradle, product build or test execution by this reviewer. The reported 45 passing checks belong to the author.

## Result

Approved after the timing fix. No remaining source blocker was found in the reviewed change. Each MediaStore query receives the remaining portion of the original 30-second budget. The driver retains the returned metadata, then checks the deadline before accepting publication. A late successful response cannot open the native picker. The added negative test exercises that sequence without a native execution.

## Accepted changes

- The driver uses the public Import board file action and native document picker. The Android contract explicitly replaces the shared paste/read actions and leaves paste qualification as NOT_RUN.
- The staged XML changes only the visible board-name nonce. It verifies complete source bytes before and after the picker and records hashes.
- MediaStore publication uses the canonical `/storage/emulated/0/Download` path. The native picker cannot open until the row has an XML MIME type, `is_download=1` and `is_pending=0`.
- The required device text matches the actual enum rendered by FpgaPanel: `OPEN_FPGA · ice40 · hx1k · tq144`. The unique board name prevents a stale card from satisfying import readiness.
- FpgaPanel.importXml explicitly sets showXml=false after success, so the previously raised concern about the residual XML editor occupying the viewport does not apply after successful import. The driver observes the return without injecting scroll actions during the native transition.
- The README describes file import and preserves the four earlier attempts as failed. This source change does not establish a native pass or close issue #65.

The earlier native attempt demonstrated a 245-character prefix of a planned 535-character board XML and the actual parser diagnostic Unterminated XML attribute. It did not demonstrate an invalid AND fixture. The actual core geometry places the AND inputs exactly at the fixture endpoints `(150,80)` and `(150,120)`.
