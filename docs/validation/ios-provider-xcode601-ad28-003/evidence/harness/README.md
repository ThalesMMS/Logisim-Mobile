# FileProvider registration bootstrap preparation

**Native execution with the current artifact: NOT_RUN.** One isolated XCTestCase prepares the fixture backend, registers its domain and inspects the public domain count. It does not register anything until a coordinator explicitly runs it on a reserved Simulator.

The UI flow, callbacks, waits and public snapshot decoder remain byte-identical to `6630378c89a0c9e0a6b15ee8b6ec275a5cd1aaca` from `fixture.launch()` onward. Only prelaunch artifact admission and resources change. The separate fixture source is `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd`. `ProvenanceSignedFixture.json` is its byte-identical producer manifest, SHA-256 `c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159`:

| Product | Bundle tree SHA-256 |
| --- | --- |
| Fixture app | `e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5` |
| Provider extension | `e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f` |

`ProvenanceArtifactReview.json`, SHA-256 `254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8`, records ROOT's internal admission for `LOCAL_SIMULATOR_ONLY` and `ONE_PREPARE_REGISTER_INSPECT`. Its exact App and Provider dictionaries contain only `application-identifier` (`P4VGX44GD8.` plus the exact role bundle ID) and the single fixture application group. This literal prefix is admitted for this Simulator experiment; it is not Apple Developer Team approval, a profile, a certificate or release approval. Both `developerTeamApproved` and `releaseApproved` remain false.

The admission helper binds both resource hashes, source/tree pins, role bundle IDs, the exact two-key dictionaries and generated application identifier, main-only Simulator section ownership and packaging metadata, and empty code-signature entitlements. The producer manifest retains `simulatorEntitlementReviewRequired=true`, `REQUIRED_BEFORE_NATIVE` and `NOT_RUN` evidence; the sidecar does not rewrite its status or claim a native result. Extra entitlements, including a team identifier, are rejected.

The producer's SDK 27.2 arm64 Simulator build and ad-hoc signature checks are separate evidence. The [earlier provenance-update receipt](../../docs/validation/ios-fileprovider-bootstrap-error-chain-provenance-2026-11-05.json) belongs to source `11fc44d5`, not the current 601 artifact. Neither build record establishes registration success or replaces a previous failure.

The earlier bootstrap with source `ff99aadb4991933774eed684dda70805de9d0055` remains **FAIL**: one test, one failure, 36.779 seconds, exit 65. Its fixture process trapped in `___UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke` while the test waited for `fixture.evidence`, before any Prepare or Register tap. That failure belongs to the earlier fixture launch; it qualifies no domain or backend action and is not replaced by this provenance update.

The second B25 bootstrap with fixture `48412de3` and test `628562d5` also remains **FAIL**: one failure, 16.147 seconds, exit 65. Prepare succeeded, then the registration callback returned `NSFileProviderErrorDomain / -2001`. Inspect was not reached; the underlying cause and domain count were not determined. Both failures remain preserved in archive commit `f2b4cde813727ee5d5a844cfbf7894bf071abc09`.

The existing decoder ignores the new optional `errorDiagnostic` field. Raw public evidence attachments can retain it without adding a gate, interpreting it as success, or retrying registration. The current artifact and its internal admission do not establish a cause for the previous failure. A future differential run requires its own recorded preflight and result.

The test verifies the provided manifest and internal review, then attaches both. It does **not** fingerprint the installed app by reading its binary. Before any future run, the coordinator must fingerprint the actual app and embedded extension against these records, record the reserved device/runtime and install outcome, and preserve their fingerprints afterward. No runner is supplied that could install, launch or register implicitly.

## Public UI contract

| Identifier | Role/action | Expected observation |
| --- | --- | --- |
| `fixture.status` | UILabel / StaticText | Literal callback status or actual NSError domain/code and message |
| `fixture.evidence` | Noneditable UITextView | Public value with App Group/Domain/Item header and complete `SNAPSHOT` JSON |
| `fixture.prepare` | UIButton | New `prepare` event and valid backend state |
| `fixture.domain.add` | UIButton | Requested/completed events and successful registration callback status |
| `fixture.domain.inspect` | UIButton | `Matching fixture domains: 1. Native scenario remains NOT_RUN.` |

Only these three action buttons are tapped. The snapshot is read exclusively through the public UITextView value. The test never opens or writes the app's App Group directory, database, private logs or grant data. UI screenshots, accessibility trees and public evidence values are attached at each stage and on termination.

An explicit unprepared message permits the first Prepare. Unexpected headers or malformed baseline JSON fail. If a prepared backend already exists, Prepare must preserve its complete state, including authentication, generation, SHA-256 and byte count. It is not a reset. Registration and inspection must also preserve that state. New event sequences are required; stale registration events cannot qualify the run. A real failure status fails the case without skip or recovery fallback.

Buttons must be unique, enabled, visible and hittable. When necessary, the test scrolls only a single observed UIScrollView in the fixture. Native registration and this artifact's layout and accessibility behavior remain unobserved until execution.

## Isolated harness

`project.yml` contains only the `FileProviderBootstrapTests` UI-test target, the pure admission helper and the two JSON resources. Its `FixtureBootstrap` scheme has no dependency on a fixture or production app target. A future coordinator can stage this folder in a fresh output and generate/build the UI-test harness during an authorized build window, then run:

`FileProviderBootstrapTests/FileProviderBootstrapTests/testPrepareRegisterAndInspectFixtureDomain`

No canonical project, runner, fixture source, App Group or entitlement was changed. This preparation performed only source/data inspection and SDK typechecking, with a module cache under its own ignored output directory. It performed no app build, installation, Simulator query, domain call or UI action.

A passing case would establish **BOOTSTRAP_REGISTER** only: ControlApp access to its App Group backend plus registration callback and domain listing. It would not establish extension execution or cross-process App Group sharing. Files location/enumeration, fetch, Save, authentication denial, eviction and security-scope revocation remain **NOT_RUN**. Those require a later separate gate with actual system-dispatched provider events and public Files selection.
