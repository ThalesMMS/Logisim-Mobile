# Modify request diagnostics

This producer adds event details to the standalone FileProvider fixture based on
`601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd`. It does not change which mutations the
fixture accepts. The control app, store/schema, initial 552-byte circuit, item
policy, configuration, entitlements, signing builder and domain identity remain
byte-identical to that producer.

Auth004 failed during one preparatory `evictItem` operation, before Files opened.
The preserved request `961C9B1C-1E0E-433B-B27B-275A7ED07291` has a started/failed
pair at sequences 83/84 and error `NSCocoaErrorDomain / 3328`. The UI exposed the
fixture's contents/URL guard rejection. Those records do not contain the fields,
options or contents-URL presence. They therefore cannot establish which operand
of the conjunction failed. The failed native run remains failed; its replayable
archive is original commit `8ed919cc5e7d24ab68f754e0724063eed1eac054`.

The `provider.modify` started and terminal events now include these observations:

| Detail | Meaning |
| --- | --- |
| `changedFieldsRaw` | Complete unsigned decimal mask supplied by FileProvider. |
| `changedFieldNames` | Recognized fields in ascending bit order, comma separated. |
| `changedFieldsUnknownMask` | Bits outside the SDK's eleven known fields. |
| `newContentsPresent` | Whether FileProvider supplied a contents URL; no URL or bytes are logged. |
| `modifyOptionsRaw` | Complete unsigned decimal options mask. |
| `requestedContentPolicyPresent/Raw/Name` | The incoming item's optional policy; absence stays distinct from `inherited`. |
| `fixtureDeclaredContentPolicyRaw/Name` | The unchanged policy declared by the fixture's item getter. It is not a claim about an effective system policy. |

The same immutable details accompany the request UUID through completion,
failure, denial or cancellation. Non-modify requests keep their original event
details. Reading the optional incoming property adds no snapshot or file I/O.
No metadata fields are accepted, ignored, persisted or acknowledged by this
change. Unknown masks and policies remain visible in the raw values.

The names and raw field bits are checked against the local iPhoneSimulator SDK
27.2 `NSFileProviderItem.h`. Its `NSFileProviderContentPolicy` enum also defines
macOS-only values 1 and 3; the decoder can name those raw values without using
unavailable Swift cases or accepting such a policy. Apple's
[replicated extension API](https://developer.apple.com/documentation/fileprovider/nsfileproviderreplicatedextension)
supports content and metadata modification requests. The exact failed request
must be observed before any functional change to this fixture.

Run from the repository root:

```sh
python3 scripts/ios_fileprovider_revocation/run-modify-diagnostic-checks.py
python3 scripts/ios_fileprovider_revocation/run-host-checks.py
python3 scripts/ios_fileprovider_revocation/verify-fixture.py
python3 -m unittest discover -s scripts/ios_fileprovider_revocation -p 'test_*.py'
```

The first command runs 79 literal diagnostic checks and proves the mutation body,
content policy and all other existing fixture files match producer 601. These are
host/source checks, not FileProvider execution. The separate build manifest must
name this new producer and its new app/extension hashes. Producer 601 and its
review are historical evidence; they do not authorize substituting this binary
or claiming an authentication-denial result. No installation, domain refresh,
registration or native execution is part of this source/build unit.
