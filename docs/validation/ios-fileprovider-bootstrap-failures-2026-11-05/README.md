# FileProvider bootstrap: two preserved failures

Both native attempts remain **FAIL**. Neither qualifies `BOOTSTRAP_REGISTER` or authentication denial.

| Attempt | Signed fixture / test source | Native result | Last demonstrated boundary |
| --- | --- | --- | --- |
| B25 001 | `ff99aadb` / `10099e21` | 1 failure, 0 passes, 0 skips; 36.779 s; exit 65 | Fixture launch SIGTRAP before Prepare/Register |
| B25 002 | `48412de3` / `628562d5` | 1 failure, 0 passes, 0 skips; 16.147 s; exit 65 | Prepare succeeded; registration callback returned `NSFileProviderErrorDomain / -2001` |

Both ran on the reserved arm64 iPad Simulator B25, iOS 27.0 (24A434). The signed fixture artifacts were built with SDK 27.2. Full pins, fingerprints and boundaries are in [receipt.json](receipt.json).

## Observed boundaries

Attempt 001's fixture process, PID 11986, trapped with `EXC_BREAKPOINT / SIGTRAP`. The first faulting frame was `___UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke`. The test failed while waiting for `fixture.evidence`, before its first Prepare tap. The [original IPS](evidence/001/RevocationFixture-original.ips), [log](evidence/001/test.log) and [summary](evidence/001/xcresult-summary.json) remain failure evidence. This was the fixture's own launch failure, separate from earlier `backboardd` compositor failures.

Attempt 002 launched the scene-based fixture, PID 34488. A real Prepare tap produced a public UITextView snapshot with event 1 `prepare`, generation 1, authentication allowed, 552 bytes and SHA-256 `54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88`. The [built store source](evidence/002/fixture-source/Shared/FixtureStore.swift) resolves the fixture App Group and writes/reads its database; the public evidence therefore establishes the control app's own storage access. It does not establish extension execution or cross-process sharing.

Register was tapped next. The [public terminal evidence](evidence/002/attachments/30C62F3C-AEC4-4300-9141-34EDB6A71DE2.txt) records events 2 `domain.registration.requested` and 3 `domain.registration.failed`, with domain `NSFileProviderErrorDomain`, code `-2001`, and the exact visible message “The application cannot be used right now.” The prepared state remained unchanged. [Prepared screenshot](evidence/002/attachments/E340A20D-D326-4FBF-B015-9B5441B28F99.png), [terminal screenshot](evidence/002/attachments/1547A687-D582-44A0-8608-77A5CA8AC1FC.png), accessibility trees, test log and raw local xcresult summary are preserved.

The test stopped on that real callback error. Inspect was not reached; **domain count was not observed**. No successful registration, extension/fetch, Files selection, denial, eviction, Save or grant-revocation gate passed. The fixture's visible `Native scenario: NOT_RUN` label refers to its full denial scenario; it does not negate the observed Prepare and failed registration attempt.

The recorded cleanup terminated only the task-owned fixture, exit 0. The [post-run identity record](evidence/002/installed-after.json) matches its installed fixture to the signed artifact and the main app to the [earlier archived FINAL reference](evidence/main-final-peer-reference.json): source `6cef0542`, app tree `d987d43d…`, executable `198a06dc…`. The test launches only the fixture. These records establish matching main-app bytes afterward, not a fresh before-run measurement or an assertion about all main-app data/state.

## Static configuration census

This census uses only the archived built source and the numbered [local primary excerpts](primary-local-extracts.txt). Whole-source hashes, paths and line ranges are in [primary-local-index.json](primary-local-index.json).

| Check | Evidence | Finding |
| --- | --- | --- |
| Error category | SDK `NSFileProviderError.h:62–65` | `-2001` is `NSFileProviderErrorProviderNotFound`: the manager found no provider in the application. This does not identify why lookup failed. |
| Extension declaration | Built `Config/Provider-Info.plist:13–16`; template `TemplateInfo.plist:151–164` | Extension point, DocumentGroup, principal-class pattern and enumeration flag match the iOS File Provider template. |
| Optional UI extension | Template `TemplateInfo.plist:33–60` | Template offers a UI extension, enabled by default. This fixture has only the non-UI extension. The template option does not demonstrate a missing requirement. |
| Embedding and group | Built `project.yml:20–39`, both entitlements, store constants and Provider plist | The extension is embedded; bundle identities and the group agree. Archived extracted entitlements agree with these records. |
| Replicated API | Built control app domain initializer and `NSFileProviderManager.add`; template Swift:5–8; domain header:117–139 | The initializer/API pattern matches; the domain contains no `/` or `:`. SDK availability includes iOS 16; deployment is iOS 17. |
| Scene lifecycle | Built app plist:27–34 and control app:6–22; Scene Lifecycle template:19–31 | One declared scene configuration creates a retained `UIWindow(windowScene:)`. Attempt 002 reached the control UI. |

No demonstrated configuration/API divergence explains this callback error. In particular, the error alone establishes neither unsupported Simulator behavior nor a signing, Team or App Group fault. The static census does not inspect the runtime extension registry or establish effective signing eligibility. No patch, profile/flag change, retry or device query was made.

## Portable verification

From any copied checkout or standalone copy of this folder:

```sh
python3 verify-archived-evidence.py
```

The standard-library verifier checks the complete checksum census, copied bytes, signed artifact/installation fingerprints, extracted entitlements, source hashes, both failure counts, first IPS, exact public event order/error and preserved backend state, cleanup target, main-app reference, screenshots and the four-literal-only test delta. It reads only files inside this folder. Historical host and Simulator paths in copied records are metadata and are never followed.

[inventory.json](inventory.json) identifies each original source and included/excluded attachment. Raw xcresults, videos, binary automation snapshots, DerivedData, generated projects and executable bundles remain local and are excluded. The original outputs were not modified. Archiving performed only file reads/copies and local xcresult summary extraction; it ran no build, signing, Simulator operation, registration or UI action.
