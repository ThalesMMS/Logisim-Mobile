# First Android FPGA attempt stops before pairing

The single authorized native attempt used harness `219edebd298d62f4bb12eb0e842fb09e5577c47b`
and product `ce3e061d38cd9851038d1cd8a71557ede9add641` on `emulator-5556`.
It ended **FAIL / NATIVE_FAIL / NOT_QUALIFIED** after 21.29 seconds. The failure
and its original driver sources remain unchanged in `native/`.

The driver expected the confidential accessibility description directly on an
`android.widget.EditText`. The actual hierarchy puts that description in an
`android.view.View` child of the empty password EditText. Both nodes have bounds
`[74,1937][1006,2105]`. The field exists; the driver's selector rejected its
public semantic structure. The screenshot and complete hierarchy are retained.

The host audit contains no recorded plan, submit or cancel request, no handler
event and no FPGA job. GET capability requests are not included in that audit.
The attempt did not reach board mapping, ZIP export, HDL upload, synthesis,
tool errors or job cancellation. None of those cases is qualified by this run.

## Artifact and lease checks

Before any ADB use, the three copied APKs passed compiled-manifest checks for
their package IDs, instrumentation targets, sizes and SHA-256 hashes:

| APK | SHA-256 |
| --- | --- |
| E2E app | `71bafede4e6c9c5ea637d5ad0bb7cce5e366b0842b8f06d32d34143db2277a43` |
| E2E tests | `c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882` |
| Snapshot helper | `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387` |

The app needed an update to the supplied artifact. The two helpers already
matched. Installation and post-failure checks verified all three APKs unchanged.
The normal application and `emulator-5558` were not operated.

The owned host on `127.0.0.1:57931` closed, and its reverse mapping was removed.
The terminal lease receipt records an empty listener check and absent mapping.
The lease returned with the Companion sheet open and the token field empty.
No second native attempt was made.

`preflight/producer-manifest.json` is the original integrated-build manifest.
`build-receipt.json` explicitly records its derivation from that manifest; this
worker did not rebuild the app. APK binaries remain at their frozen source
locations and are represented here by hashes. `copy-chain.json` identifies the
origin, bytes and hash of every copied evidence file.

## Driver correction and remaining validation

The correction resolves each pairing editor through its unique public label,
including a description on a descendant View. It requires the selected token
editor to be enabled, visible, empty and marked `password=true`. After entering
the host address, it dismisses the keyboard and reads the token editor again
before entering the ephemeral credential.

The new regression reproduces the original assertion before the fix. After the
fix, all 39 focused checks passed, including negative cases for a nonempty,
unmasked or clipped token editor. The complete captured hierarchy also resolves
both public field labels offline. These checks do not execute the corrected
driver on Android.

The green test log also prints `FPGA FAIL` for a temporary directory. That is
the mocked cleanup-failure test; it is not another native attempt.

The corrected native flow remains **NOT_RUN**. Issue #65 stays open, and physical
board programming remains deferred until the software work is complete.
