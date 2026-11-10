# Shared user scenario contract

`editor-actions-v1.json` contains literal expectations for the scenario at source commit `04a0b34`. Both native runners consume the same component attributes, wire endpoints, paste offset, truth table and SAP-1 counters. The `.circ` fixture represents the `wired` checkpoint. It does not replace creating and connecting those components through the UI.

Actions describe user operations and refer to canonical localization keys through each runner. Coordinates are circuit grid coordinates, not device pixels. Each platform resolves a fresh native element before acting. A missing or ambiguous control fails the scenario.

`assertDocument` compares an actual complete UI export against a named literal snapshot. Component order and XML formatting do not affect the comparison. Component attributes, positions, duplicate multiplicities and wire endpoint direction remain exact. Generated circuit/component identity attributes are excluded from this structural comparison. Native identity checks must remain separate from the literal component, attribute, and wire snapshots.

The SAP-1 file assertion counts all circuits, components and wires. The native sequential scenario also observes half-cycle 56, display nibbles `0000,1000`, progression after Run and a stable counter after Pause. Counting a fixture or parsing this contract does not establish that those actions ran.

`scripts/user_scenario_contract.py` provides `load_contract`, `normalize_document`, `assert_document` and `assert_sap1`. Its CLI writes an assertion record and retains the exact input bytes. These records identify document assertions only; the native runners publish their own device, artifact, screenshot, log and execution results.

The Pin defaults are verified against desktop commit `fbb1eca6` in `PinAttributes.java`, `ProbeAttributes.java`, and `StdAttr.java`, and the mobile `WiringLibrary.pinDefinition`. Both kinds default to east, simple behavior, zero initial value, a west label, bold 16-point SansSerif, and radix 2. The scenario explicitly changes the output to west before its first component checkpoint. Its input terminal and the NOT input are named `in` by the current registry layouts. Expected XML values are literal; they are not learned from a captured export.

`legacy-original-actions-v1.json` defines the shared open, edit, simulate, save, and reopen scenario for the inverter saved by Logisim 2.7.1. It uses the same document assertions as the editor scenario and records output checkpoints before and after reopen.
