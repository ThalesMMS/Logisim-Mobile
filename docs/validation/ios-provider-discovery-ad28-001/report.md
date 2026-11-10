# AD28 File Provider discovery: read-only census

Only the exact app `thalesmms.logisim.validation.revocationfixture` and extension `thalesmms.logisim.validation.revocationfixture.provider` were queried on `AD28C68F-21D3-4FA6-BCD7-383AE2A1D372`. The installed fixture is the existing 11fc artifact. No app/provider launch, installation, domain registration, UI action, test, erase, configuration/entitlement/signature change, or source edit was performed. `simctl spawn` executed only the documented `pluginkit` match/help and `log show/help` query helpers.

## Direct finding

The extension is registered in PlugInKit with the correct containing app and extension point, but historical `fileproviderd` explicitly failed to construct its descriptor because it could not fetch the declared App Group container and therefore could not deduce the provided-items URL. This is a concrete failure during descriptor discovery, beyond physical presence of the appex. The root reason for that container fetch failure, and whether a descriptor was retried/refreshed afterwards, are not established by these read-only observations.

All timestamps below are from one retained unified-log JSON query rendered in UTC. `index` refers to the original array, not a renumbered filtered list.

| UTC timestamp | Index | Process | Observation |
|---|---:|---|---|
| 06:57:41.938833 | 2 | CoreSimulatorBridge | Placeholder entitlement extraction says the app executable had no entitlements. |
| 06:57:41.946209 | 18 | CoreSimulatorBridge | The same placeholder path says this for the extension. |
| 06:57:46.955156 | 527 | pkd | Plugin INSTALLED, exact extension ID, correct path, contained in the exact app ID. |
| 06:57:51.039725 | 758 | fileproviderd | Query for the declared `group.thalesmms.logisim.validation.revocationfixture` appGroup. |
| 06:57:51.046246 | 760 | fileproviderd | `[ERROR] Error fetching group container ...: (null)`. |
| 06:57:51.050846 | 761 | fileproviderd | `[ERROR] Cannot deduce the provided items URL` for this exact extension/path. |
| 06:57:51.050903 | 762 | fileproviderd | `[WARNING] Failed to create descriptor for extension` for that ID/path. |
| 06:58:17.685790 | 1257 | containermanagerd | AppGroup schema updated from 0 to 2. |
| 06:58:17.686337 | 1258 | containermanagerd | Token issued to PID 69225 for that group, with an AppGroup container path. |
| 06:58:29.893873 | 1588 | fileproviderd | `No provider found with identifier (null) for calling bundle` followed by the exact app ID. |

The retained JSON identifies PID 69225 as the installed `RevocationFixture.app/RevocationFixture`, with its first matching process event at 06:58:15.707522. The later successful group token does not demonstrate successful descriptor creation or index refresh. No successful descriptor event appears in this exact-ID-filtered collection; that absence alone does not prove there were no retries or redacted messages.

## Current registry and static cross-checks

- `simctl appinfo` succeeds for the exact app and reports `GroupContainers = {}`. `get_app_container APP groups` succeeds with empty stdout. These are registry-query results, not proof that no physical group directory exists; the historical MCM event supplies a path later in the timeline.
- Device `pluginkit -m -A -D -v -i EXT` returns one plugin. Adding the documented `-p com.apple.fileprovider-nonui` match returns the same one. Verbosity repeated three times exposes UUID `E8471350-97AE-41DE-B670-F66B2FE4EA4B`, registration timestamp `2026-11-05 06:57:46 +0000`, correct extension path, SDK/point `com.apple.fileprovider-nonui`, and correct Parent Bundle path.
- The local `pluginkit(1)` manual documents `-m` as matching registered plugins **without host-specific restrictions**. Thus this match is not proof that File Provider accepts the extension as launchable. `-A` selects all versions; `-D` selects duplicate physical instances; neither option changes an election. Only match flags were used.
- The independent static review at `/tmp/logisim-provider-discovery-ad28-static-001/review.md` found installed/produced appex files byte-identical; matching principal class `RevocationProvider.RevocationFileProvider` in the actual debug dylib; arm64/iOSSimulator platform; NSObject plus `NSFileProviderReplicatedExtension`; and the expected group entitlement in app and extension. `codesign --verify --strict` passed. Those facts do not prove runtime class initialization or discovery.
- The principal-class form, replicated protocol, four plist fields and two-argument domain initializer agree with the Xcode 27.2 template and SDK. The separate Apple/SDK API census found no documented extra flag required by this configuration, and distinguishes item indexing/capabilities from extension discovery.

The current signed files contain the group entitlement, while the earlier placeholder extraction logged no entitlements. These observations concern different consumers and times. They do not establish missing signing, missing TeamIdentifier, missing DER data, or Simulator incompatibility as a cause. No signing/configuration remedy or rerun is proposed here.

## Evidence and scope

`commands.json` retains exact argument arrays, query exits, stream sizes and SHA-256 values for 13 native query/help commands and three exact-group filesystem read helpers. Query commands exit 0. The two help invocations returning 2 and 64 print usage and are preserved as such. The historical query returns exit 0 with a `getpwuid_r` warning on stderr; that warning belongs to the later read-only query, not to the failed fixture execution.

- `historical-registration-and-lookup-exact-ids.stdout`: 1632 raw events, SHA-256 `251208aa01519842b005dd2b50b5454427b8e04f70965d9238cc3d7eb3a03596`.
- `historical-registry-relevant-events.json`: selected events retaining original array indices.
- `historical-registry-evidence.txt`: readable exact-message excerpt, SHA-256 `af2ea7e6a0cbda1716b48b9e16f1db15819d071f5e487494a2f5079b79530ead`.
- `provider-registry-match-verbose3.stdout`: containing-bundle and point metadata.
- `app-installed-Info.plist` and `provider-installed-Info.plist`: unchanged metadata copies.
- Static commands, exits and hashes: `/tmp/logisim-provider-discovery-ad28-static-001/manifest.json`.

The original UI result remains FAIL 17.003 s with NSError chain -2001 → -2014. Its separate immutable archive is commit `124d5ef4f937780f3d972c390d492e3f2c8b965a`, under `docs/validation/ios-fileprovider-ad28-diagnostic-001/`. This census adds read-only evidence; it is not another native case or a correction of that FAIL.

Primary references used by the coordinated API review: Apple `NSFileProviderError.Code.applicationExtensionNotFound` (https://developer.apple.com/documentation/fileprovider/nsfileprovidererror/code/applicationextensionnotfound), File Provider overview (https://developer.apple.com/documentation/fileprovider), and the current Xcode iOS File Provider Extension template. SDK `NSFileProviderDomain.h` distinguishes the replicated two-argument initializer from the nonreplicated three-argument initializer; `NSFileProviderReplicatedExtension.h` declares the protocol. Those references do not expose the internal descriptor-index filtering algorithm.

## Exact-group comparison at the captured query times

Scope ends before the later `ios-provider-existing-group-ad28-002` experiment. That later run is not part of this archive. “Current” in the original query filenames means current at that query timestamp, not the filesystem state after the later experiment. No live path is needed to verify this archive.

The captured path is `/Users/thales/Library/Developer/CoreSimulator/Devices/AD28C68F-21D3-4FA6-BCD7-383AE2A1D372/data/Containers/Shared/AppGroup/5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4`. Only this exact group was inspected; no other App Groups were listed. Investigators did not call `containerURL`.

The raw MCM plist identifies `group.thalesmms.logisim.validation.revocationfixture`, creator `thalesmms.logisim.validation.revocationfixture`, content class 7, metadata UUID `C4A78BD1-44B3-4590-B045-A3AC1883D566`, schema 2. This metadata UUID matches `[u C4A78BD1-44B3-4590-B045-A3AC1883D566]` in event 1257. It is distinct from the directory UUID `5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4`.

The directory birthtime is recorded as `2026-11-05T06:58:17.691188+00:00`; the MCM plist birthtime is `2026-11-05T06:58:17.691401+00:00`. Historical event 1257 is at `06:58:17.685790+0000`; event 1258 issues the token at `06:58:17.686337+0000`. Those attributes/events do not prove earlier directory absence or authorization at the failed query.

Producer-pinned `FixtureStore.swift:7` names the same group. Lines 54–59 obtain it and append `RevocationFixture`; lines 50–51 use `fixture-state.json` and `fixture.lock`. `ControlApp.swift:69–70` connects the existing Prepare button to `FixtureStore.prepare`; lines 106–118 use the same `FixtureStore()` before `NSFileProviderManager.add`. Captured state is at the corresponding `RevocationFixture/fixture-state.json` inside the observed group. Its base64 contents decode byte-identically to the producer-pinned initial-circuit literal: 552 bytes, SHA-256 `54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88`, also recorded in `state.backendSHA256`. There is no standalone .circ in the captured group listing.

The state has exactly three events: Prepare `06:58:24.548Z`, registration requested `06:58:29.879Z`, and failure `06:58:29.901Z`, all process `RevocationFixture:69225`. This is control-backend diagnostic data, not a File Provider export, Main import, system-dispatched provider read, revocation or successful registration.

**Precise distinction:** the failed `fileproviderd` fetch identifies the same group but resolves no filesystem path or container UUID. Thus it is not established that the daemon resolved this later directory at failure time. Matching source and metadata do not distinguish missing/unavailable container from an authorization/filtering failure. The retained error is `(null)`, without ENOENT/EACCES/EPERM. Permission and signing causes remain unproved. No reinstall/retry outcome is inferred from this historical001 dataset. No experiment plan is included here.

Metadata-query stdout SHA-256: `5a5438bf598ef059e2864cbc15825a8d48a88c936dd65b054822c8b8e87fadcd`. Fixture-state-query stdout SHA-256: `8cf377c7cfdccf752e05046341829b9e651c558cbddc5a832f8e5ace4401fb06`. The raw plist and state JSON were captured only after hashes matched those earlier reads; no Prepare or Register was invoked by the investigation.
