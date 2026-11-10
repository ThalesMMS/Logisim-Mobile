# Android FPGA user flows

`run-android-fpga.py` uses the existing Android E2E driver and public editor
controls. It does not install APKs, clear app data or build the product. Execution
requires an explicit device serial, an exclusive device lease, an already
installed matching E2E APK triplet, its build receipt and the immutable source
checkout that produced the app's localization catalog.

The runner covers these software flows:

- Pair a real loopback companion through the confidential token field.
- Import the AND circuit and use the public **Import board file** action to
  open its complete XML through Android's document picker. Reject incomplete mappings and
  duplicate physical pins, then map `p_a`, `p_b`, `p_y` to pins 21, 22, 23.
- Export a real project ZIP through Android's document picker. Check its CRC,
  file names, HDL ports, pin constraints and synthesis script.
- Review and execute synthesis. Compare the app's exported HDL hashes with the
  files received by the Mac, inspect actual netlist ports, and require the UI to
  show the host's artifact size and hash.
- Observe a real nextpnr package-pin error using the separately declared pin
  999 fixture. A failed preflight cannot substitute for this tool failure.
- Cancel one actually queued or running job through the UI. If the job completes
  first, the cancellation case fails and remains unqualified. The fixture adds
  no delay, fake executable or repeated submission to win this race.
- Export complete circuit XML after each case and require bytes identical to
  the actual baseline exported before mapping.

`fpga-actions-v1.json` defines the shared Android/iOS contract. Its standalone
preflight/export steps are folded into the Android synthesis case. Its Android
adapter replaces the shared paste/read actions with native file import; it does
not qualify the XML-paste controls. The optional
successful bitstream case, iOS execution, clock-role controls and physical board
programming remain separate work. This runner cannot close issue #65.

## Prepare without a device

```sh
python3 scripts/run-android-fpga.py --prepare-only --output /tmp/fpga-native-prepared
```

Preparation copies the fixture, contract and driver sources with their hashes.
It opens no socket and runs no ADB, synthesis tool or app. The result remains
`NATIVE_NOT_RUN` and `NOT_QUALIFIED`.

## Execute after reserving the Android device

The Android system locale must be English for the native document picker; the
app controls support English and Portuguese. Start with the editor visible.
The supplied APK directory must use the canonical triplet file names.
If an earlier attempt left its verified host card connected, use the observed
public `Disconnect host` action under the device lease before starting another
pairing. Closing the sheet alone retains that connection.

The driver stages a nonce-named XML in Downloads, verifies its complete bytes,
and publishes that exact canonical path through the media scanner. It requires
an XML MIME type, `is_download=1` and `is_pending=0` within a fixed 30 seconds
before opening the picker. The existing native picker navigation keeps its own
bounded transitions. After selection, a separate fixed 30-second observation
requires the new board name and `OPEN_FPGA · ice40 · hx1k · tq144` in the FPGA
sheet. Successful import hides the XML editor in the product. Another byte
comparison confirms the source stayed unchanged before mapping begins. No
long XML string is typed through Android key events. These setup checks do not
change synthesis or cancellation deadlines.

```sh
ANDROID_HOME=/Users/thales/Library/Android/sdk \
python3 scripts/run-android-fpga.py --execute --serial emulator-5556 \
  --artifacts /absolute/frozen-apk-triplet \
  --build-receipt /absolute/app-build-receipt.json \
  --catalog-root /absolute/immutable-app-producer \
  --output /absolute/new-evidence-directory
```

Use `--case synthesis`, `--case tool-error` or `--case cancel` for an explicitly
partial run. The default runs all three. A native pass still establishes only
these software flows on the selected simulator/device.

The host registers only `fpga-synthesize`, with real Yosys, nextpnr-ice40 and
icepack executables and their hashes. It never registers `fpga-program`. A
temporary, non-rebinding `adb reverse` mapping lets the app keep its existing
HTTP loopback policy. Cleanup closes only the runner's host and removes only
its unchanged reverse target, even if audit or artifact collection fails.

Each output directory retains APK identities, source and fixture hashes,
derived board XML identities, native trees/screenshots, actual circuit exports,
the project ZIP, reviewed parameters, job IDs, host requests/stages/logs and
verified artifact bytes. The bearer token is ephemeral and excluded from the
host audit. Plan tokens are retained only as hashes. A prior attempt is never
overwritten.

## Focused checks

```sh
PYTHONPATH=scripts:. python3 -m unittest \
  scripts.test_fpga_native_contract \
  scripts.test_android_fpga_scenarios \
  scripts.tests.test_fpga_native_companion -v
```

These checks verify fixture integrity, negative selectors/oracles, fixed waiting
deadlines, cleanup, and real HTTP/Yosys host behavior. They do not run the app.
The corrected file-import flow is **Native NOT_RUN**. Four earlier attempts
remain failed and unqualified; the [fourth receipt](../../docs/validation/fpga-native-android-fourth-attempt/README.md)
records the incomplete board XML observed before this correction.
