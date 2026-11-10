# Native performance: source plan for the frozen 3a apps

This is a source audit and a prepared capture recipe for issue #48. No device inventory, ADB/simctl call, app launch, process sampling, Native scenario, benchmark, JFR, build, hardware test or quiet-window reservation ran here. Filesystem checks verified the three frozen Android APKs, the 12 iOS bundle files and the selected historical circuit files. `plan.json` retains the pins, file hashes, source hashes and unexecuted next unit.

The product pin is `3a0a01f1af05dd3fea75d5a1499c762d039c478a`. The Android snapshot helper comes from `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`; that helper update did not change app production inputs. Product paths also have no differences between 3a and audit pin `be821079`. The app and helper source identities remain separate.

The iOS sampling recipe requires host fix `597432f9`, which removes the old process-argument query. It retains Libproc executable/PID/UUID identity and records `arguments_collected=false`; discovery enumerates PIDs only. Sixteen synthetic-fixture tests passed, including a negative that rejected the old argument-query path. No existing capture was rewritten or reclassified. The filesystem-only bundle fingerprint was unchanged by this host fix.

## Existing evidence and remaining observation gaps

| Area | Existing source/evidence | Smallest next observation and limit |
| --- | --- | --- |
| Import and serialization | Identified JVM schema-2 timings and the historical-byte functional replay; native files/export cases prove selected outcomes | Existing frozen apps allow process-resource/UI envelopes around import or copy/export. Picker, lifecycle, initialization, codec and observer costs are mixed. There are no app codec begin/end spans to report isolated Native codec duration |
| Drawing | `CircuitDrawing.kt` traverses all wires, junctions and components; shared drawing/export checks exist | Android `gfxinfo` supplies observed frame completion spans and optional GPU deadlines. It does not isolate drawing, recomposition or invalidation. The iOS sampler has no frame/GPU sensor |
| Pan and zoom | Canvas arrows pan only with no selection; otherwise they move selected items. Plus/equal and minus zoom | First qualify a one-way viewport effect outside the sample window. A later keyboard workload can exercise drawing without a new app build. It does not qualify touch/pinch or prove per-event delivery/ACK latency |
| Open-scene memory | Android sampler reads PSS/RSS; iOS sampler reads RSS/physical footprint | A paused input-idle window can record process levels. It does not prove retained memory, peak allocation, leak absence or an allocation rate |
| Memory across replacement/close | Corpus sizes and public document actions already exist | Later, preserve an explicit same-process open/replace/close sequence and quiescence observations. Catalog retention and bounded caches are not automatically leaks; RSS growth alone is insufficient |
| Continuous/background | Run/Pause and lifecycle controls exist; SAP functional tests observe execution and paused counter reads | Later, collect separate SAP running and background process windows with clock frequency and state recorded. Existing short functional waits do not qualify a 20-second resource window or physical energy |
| Motor cancellation | Controller cancels jobs and rejects stale publication; kernel has cooperative checkpoints | The APK has no request/checkpoint/worker-finally timestamps. A paused UI or stable counter is not the time the job stopped consuming CPU. Pointer `ACTION_CANCEL` is a different contract |
| Culling/incremental equivalence | `SpatialIndex` has pure query/equivalence tests, but no caller in the current Canvas drawing path | No delivered Canvas culling optimization exists to compare. Do not import pure 50k-rectangle query timings as frame results or add culling before locating a measured cost |
| Active caches | Remembered bounds/text measurement and a bounded annotation image/drawing cache exist. A drawing-item test checks reuse and invalidation | Gates/SAP do not stress image-cache eviction. A later cache change needs the same document/pixel/state oracles and a measured paired comparison. The old experimental defaults cache was not delivered |

`SimulationRunController.stopWork` calls `cancel` and clears references without a measured join. Kernel cancellation checks begin after elaboration/network preparation. Neither source fact establishes an ANR cause or a cancellation bound. Separate clocks, request identity and completion observations would be required for that measurement. Product instrumentation is not part of this unit.

## Offline readiness qualifier after the failed setup

Receipt `c6edd6b1` preserves one failed Android setup attempt in `docs/performance-evidence/android-3a-input-idle16-001`. `Scenarios.incoming` waited for the mutable `status_file_opened` text. The archived report identifies the expected provider UUID and `gates-16`; the final tree shows a paused editor without that status. Sampling never started, and no canonical Native XML was exported. The attempt remains FAIL.

`scripts/android_performance_readiness.py` adds pure functions for a future driver. `ReadinessText.from_catalog` selects an explicit locale. `qualify_import_report` checks the exact provider UUID, localized title, source summary, circuit summary and Close, and rejects error dialogs or another UUID. `qualify_editor` requires one visible Canvas by its localized description, one Run action through `content-desc`, no Pause and Steps 0. Visibility checks use window bounds and ancestor clipping; no explicit `visible-to-user=true` attribute is required.

`qualify_independent16_model` checks 64 components, including 48 uniquely labelled 8-bit pins and 16 east-facing AND gates, their historical locations and all 16 wire endpoints. It resolves library descriptors, normalizes coordinate spaces and compares multisets with undirected wires. Added defaults are allowed. This projection does not prove complete default, metadata or XML equivalence.

Nine offline tests read unchanged report/editor bytes and the source XML from the versioned failure ZIP. Negatives reject another UUID, an error dialog, a missing or duplicate editor, Pause, nonzero steps, wrong wire counts or endpoints, duplicated wires, AND-to-OR substitution and a north-facing AND. The model test uses historical input XML, not a Native export. These checks establish no live correlation among the provider, editor, exported document and process. The next driver must obtain and validate that actual export; the earlier JVM replay is separate functional evidence.

```sh
python3 -m unittest discover -s scripts/tests -p 'test_android_performance_readiness.py'
```

The helper performs no I/O, UI action, wait or process call. It has no Native CLI. `Scenarios.incoming` and the driver are unchanged. No Native readiness or performance channel was validated in this source-only unit. The original recipe below remains a plan and requires a separately reviewed driver integration before another attempt.

## First unit: Android input-idle, independent-16

Use the existing isolated e2e app and helpers. The app APK must be `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973`; test APK `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b`; snapshot helper `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c`. All three were checked on disk, without installation or a device call.

The first circuit is the existing accepted `independent-16.circ`: 16,109 bytes, SHA-256 `19198a15e4880baccd3d00391f257c766a8be0859daae1ca721d3d8ed7c12a31`, main `gates-16`, 64 components and 16 wires. Since #321 Mobile opens it as Evolution does and renames `gates-16`, `a-N`, `b-N` and `out-N` with `generateValidVHDLLabel` (Mobile's stable suffix: `gates_16_0da774a5`); `scripts/android_performance_readiness.py` expects those names in the export and the rename notice in the import report. Its preserved archive is delivered by receipt commit `fd52c5ef`, at `docs/performance-evidence/historical-replay-29942df3/historical-inputs.zip` after root integration. That receipt is not present in this audit base; `plan.json` declares the dependency and retained local path. Integrate the receipt or its root cherry-pick before using the repository-relative extraction path. Do not regenerate the file or add a marker inside its XML. The 1024-bank files are parser-rejection cases, not drawable workloads.

After the owner receives a lane, use the existing generic Android scenario helpers to prepare the document:

```python
exact = Path(circuit_path).read_bytes()  # verify the pinned size/SHA first
uri = scenarios.fixture(exact)
scenarios.incoming(uri)  # external VIEW; app3a handles it as IMPORT_COPY
before_xml, project = scenarios.export_xml("native48-before")
# Confirm gates_16_0da774a5 (gates-16 renamed on open, #321), 64 components/16 wires; Fit, paused controls and a unique Canvas.
# Preserve a UI tree/screenshot and the fresh-process/artifact identities.
# Run the passive collector below without UI input, snapshots, exports or gestures.
after_xml, _ = scenarios.export_xml("native48-after")
assert before_xml == after_xml
```

These helpers already preserve the external bytes and export canonical project XML. Compare canonical before/after; it need not equal the external XML byte for byte. There is no generic one-shot preparation CLI for an arbitrary file today. The owner can reuse this host-only helper sequence; no app/test APK change is needed. The normal e2e session's installation/reset/setup is outside the measurement window and must be recorded as setup. `mixedInput` pinch/scroll/cancel cases are not substitutes: their `openFixture` replaces the document.

Record the selected app process, window, orientation, resolution/density, font scale, viewport, keyboard/tool-sheet state, clock controls and all other retained windows/scenes before sampling. Run should be available and Pause absent. Here “idle” means no driver input and paused clock controls; it does not assert that workers, GC, renderer or OS housekeeping are inactive. A preparation failure remains a setup failure, not a slow-render measurement. Do not retry inputs to force a performance result.

The existing sampler can then run once, using values from that future lane:

```sh
python3 scripts/profile-android-circuit.py \
  --serial "$ANDROID_SERIAL" --package thalesmms.logisim.mobile.e2e --pid "$VERIFIED_APP_PID" \
  --expected-apk-sha256 10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973 \
  --fresh-launch-confirmed \
  --build-commit 3a0a01f1af05dd3fea75d5a1499c762d039c478a \
  --base-commit 3a0a01f1af05dd3fea75d5a1499c762d039c478a \
  --scenario idle --circuit "$HISTORICAL_INPUTS/corpus/independent-16.circ" \
  --seconds 20 --interval 1 --no-gfx-reset \
  --output-dir outputs/performance/android-3a-input-idle16-001
```

This command has not run. Twenty seconds and one-second polling are requested collection parameters, not latency/resource budgets. The collector verifies the installed app/PID before and after, excludes the pre-existing frame buffer and preserves raw memory/frame data and command envelopes. CPU requires readable process counters and `CLK_TCK`. Missing CPU/RSS/frame observations remain unavailable or partial; a zero-frame idle window does not prove zero invalidations. Polling adds overhead and does not guarantee exactly one sample per second. Preserve actual command durations rather than subtracting an invented observer cost.

Once this observation channel passes, repeat the same unit on accepted `independent-384.circ`: 384,036 bytes, SHA-256 `6b7593a012694a5aa75989516e7d6374b442e6f9fe1b50f95ade667dab1bee67`, 1,536 main components and 384 wires. A single ordered pair is descriptive per-corpus evidence, not a paired causal optimization or retained-memory experiment. Keyboard viewport changes are a separate follow-up after proving focus, no selection, a one-way anchor/scale change and unchanged project XML outside the sample window.

## iOS counterpart and the two bundle hash algorithms

The same frozen 3a app can support a passive process-resource window without rebuilding the app. The e2e wrapper normally builds its test harness, so do not silently invoke the full wrapper as a no-build sampler. The owner must establish the exact document/window and fresh launch externally. CPU/RSS/footprint are process-wide, including other retained scenes; do not attribute them to one circuit unless that context is controlled.

The harness tree hash is `813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef`. It serializes path/NUL/file-hash/newline records. The sampler's `bundle_fingerprint` instead hashes canonical JSON entries, including internal symlink targets. Its filesystem-only `--hash-app` mode produced `38373c28ccd9316e57cb45536bfe9195b65261d53a3b22cc18ba371fa06613c7` for the same 12 files. That second hash belongs in `--expected-bundle-sha256`; the algorithms must not be interchanged. The raw filesystem result is in `ios-collector-fingerprint.json`.

```sh
python3 scripts/profile-ios-simulator.py \
  --udid "$LEASE_UDID" --bundle-id thalesmms.logisim.mobile \
  --expected-executable-sha256 72f025668535a86c479eda81c2bbcba9fb65e0f1cef29598d869dd78c4977d48 \
  --expected-bundle-sha256 38373c28ccd9316e57cb45536bfe9195b65261d53a3b22cc18ba371fa06613c7 \
  --fresh-launch-confirmed --build-id 3a-native48-input-idle16 \
  --build-commit 3a0a01f1af05dd3fea75d5a1499c762d039c478a \
  --base-commit 3a0a01f1af05dd3fea75d5a1499c762d039c478a \
  --scenario input-idle-independent16 --circuit "$HISTORICAL_INPUTS/corpus/independent-16.circ" \
  --duration-seconds 20 --interval-seconds .5 \
  --output-dir outputs/performance/ios-3a-input-idle16-001
```

This command also has not run. The sampler checks the complete on-disk bundle and loaded main-executable UUID, but does not enumerate every loaded dynamic image. The logic is in `LogisimMobile.debug.dylib`, hash `84c64e315e959842ae92b0891c6895e40aa2f70496eaf6c1a228a2140ed202fb`; a confirmed fresh launch matters. Any future stack analysis needs matching symbols for that image too. No Core Animation/GPU frame or recomposition result follows from this process sampler. An external trace would first need demonstrated events for the correct process/window before its frame data could be used.

## Later units and physical stage

SAP continuous and background windows can use the existing app and historical program with declared frequency, state and transition timestamps. They can observe process use and the resulting paused state, not exact cancellation completion. Drawing/codec phase spans and request-to-worker-stop latency need additional appropriate observation; the current APK/bundle does not expose those timestamps. Do not add optimization or instrumented-build numbers to the frozen-app baseline by inference.

Only after the software observation channels are qualified should a measured bottleneck justify culling/cache changes and a same-input equivalence comparison. PSS, RSS and physical footprint are different process metrics; do not compare them as interchangeable memory totals across runtimes. Physical phone/tablet CPU/frame/memory budgets, energy and thermal behavior remain the final stage requested by the user. Emulator CPU/PSS and Simulator host CPU/footprint, scenario duration, and “no observed frames” cannot replace those results. This source plan does not close issue #48.
