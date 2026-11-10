# SAP-1 automatic Run/Pause case

Result: **PASS**, one selected test, zero failures, **28.827 s**, runner execution exit **0**. Qualification remains **PARTIAL**.

- [Raw runner result](results.json), [case receipt](receipt.json), [complete compact XCTest log](test.log), [installed-after fingerprint](installed-after.json).
- Selected test: `EditorEndToEndTests/EditorEndToEndTests/testSap1AutomaticRunPause`.
- Run ID: `d870b0afd63d`; app producer `6cef0542`, KMP validation `85da031e`, UI harness `ba9d9682`.

The driver loads SAP-1 through the Library, resets the simulation, toggles Reset `0→1→0` and checks counter zero. Run must expose Pause; Pause must restore Run and remove Pause. The retained observation is exactly `startedAt=0, pausedAt=1`, and the final native tree reports `Steps 1`. Two paused counter reads match around the contract's requested 2500 ms wait. The contract requests Run for 1500 ms; this does not measure the simulation frequency.

The original import report, paused/final screenshots, final native tree, orientation note and overflow geometry are retained through the attachment manifest. The two paused/final PNGs are byte-identical; they are preserved under their original names rather than treated as two independent display checks.

This selected case does **not** execute 56 manual half-cycles, assert Hex `08`, copy project XML or compare project XML before/after. Those actions must not be inferred from the shared source containing other test methods. The pause observation does not prove continuous stability of every internal state. See the [common scope and provenance](../README.md).
