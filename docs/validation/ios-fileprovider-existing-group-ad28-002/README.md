# Existing-group AD28 differential: preserved FAIL

The single authorized exact-bundle reinstall and unchanged bootstrap remain **FAIL**: 16.731 seconds, one test, one failure, zero passes/skips, exit 65. A pre-existing group and new FileProviderd processing were observed. That sequence did not make registration succeed in this attempt. It does not determine a signing or Simulator cause and does not qualify revocation issue #26.

Fixture source is `11fc44d53b229558fbfc57b66e0b2f2d11a0037b`; harness source is `6630378c89a0c9e0a6b15ee8b6ec275a5cd1aaca`. The [receipt](receipt.json), [terminal record](evidence/terminal.json), [execution](evidence/execution.json), [summary](evidence/xcresult-summary.stdout), [test log](evidence/test.log) and [command ledger](evidence/commands.json) retain the result. `scope.json` is the original initial `PREPARING` record, not the terminal result.

## Preserved bytes and state

The before, after-install and after-run snapshots identify the same group directory `5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4`, distinct metadata UUID `C4A78BD1-44B3-4590-B045-A3AC1883D566`, identifier `group.thalesmms.logisim.validation.revocationfixture`, metadata hash and birth time `06:58:17.691188Z`. The before snapshot at `07:27:29.439514Z` precedes reinstall `07:27:29.441169–29.846883Z`.

The producer, before-install, after-install and after-run identity records agree on app tree `8b2577db…`, provider tree `d6e6ee49…` and their executable hashes. The installed bundle path changes from `F2F94231-…` to `8EA27060-…`; the bytes and extracted group entitlements remain equal. Manifest SHA-256 is `bda24c74410814276f00474a689ae95508deb7330cc0eacae9f55bf54ec7388a`. [source-products-after.json](evidence/source-products-after.json) also records unchanged producer fingerprints.

The actual archived backend contents decode to the same 552 bytes before, after install and after run, SHA-256 `54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88`. Standalone `.circ` copies are included. Authentication, generation and schema remain unchanged. Before and after-install database bytes are identical. The final database hash changes because new events and `nextSequence` are appended; the verifier does not mistake that for a backend-content change.

The [public baseline](evidence/attachments/612F21E9-982A-4C8C-A893-00DBFD3CDEBD.txt) preserves events 1–3 from the preceding FAIL. The [prepared value](evidence/attachments/78902D97-B2D4-4825-A48F-3DE9B2893C1E.txt) adds event 4. The [failure](evidence/attachments/E077909C-7D88-466C-89C8-900CA7E99D8B.txt) and [terminal value](evidence/attachments/67952F8E-E351-4769-9F2B-D22F7B726E5E.txt) retain the exact old prefix and add, from PID 86977:

```text
4 prepare
5 domain.registration.requested
6 domain.registration.failed: -2001 → -2014, termination=complete
```

Inspect was not reached. No domain count, successful registration, extension execution, fetch, authentication denial, Save, revocation or retry is qualified. All earlier FAILs remain preserved in original commits `124d5ef4` and `f2b4cde8`.

## New discovery and ordered rejection

The selected indices are **zero-based in filtered arrays**, not offsets in an unfiltered system trace. [discovery-selected.json](discovery-selected.json) preserves complete selected records; [numbered extract](discovery-numbered-extract.txt) makes their source and order explicit.

| Source / index | UTC | Observation |
| --- | --- | --- |
| New-discovery / 631 | 07:27:29.784735 | PKD installs plugin `F8E78B69-61F8-495B-A993-E0BB85E025F4` during reinstall. |
| New-discovery / 958 | 07:27:30.127311 | FileProviderd PID 68875, thread 9903950, queries the exact group, class 7. |
| New-discovery / 960 | 07:27:30.128916 | ContainerManagerd emits a token for PID 68875 and the group path. |
| New-discovery / 961 | 07:27:30.129291 | The same FileProviderd/thread logs a result containing the exact group, metadata UUID and directory. |
| New-discovery / 962 | 07:27:30.129652 | FileProviderd says the extension has no container for that document group and ignores it. |
| New-discovery / 963 | 07:27:30.129717 | FileProviderd reports descriptor creation failure. |

These records demonstrate new processing beyond a PKD registration notification. A logged group-query result is followed by rejection; it does not explain the implementation's data flow or why the result did not produce a descriptor. The later UI Register still returned `NSFileProviderErrorDomain / -2001`, with captured underlying `-2014`. The [Apple case](https://developer.apple.com/documentation/fileprovider/nsfileprovidererror/code/applicationextensionnotfound) and SDK classify that underlying result as no launchable non-UI extension for the domain's app bundle. They do not identify the cause here.

## Observed declaration and entitlement messages

Full-history index 17, `07:27:29.533311Z`, is an **installcoordinationd placeholder dictionary**. It prints DocumentGroup and SupportsEnumeration directly under `NSExtension`, without an `NSExtensionAttributes` dictionary. The archived producer plist and installed Xcode template have the same direct-key structure; exact values, source hashes and template lines are in [configuration-structure.json](configuration-structure.json). This is not a claim about normalized PlugInKit attributes or which structure FileProviderd consumed internally.

Full-history indices 2 and 18 contain CoreSimulatorBridge messages saying the app/provider executables “had no entitlements.” The effective codesign entitlement outputs independently contain the expected group and their verification commands returned zero. Both observations are preserved; this archive does not resolve their difference into a signing diagnosis.

## Portable verification and preservation

```sh
python3 verify-archived-evidence.py
python3 test-verifier.py
```

The verifier uses only this folder. It checks hashes and bytes, identities, exact backend preservation, old event prefix and new events, the diagnostic chain, command outputs, deterministic gzip, source declaration, PKD identity and query-result/rejection ordering. The gzip files preserve both raw unified-JSON SHA-256 values; decompression recovers the exact original bytes. Historical host and Simulator paths are metadata and are never followed.

The five verifier checks run on relocated temporary copies. They accept the intact archive and reject a causal signing claim, swapped query-result/rejection order, duplicate inventory entries and changed backend bytes, even after updating the relevant inventories/checksums. They do not run the archived execution driver or any native command.

[inventory.json](inventory.json) records every copied source and included/excluded attachment. The provided driver is archived both from `/tmp` and the output's identical `execution-driver.py`; it is provenance data and was **not run during archiving**. The reused harness source, original compiled-harness fingerprints, manifest, source snapshot, signatures, entitlements, group metadata, public UITextView values, trees and six PNGs are included. Executable bundles, raw xcresults, video and binary automation snapshots are excluded. Original outputs, prior archives, source, main/canonical and device state were not changed by this archival work.
