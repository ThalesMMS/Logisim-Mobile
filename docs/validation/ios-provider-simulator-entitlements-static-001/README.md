# Fixture Simulator entitlements: static evidence

This archive separates two representations. The signed main executables contain equivalent XML and DER entitlement blobs. Their Mach-O section tables do not contain the Simulator-specific entitlement sections. These are observations of fixture producer `11fc44d53b229558fbfc57b66e0b2f2d11a0037b` and the byte-identical installed copies after the AD28 reinstall. They do not establish which representation CoreSimulatorBridge consumed or the cause of the File Provider error.

The fixture app and extension were not executed, built, installed, resigned or modified by this inspection. The read-only registry queries in `registry/` were collected separately by the coordinating parent. Main Logisim, B25 and 17e were outside this inspection.

## Observed bytes

| Main executable | SHA-256 | Signature offset / size | XML slot file offset / size | DER slot file offset / size |
|---|---|---|---|---|
| App, produced = installed | `93f5c45e71c1ba64b2789bae430ab117a3152260f17f950bd5d97eab656d08a8` | 39696 / 992 | 40215 / 336 | 40551 / 112 |
| Extension, produced = installed | `5c7d3f12196d954902c4bb814532500e7a82bd83c55da89f2da3504302795c75` | 37088 / 992 | 37616 / 336 | 37952 / 112 |

Both have slot 5 magic `0xfade7171` and slot 7 magic `0xfade7172`. XML payloads are 328 bytes; DER payloads are 104 bytes. The complete XML and DER blobs share SHA-256 `017a1181f1f961c0e685433cec6a39a943307b15091ce1ef694619774749c91a` and `26c2dda714a8d8e38954596706640d3e846180d64a444f4ec9043b024617994c`, respectively. The decoded dictionaries contain only `com.apple.security.application-groups = [group.thalesmms.logisim.validation.revocationfixture]`. The local `codesign --xml` output agrees semantically; `codesign --der` agrees byte for byte with the extracted DER payload.

The CodeDirectories are version `0x20400`, flags `0x2` (ad hoc), SHA-256, with seven special slots and three 16-KiB code pages. The recorded slot 5/7 hashes and all three page hashes match their source bytes. The archive verifier recomputes the retained special-slot hashes. Full code-page hashes require the original executable and are not recomputed by the archive-only verifier.

Eight section inventories cover the app and extension main executables and debug dylibs, produced and installed. Each lacks `__TEXT,__entitlements`, `__TEXT,__ents_der` and `__TEXT,__info_plist`. All section names, ranges and captured `otool -h -l` output are retained. An initial query used the invalid 18-byte name `__entitlements_DER`; the corrected query uses `__ents_der`, and the independent review confirms its absence. The initial typo is not used as evidence of absence.

## Build and registry facts

The original builder passed `CODE_SIGNING_ALLOWED=NO`, `CODE_SIGNING_REQUIRED=NO`, manual signing and empty identity/team to Xcode. It subsequently signed dylibs ad hoc and signed both bundles with the fixture-specific group XML plus `--generate-entitlement-der`. This explains the construction sequence of the observed files; it is not a runtime-trust result.

The six retained linker commands contain debug-section creation, but no creation of `__entitlements` or `__ents_der`. The original build log has no `ProcessProductPackaging` or `Simulated.xcent` line. The complete original build log is retained as deterministic `original-build.log.gz`; its exact source hash and numbered linker extracts are retained in `build-link-extracts.json`; complete build command/signing records are in `original-build-artifact.json` and `registry/build-signing-flags.json`.

The current registry query reports `GroupContainers={}` and an empty group-container result. PlugInKit lists one matching extension with the correct parent bundle and point. These observations do not negate the separate retained MCM lookup/token evidence. The queries do not expose File Provider's internal association check. No entitlement edit, nesting change, Simulator restriction or signing remedy is established here.

## Offline replay

Run `python3 -B verify.py` and `python3 -B -m unittest -v test_static_evidence` from this directory. Verification reads only this archive. Original absolute paths are provenance, not default inputs. Whole executables and debug dylibs are deliberately not included; the archive retains extracted signature blobs, section/header observations and source identities.

For an explicit source-byte replay, use `inspect-binary.py --binary /absolute/path/to/RevocationFixture --expected-sha256 93f5c45e71c1ba64b2789bae430ab117a3152260f17f950bd5d97eab656d08a8 --output /new/output/directory --label app`. It reads the selected file and writes new inspection evidence; it does not execute or sign it. The parser admits the observed thin little-endian Mach-O form and a bounded DER v1 dictionary with string-array values. It is not a general code-signature validator.

REA's complete retained records are in `rea-app-evidence.json` and `rea-provider-evidence.json`. Their sessions were closed after export. Apple’s [code-directory declaration](https://github.com/apple-oss-distributions/Security/blob/main/OSX/libsecurity_codesigning/lib/codedirectory.h) documents the signature representation; the installed `codesign(1)` manual describes its XML/DER display and generation options. Neither source determines why the recorded placeholder consumer reported no entitlements.
