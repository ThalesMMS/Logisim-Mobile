# Fixture 601 on AD28: bootstrap registration PASS

One authorized native **Prepare → Register → Inspect** case passed: **25.384 seconds, one pass, zero failures/skips, exit 0**. This qualifies **BOOTSTRAP_REGISTER_ONLY**. It does not qualify functional provider callbacks, enumeration, fetch, Save, denial, security-scope revocation or completion of issue #26.

The [terminal record](evidence/run/terminal.json), [raw summary](evidence/run/xcresult-summary.stdout), [execution record](evidence/run/execution.json) and [test log](evidence/run/test.log) preserve the result. The [receipt](receipt.json) states the exact scope. `scope.json` remains the original initial `PREPARING` record; the terminal result is PASS.

## Provenance and local admission

The producer is `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd`, manifest SHA-256 `c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159`. The harness is `046ebd30c0f71d7f6bf65ca80ec93b925e772792`; its separate preparation/build records and actual staged source are included. The archived driver has SHA-256 `8d7245a855031b4a29dce225624360e85e7939647fadd49587e9599f51a71107` and was not executed during archiving.

The [Root sidecar](evidence/run/ProvenanceArtifactReview.json) has SHA-256 `254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8`. Its scope is `LOCAL_SIMULATOR_ONLY`, operation `ONE_PREPARE_REGISTER_INSPECT`; `developerTeamApproved` and `releaseApproved` are false. The run copy, source-pinned copy, staged resource, compiled resource and runtime attachment agree byte for byte, as do the producer manifest copies.

The group and old events existed before this installation. The before-install product is the failed `11fc` artifact, app tree `8b2577db…` / provider tree `d6e6ee49…`. After installation and after the test, the products match producer `601`: app tree `e24e3fb5…`, executable `39480047…`; provider tree `e91733f4…`, executable `81749069…`. The producer fingerprints remain unchanged afterward.

Candidate-source, installed and post-run XML/DER Simulator sections are retained. Main executables own the section pairs; debug dylibs do not. Their actual dictionaries contain the exact Root-reviewed generated application identifiers and the one fixture group. Section bytes equal generated Xcode inputs in all three captures. Post-install/post-run code-signature entitlement plists are `{}`; the old before-install code-signature channel contains the group. These channels are preserved separately. The producer/helper `NOT_RUN` and required-review fields describe their static capture, not this later UI case; they are not rewritten.

## Public observations and preserved backend

The group remains directory `5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4`, metadata UUID `C4A78BD1-44B3-4590-B045-A3AC1883D566`, with the same metadata and earlier birth time. Actual archived backend bytes remain identical: **552 bytes**, SHA-256 `54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88`, generation/schema 1, authentication allowed. Before and after-install database bytes match; the final database adds events and advances `nextSequence`, without changing backend data.

The [public baseline](evidence/attachments/AB107088-AB6B-44FF-B59B-3146BD1D1E18.txt) preserves the six events from the two earlier AD28 FAILs. New fixture process `23645` appends:

| Sequence | Event | UTC |
| --- | --- | --- |
| 7 | `prepare` | 08:51:28.363 |
| 8 | `domain.registration.requested` | 08:51:34.293 |
| 9 | `domain.registration.completed` | 08:51:34.394 |

The new completed event has no NSError fields. Historical errors at sequences 3 and 6 remain intact. [Registered evidence](evidence/attachments/8F60315C-C430-47B1-A155-0FBE98F07501.txt) retains the successful callback and unchanged state. [Inspect evidence](evidence/attachments/75BD609B-3A55-47BC-A34B-914DF1F29E5A.txt) reports **one matching fixture domain**, not a count of all system domains. Trees and screenshots preserve each stage. The fixture's visible “Native scenario remains NOT_RUN” label refers to its broader denial scenario and does not negate this bootstrap result.

Cleanup terminated only the fixture, exit 0, and the record returns the AD28 lease. No new cleanup, device query or UI operation occurred during archiving.

## System discovery and process/XPC observations

[Selected full records](selected-events.json) and the [numbered extract](selected-events-numbered.txt) use zero-based indices in the archived bundle-filtered JSON array:

- `851`, 08:51:09.623686Z: FileProviderd returns the existing group/path.
- `857/859`: adds/registers the provider coordination path under that group.
- `862–897`: launch configuration, request, process monitoring and XPC creation are observed.
- `920–922`: provider process PID 23598 logs connection preparation/activation and peer 23580.

The daemon registration at `859` precedes test start and is distinct from the later UI domain-add callback at event 9. Process/XPC activity is an observation of those stages; it does not establish functional FileProvider callback execution, item availability, enumeration or fetch. Raw JSON is retained with deterministic gzip; decompression restores the original bytes and hashes.

## Scope, earlier FAILs and portable verification

The [17.003-second FAIL](evidence/prior-fail-17.003.json), original `124d5ef4f937780f3d972c390d492e3f2c8b965a`, and [16.731-second FAIL](evidence/prior-fail-16.731.json), original `a4ebb123d3076aa8e6bc9aae817294fc4fdd87d4`, remain unchanged. This later PASS does not isolate the cause of either failure. Packaging/signature inputs, generated Simulator metadata, harness admission and elapsed system state differ; no Team or release conclusion follows.

```sh
python3 verify-archived-evidence.py
python3 test-verifier.py
```

The portable verifier uses this folder only: unique inventory/hashes, actual XML/DER dictionaries, Root admission/resources, old/new product identities, unchanged backend bytes, event prefix, new callback, public matching-domain count, log ordering and scope limits. Negative checks and relocation run on temporary copies. Archived drivers/helpers are provenance data, never executed by these checks. Complete bundles, DerivedData, generated projects, raw xcresults, video and binary automation snapshots are excluded. Producer, source, signatures, device/cache, canonical and prior receipts remain untouched by this archive.
