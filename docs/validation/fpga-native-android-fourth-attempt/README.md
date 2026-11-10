# Fourth Android FPGA attempt rejects incomplete board XML

The single authorized run on `emulator-5558` ended **FAIL / NATIVE_FAIL /
NOT_QUALIFIED** after 91.49 seconds. Pairing succeeded. Board import failed;
mapping, project export, synthesis, compiler-error and cancellation cases did
not run. No retry or physical programming occurred. Issue #65 stays open.

The harness was `51a6a01c93e2195d40c2ee98e84365b262da97e3`. The app and test
producer remained `3a0a01f1af05dd3fea75d5a1499c762d039c478a`; the manifest and
snapshot helper remained `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`. The observer
SHA-256 remained `8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634`.
The three APKs passed compiled package/target, source, size and hash preflight.
All three approved APKs were reinstalled to replace intervening diagnostic
artifacts and verified before UI. No product build was performed.

| APK | Approved and observed SHA-256 |
| --- | --- |
| E2E app | `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| E2E tests | `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Snapshot helper | `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |

The intended board text contained 535 UTF-8 bytes. Captures 7–11 retain only its
first 245 bytes, ending inside `<Button Label="A`. Starting with capture 8,
the product shows `Unable to import the FPGA board XML: Unterminated XML
attribute.` The terminal `NoScrollable` assertion occurred later while the
driver searched for Validate mapping. The mechanism that stopped text entry at
245 bytes is undetermined. The intended board fixture is valid; the AND gate
input/output positions match the real core's geometry. The saved editor also
recognizes `c_main · VERILOG` with three physical signals before import fails.

All twelve focused SDK captures completed with PID 19581 before and after,
zero deadline overrun and no root-unavailable result. The independent review
checks each raw Bundle against its JSON and decoded XML. The host advertises
only `fpga-synthesize`; its audit contains zero plans, submissions, cancellation
requests, handler events and jobs. Capability GETs are outside that audit.
No native synthesis or EDA error handling is established by the empty job audit.

The owned host on port 60113 closed and its unchanged reverse mapping was
removed. Post-run checks recorded no listener or own mapping and unchanged
installed APK hashes. The 5558 lease was returned. Neither 5556, port 8042,
the normal app, a programmer nor a physical board was operated.

The raw lease receipt incorrectly describes an open FPGA sheet. Both final
hierarchies instead show the editor without that sheet and with `Operation
cancelled.` This discrepancy is recorded in `attempt-summary.json`; the raw
receipt remains unchanged. The retained evidence does not identify the exact
event that dismissed the sheet. Collected workspace checkpoints and TAR files
are context from the isolated E2E workspace, not FPGA artifacts.

The prepared driver now uses the existing public **Import board file** action
and Android's document picker. It stages a nonce-named XML, verifies the complete
bytes, requires the canonical MediaStore row to be published in Downloads as
XML, observes the new board name and normalized device text in the tool, and
verifies unchanged source bytes before mapping. The Android contract explicitly
records this adapter in place of paste/read; XML-paste remains unqualified.
The app, observer, circuit fixture and later job oracles are unchanged. The
existing native picker bounds and fixed setup observations cannot renew the
synthesis/cancellation deadlines.

All 45 focused checks passed, including incomplete source before/after import,
unpublished or late Downloads metadata and a stale board card. A peer review caught an
initial `OPENFPGA`/`OPEN_FPGA` display mismatch before execution; its literal
public-text regression failed before that correction and passed afterward.
The green log's temporary `FPGA FAIL` line belongs to a mocked cleanup-negative
test. The corrected file-import flow remains **Native NOT_RUN** and requires a
new explicit device lease. No further native run was performed here.

`copy-chain.json` preserves every native output and non-APK preflight file byte
for byte, with origins, sizes and hashes. Raw logcat whitespace is preserved by
two file-specific attributes. `SHA256SUMS` covers the complete packet. The three
earlier failed attempts retain their separate sources and results.
