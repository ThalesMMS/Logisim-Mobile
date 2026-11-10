# Fixture 601: recorded Xcode Simulator build

The single authorized build from `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd` completed **BUILD_AND_ADHOC_SIGN_PASS**. This archive records that build and static byte/log checks; it performs no new build or signing. **At this captured build stage, installation, Native execution and domain registration were NOT_RUN, and generated extras were PENDING_ROOT_REVIEW.** These captured fields do not describe a later admission or execution.

After capture, the coordinator reported that Root approved these exact `601` extras for **one local Simulator test only**, without approving an Apple Developer Team identity or release use. That admission is separate: `scripts/ios_fileprovider_bootstrap/ProvenanceArtifactReview.json` in the `ios-fileprovider-bootstrap-xcode-601` worktree was observed with SHA-256 `254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8`; its source commit was still pending at archive capture. This build archive does not replace that admission record or qualify registration; its original manifest, required-review flag and independent pending-review report remain unchanged.

The [unaltered manifest](evidence/artifact/artifact.json) has SHA-256 `c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159`. Configuration/build, signature display/verification logs, extracted signature-entitlement plists, four Simulator section blobs and four generated `.xcent`/raw DER inputs are preserved. [receipt.json](receipt.json) records scope and limits; [inventory.json](inventory.json) records exact source paths, sizes and hashes.

## Actual section bytes and decoded values

The main executable owns the XML/DER section pair in **both** products. Neither debug dylib owns these sections, as recorded by the producer and independently checked against the original images. Each retained section is byte-identical to its generated Xcode packaging input:

| Product / section | Bytes | SHA-256 |
| --- | ---: | --- |
| App `__entitlements` | 440 | `279c91edc22ba362aeb82088100a50ce07e0b8710de79a13eea38a1737898d10` |
| App `__ents_der` | 191 | `65b1e6a3efe8427d4fa9c133701e5c0bc6294b51e57d1981a706ebf1c696cc96` |
| Provider `__entitlements` | 449 | `db38978790025ed2c514445b0dcb62286331f01cb5a580982c12def85896118a` |
| Provider `__ents_der` | 200 | `6dd8ac249508fe2e9e1aef11c16b43774d0bf00cfca0a04080a1fb74415226a3` |

The portable verifier decodes the actual XML and bounded version-1 DER bytes. Each dictionary contains exactly two keys:

| Product | Complete `application-identifier` value |
| --- | --- |
| App | `P4VGX44GD8.thalesmms.logisim.validation.revocationfixture` |
| Provider | `P4VGX44GD8.thalesmms.logisim.validation.revocationfixture.provider` |

The other key is `com.apple.security.application-groups`, containing only `group.thalesmms.logisim.validation.revocationfixture`. `generatedAdditionalKeys` therefore contains exactly `application-identifier`. The prefix is an observed generated value: its origin is not established and it is **not approved as a Team identifier**. The captured `simulatorEntitlementReviewRequired=true` and `REQUIRED_BEFORE_NATIVE` remain intact; the separate local-test admission does not rewrite them.

## Separate signature channel and unchanged source

The extracted **code-signature** entitlement plists decode to `{}`. Independent XML/DER signature-slot extracts also decode to `{}`. These are separate from the nonempty Simulator `__TEXT` section dictionaries; the verifier never substitutes one channel for the other. Signature logs record ad hoc and `TeamIdentifier=not set`; no `com.apple.developer.team-identifier` key appears in the Simulator dictionaries.

The recorded build invokes Xcode signing with identity `-` and an empty development team. Post-build commands display and verify signatures; they do not replace-sign the products. [Independent byte review](evidence/independent-build-review/report.md) recomputed bundle trees, image slices, section/input equality and CodeDirectory/page hashes. Its report captured prefix review as pending and itself grants no install/registration approval.

All exported fixture files match source pin `601`. The 13 Swift/Config/project files listed in [source-comparison.json](source-comparison.json) are byte-identical to `11fc44d5`; driver/helper tooling differs. The exact pinned driver, Simulator entitlement helper and artifact reader are included as provenance data and are not executed by archive verification.

New executable hashes are recorded identities for this build, not a runtime result:

| Product | Executable SHA-256 | Bundle tree SHA-256 |
| --- | --- | --- |
| App | `39480047426a291e21ca98998e8a7cb4718a021d4ea1f921c9f339ca748a9b49` | `e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5` |
| Provider | `8174906909373b28923998e19c18e8228f1e049fb875c585b6a3e1e1b3f6e393` | `e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f` |

## Portable checks and limits

```sh
python3 verify-archived-evidence.py
python3 test-verifier.py
```

The verifier reads this folder only, checks unique inventory coverage and all byte hashes, compares sections with generated inputs, and decodes XML/DER dictionaries independently of the archived producer helper. It checks the empty signature channel, exact extras and pending-review flags. The negative/relocation checks operate on temporary copies and run no native command.

Whole Mach-O images, app/appex bundles and DerivedData are excluded. Portable verification rechecks retained blobs, inputs, source and reports; ownership/image-slice and code-page claims are preserved independent observations made against the original producer. Build/static checks do not establish runtime trust, GroupContainers access, discovery, registration, denial or a cause for any earlier FAIL. Those failed runs remain unchanged. No producer, source, signature, device, cache or canonical state was changed by this archive.
