# SAP-1 sequential case

Result: **PASS**, one selected test, zero failures, **140.001 s**, runner execution exit **0**. Qualification remains **PARTIAL**.

- [Raw runner result](results.json), [case receipt](receipt.json), [complete compact XCTest log](test.log), [installed-after fingerprint](installed-after.json).
- Selected test: `EditorEndToEndTests/EditorEndToEndTests/testSap1SequentialExecution`.
- Run ID: `1b224cef7bca`; app producer `6cef0542`, KMP validation `85da031e`, UI harness `ba9d9682`.

The driver loads SAP-1 through the Library, resets the simulation, toggles Reset `0→1→0`, then sends 56 real Step actions. It checks the counter every eight steps and asserts 56 after the loop. At that checkpoint, the canvas description ends with output nibbles `0000` and `1000`. The actual distinct Hex components `canvas-component:import-104` and `canvas-component:import-105` expose digit `0` and digit `8`, with decimal points off. Their full values, the canvas description and the half-cycle-56 screenshot are retained as original attachments, linked by [receipt.json](receipt.json).

The case then uses Run and Pause. Its observation is exactly `startedAt=56, pausedAt=57`; two paused counter reads match around the contract's requested 2500 ms wait. The final native tree reports `Steps 57`. The paused/final screenshots are retained, with attachment timestamps and names in the original manifest.

The unknown values elsewhere in the captured canvas description are preserved without being reinterpreted as fully resolved signals. No independent pixel oracle or project XML equality is asserted. Run timing does not qualify simulation frequency or continuous internal-state stability. See the [common scope and provenance](../README.md).
