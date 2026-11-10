# Native compact HDL tool proof

This dedicated XCTest harness drives production controls on an explicitly
reserved **iPhone Simulator**. It preserves earlier projects by creating a new
workspace for each scenario. It does not modify the application or shared
native harness/runner.

Prepare or compile without touching a device:

```sh
python3 scripts/run-ios-hdl.py --prepare-only \
  --simulator IPHONE_UUID --baseline-sha APP_SOURCE_COMMIT --output outputs/hdl-prepare
python3 scripts/run-ios-hdl.py --build-only \
  --simulator IPHONE_UUID --baseline-sha APP_SOURCE_COMMIT --output outputs/hdl-build
```

After the device owner releases the iPhone and the consolidated `.app` exists:

```sh
python3 scripts/run-ios-hdl.py \
  --simulator IPHONE_UUID --baseline-sha APP_SOURCE_COMMIT \
  --app /absolute/path/LogisimMobile.app --output outputs/hdl-native-run \
  --catalog-root /absolute/path/to/app-producer-checkout
```

Use a new output directory per attempt. `--derived-data` can reuse only the
harness build cache. `--alpha` records a debugging run without a qualification
pass. Preparation and build-only always retain `NATIVE_NOT_RUN` and
`NOT_QUALIFIED`; compilation is not UI proof.
For a failing scenario, `--alpha --only-testing
 testCompactCosimulationSamplesResetStopAndCompilerFailure` selects just that
case. Partial selection is rejected without `--alpha` and cannot qualify the
complete workflow. Nonconfidential text replacement uses the shared native
editing-menu helper, proves the complete value is empty, then requires the exact
entered value. It uses no Command-A shortcut. The confidential pairing field
must start empty, and actual companion authentication verifies its entry without
exposing the token. The software keyboard must stay visible until the normal
production Hide keyboard action is tapped.

The optional alpha-only case
`testCompactKeyboardActionPreservesExistingHdlSource` focuses the existing source
without replacing it or pairing a companion, then checks keyboard-action
geometry, the lower-edge touch, exact source preservation and reachable Insert.
It does not execute HDL or qualify either complete workflow. Default full runs
explicitly select only the two complete scenarios below.

The two scenarios require landscape height below 600 points and exercise:

- A real Yosys syntax failure followed by full-XML equality, then Verilog and
  BLIF source entry, plan review, explicit confirmation and native circuit
  application. XML is read through the shipping XML editor; source archives
  must equal the text submitted in the form.
- A real VHDL process that emits `timeNs mod 4`, sample/display synchronization,
  reset and stop, then an actual NVC analysis failure with no known output.
  The UI uses the current engine clock as a lower bound, so the test reads
  actual engine/session timestamps instead of assuming the first sample is
  at 1 ns. This case keeps the panel open to sample an output-only entity
  manually and exercises the explicit Stop action.

Scrolling uses only identified production containers (`editor-tool-menu`,
`hdl-import-scroll`, `hdl-sim-scroll`). Screenshots, accessibility trees and
geometry checks capture reachable actions while the keyboard is present.
Canonical EN/PT catalog labels locate controls without a dedicated test tag.

For execution, the runner starts the real Mac companion handlers on loopback
with a newly generated, temporary pairing token. NVC and Yosys must be installed.
The token stays in ignored local harness resources and expires when the runner
closes the server; it is omitted from the results/audit JSON. The audit records
actual handler operations, source hashes, outputs, times and failures. It must
confirm both import formats, the Yosys error, three temporal samples, a reset
to zero, stop and the NVC `analysis_failed` result. Both XCTest scenarios must
pass without skips, and supplied/installed artifact hashes must match.

Physical devices, LAN/TLS and every HDL feature remain outside this focused
proof. The separate 44-row desktop differential oracle covers binary import
semantics; these native scenarios cover the compact tool workflows.

The producer catalog must include `action_hide_keyboard`. After editing each
tool field, the harness observes the software keyboard, checks the production
`editor-tool-hide-keyboard` control at 48×48 points inside the visible window,
taps four points above its lower edge, and requires the keyboard to disappear
while the same tool and exact draft remain. The edge touch tests the area that
previously overlapped the source field, after the full control passes its
geometry and hittability checks. It does not use a keyboard shortcut to dismiss
the keyboard or relax the
24-point scrolling guard. Full XML reads also wait for the native text field
without modifying the shared harness used by other test owners.
