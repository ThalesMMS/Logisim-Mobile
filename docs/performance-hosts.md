# Capture mobile host metrics

These scripts collect metrics from an app the host operator has already installed and opened. App installation, launch, termination, and gestures are operator actions. Before each capture, confirm the consolidated build is installed, open the corpus file, and record the screen, component count, and scenario. The operator declares the scenario name and selected file. The collector has no access to the editor's internal state.

Use a host without concurrent builds, UI tests, or captures. Manifests record machine load to contextualize contention. Simulator figures support comparison in the same environment. Physical-device budgets and energy require measurements on physical devices.

The [source-only plan for artifact set 3a](validation/native-performance-source-plan-3a/README.md) pins the APKs, both iOS bundle hash algorithms, the first historical input, and a passive 20-second window. Native capture remains **NOT_RUN** under this plan. A new build or lane reservation requires a separate action. The matrix distinguishes available metrics from isolated drawing, codec, and cancellation metrics and the final physical-device stage.

## Generate the files

```sh
python3 scripts/benchmark-circuits.py --workload corpus --output outputs/performance/mobile-corpus-v2
```

The v2 corpus exports independent and hierarchical banks with 16, 64, 256, 384, and 1,024 outputs. Each bank has `4n` components and `n` wires in the main circuit. Files include pin labels to identify inputs and outputs in the UI. The manifest records size, SHA-256, counts, and whether import was accepted or rejected. For accepted cases, the exporter verifies all outputs across low, high, and low transitions. For rejected cases, the exporter preserves the XML and error IDs and keeps the parser limits in place.

The current parser limits XML to 2 million characters and 10,000 nodes. The independent bank with 256 outputs contains 6,405 nodes, and the 1,024-output bank exceeds the node limit. Use only `import_status=accepted` for drawing, pan, and zoom. Larger cases also exercise explicit rejection of oversized files.

The labels change the XML bytes from the original JVM corpus. Compare import and drawing with the same corpus version. Use the actual corpus file so the scene remains reproducible.

The [functional replay at `29942df3`](performance-evidence/historical-replay-29942df3/README.md) revalidated all ten existing historical files and SAP-1 with matching sources, classes, and inputs. Eight banks were accepted, both 1,024-output rejections were preserved, and SAP-1 reached 160 ticks with output `08`. The receipt includes a ZIP with the original bytes and a verifier that runs without Java. This replay verifies the existing files. Corpus generation, performance measurement, and host trials are separate.

`--skip-build` now requires hashes of sources, configuration, and compiled classes recorded by a previous runner execution. The runner rejects stale classes when sources or artifacts change. `--no-incremental` enables rebuilding if the Kotlin incremental cache fails after a worktree update.

The runner checks sources and revision before and after building and running. The JVM uses temporary physical copies of the `main` and `test` directories, including resources, and the Kotlin JAR, all checked against original hashes. The manifest records the Java launcher, its version and hash, the executed command, classpath files, and CSV, XML, and JFR payload hashes. The runner deletes temporary copies and retains hashes and the command in the receipt. Results and JFR remain staged until verification finishes, and detected differences prevent replacement of previous results. The new stamp includes classpath resources, so old stamps require a run without `--skip-build`.

The stamp also includes the runner and `scripts/performance/benchmark-profile.jfc`. Changing either invalidates previous preparation even if Kotlin is unchanged. The runner copies and checks the JFR profile before and after the run and records its SHA-256 in the manifest. It enables CPU and allocation samples and GC events, and disables `jdk.InitialEnvironmentVariable`, `jdk.InitialSystemProperty`, and `jdk.JVMInformation`.

The [privacy smoke test](performance-evidence/jfr-privacy-smoke-001/README.md) from producer `8c79c7eb` ran one actual JVM with fake sentinel values and the restricted profile. After stopping and closing the recording, the official `RecordingFile` API counted one data-free marker and zero records from the three sensitive event types. Fake option variables and properties were absent. Sources and files retained their hashes. This short run validated configuration and environment. Circuit execution, Native execution, and a new baseline were outside its scope. The JFR remains local, with references and hashes in the public receipt.

Java version and benchmark calls inherit only `PATH`, `JAVA_HOME`, `LANG`, `LC_ALL`, `LC_CTYPE`, `TZ`, `TMPDIR`, `TMP`, `TEMP`, `SYSTEMROOT`, and `WINDIR` when present. The manifest records permitted and used variable names without values. The Java environment excludes credentials, unknown variables, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and `JDK_JAVA_OPTIONS`. Preparation with Gradle still uses the normal build environment. Older captures made with `settings=profile` may contain environment values. Keep sensitive originals private and publish references and hashes only. The restricted profile changes the event set, so record which profile each historical run used.

## JVM core

The circuit benchmark uses `schema=2`. Each operation returns its result. The runner stops elapsed-time, thread CPU, thread allocation, and heap counters before checking the result. It also checks warmups. An incorrect sample stops the run and stays out of published results.

Serialization requires exact fixture XML. Import requires matching IDs, types, effective attributes, connections, port geometry, subcircuit definitions, and metadata against the imported reference, plus canonical reserialization. Every simulation phase, including `unchanged_settle`, requires every eight-bit output to be `00` or `5a` as appropriate for the input, with no oscillation or structured error diagnostics. The tests deliberately change these results to show that equal counts, equal XML lengths, and absence of oscillation alone are insufficient.

`cold_settle` creates a simulator in a warmed JVM and excludes process launch. `input_change` toggles input on every call, including between warmup and the first sample. `unchanged_settle` preserves the last applied input. Heap is an observation after each operation. Retention and peak RSS require other measurements. GC totals and JFR cover preparation, warmups, and checks. The previous schema included checks inside the timed regions, so its times use a different measurement boundary.

Prepare the build outside the measurement window. After pinning the revision and coordinating a window without other workloads, use the same verified classes with `--skip-build`.

```sh
python3 scripts/benchmark-circuits.py --prepare-only

python3 scripts/benchmark-circuits.py --workload circuits \
  --sizes 16,64,256,384 --iterations 7 --warmups 2 \
  --skip-build --output outputs/performance/jvm-quiet-01.json \
  --jfr outputs/performance/jvm-quiet-01.jfr
```

`--prepare-only` compiles and checks sources and classes and writes the stamp without running the benchmark or creating metrics JSON, CSV, or JFR. It also accepts `--skip-build` to verify an existing stamp. The revision, sources, and classes must match until capture. The measurement JVM will make physical copies and check hashes again. Circuit capture measures only the JVM core. Drawing, touch latency, Native execution, and device energy require separate trials.

## Android

The operator supplies the SHA-256 of the consolidated APK before installation. The collector checks it against the installed APK before and after the window. A mismatch or a change in PID or process start time stops the capture.

```sh
python3 scripts/profile-android-circuit.py \
  --serial emulator-5556 --package thalesmms.logisim.mobile.e2e \
  --expected-apk-sha256 APK_SHA256 --fresh-launch-confirmed \
  --build-commit BUILD_COMMIT --base-commit 7ef4187 \
  --scenario pan-zoom \
  --circuit outputs/performance/mobile-corpus-v2/independent-256.circ \
  --seconds 20 --interval 1 \
  --output-dir outputs/performance/android-pan-zoom-01
```

The script resets `gfxinfo` statistics before the window. It retains raw `meminfo` and `gfxinfo` reports, readable process counters, and the duration of each command. PSS and RSS are separate. Dumps use the exact PID to avoid combining user profiles. If multiple processes share the package, specify `--pid`. CPU uses process tick differences and system `CLK_TCK`. The timestamp accompanies the counter read. The manifest includes the estimate and bounds that account for ADB query duration. If `/proc` is restricted, CPU is unavailable.

Use `--no-gfx-reset` for read-only windows. In this mode the collector retains the first buffer in `gfxinfo-baseline.txt` and excludes its frames from window aggregates without sending a reset. CPU bounds also include separate quantization of `utime` and `stime`. A zero-tick difference leaves some CPU usage possible within those bounds.

Observed frames are deduplicated across queried buffers by renderer and timestamps. Records without renderer identity are excluded from aggregates. The raw span uses `FrameCompleted - IntendedVsync` only for valid rows with `Flags=0`. It includes queue time and differs from Android's adjusted `totalDuration`. When both fields exist, a separate deadline count checks `GpuCompleted >= FrameDeadline` without assuming 60 Hz, as in [AOSP JankTracker](https://android.googlesource.com/platform/frameworks/base/+/65ffa6db9273/libs/hwui/JankTracker.cpp#209). The buffer can lose frames between queries, so its percentiles describe observed frames only. Full traces and touch latency require separate capture. The [dumpsys documentation](https://developer.android.com/tools/dumpsys) notes that the format depends on Android version.

The manifest marks unavailable metrics and partial captures. Zero observed frames in an idle window leaves invalidations undetermined. Budget decisions require readable CPU and memory data. A restart during the final read also invalidates the capture.

## iOS Simulator

Hash the build's `.app` before installing it.

```sh
python3 scripts/profile-ios-simulator.py --hash-app /absolute/path/Logisim.app
```

Save `bundle_sha256` and `executable_sha256`. After installation and a fresh launch confirmed by the operator, use the explicit simulator UDID.

```sh
python3 scripts/profile-ios-simulator.py \
  --udid SIMULATOR_UUID --bundle-id thalesmms.logisim.mobile \
  --expected-executable-sha256 EXECUTABLE_SHA256 \
  --expected-bundle-sha256 BUNDLE_SHA256 --fresh-launch-confirmed \
  --build-id CONSOLIDATED_BUILD --build-commit BUILD_COMMIT --base-commit 7ef4187 \
  --scenario pan-zoom \
  --circuit outputs/performance/mobile-corpus-v2/independent-256.circ \
  --duration-seconds 20 --interval-seconds .5 \
  --output-dir outputs/performance/ios-pan-zoom-01
```

The collector verifies the device directory, bundle, executable path and hashes, and loaded executable UUID. It records hashes and UUIDs for on-disk frameworks. The operator confirms a fresh launch because the collector does not enumerate separately loaded dynamic images.

CPU includes all process threads, with 100% corresponding to one logical core. Mach ticks are converted using the machine timebase, following the `proc_pid_rusage` path in [XNU](https://github.com/apple-oss-distributions/xnu/blob/f6217f891ac0bb64f3d375211650a4c1ff8ca1ea/osfmk/kern/bsd_kern.c#L1187-L1211). CSV and the manifest record RSS and physical footprint separately in bytes. Core Animation frames, GPU, and Compose invalidations require separate instrumentation.

A separate Instruments window can investigate frames without modifying the app. After verifying identity, use the confirmed PID and the same UDID.

```sh
xcrun xctrace record --template 'Animation Hitches' \
  --device SIMULATOR_UUID --attach VERIFIED_PID --time-limit 20s \
  --output outputs/performance/ios-pan-zoom-01.trace
xcrun xctrace export --input outputs/performance/ios-pan-zoom-01.trace \
  --toc --output outputs/performance/ios-pan-zoom-01-toc.xml
```

The template is available in local Xcode. Its execution against the consolidated app still requires verification. Preserve the `.trace` and table of contents. Interpret frame metrics only when the trace contains those events for the correct process and window. A trace without events leaves hitches undetermined. Apple describes the template in [Improving app responsiveness](https://developer.apple.com/documentation/xcode/improving-app-responsiveness). For CPU stacks, use `Time Profiler` and check dSYM UUIDs from the same build before attributing functions. ETTrace requires temporary linking in the app and falls outside this unchanged-build capture.

## Comparable windows

Repeat each window three times with the same size and order. Use a new directory per capture and keep manifests, raw data, and visual evidence of the open document.

| Scenario | Operator preparation and action | Evidence produced |
| --- | --- | --- |
| `idle` | Document open, simulation paused, no interaction for 20 s | Idle CPU and memory, plus observed Android frames |
| `pan-zoom` | Same initial viewport, repeat documented pan and zoom for 20 s | CPU, memory, and observed Android frames, excluding touch duration |
| `continuous` | Same circuit and clock frequency, start execution before the window | CPU and memory during continuous execution |
| `background` | Start execution, send the app to the background, and capture for another 20 s | CPU and memory after the transition. Confirm on return that simulation stays paused |
| `import` | Window around opening the same file | Memory and observed frames. Picker and importer latency require separate measurement |

Generated banks have static inputs. Running them continuously measures repeated settling without new stimuli. Also use `examples/sap1.circ` for a workload with clock and state changes, recording the file, frequency, and window separately. Use the changing circuit to measure consumption under transitions.

To investigate memory growth, also record the exact sequence of project opens, closes, and replacements. RSS or footprint growth requires additional evidence to establish a leak. JVM heap, Android PSS, and iOS physical footprint measure different quantities.

## Observation on a simulated iPad mini

Build `a9f4983d` opened the `gates-256` corpus through the native picker with 1,024 components and 256 wires. The file contained 255,620 bytes and had SHA-256 `6093665f68ac49d62caa26c8432fa70e3b87c050720b6f9af40977d04cb98bcb`.

After zooming from 21% to 33%, four half-second drags were sent to the canvas. The full window, including gesture submission through the tool, lasted 17.836 seconds. The process consumed 13.60 seconds of CPU in the window. Before and after RSS were 477,632 and 525,856 KiB. The app stayed in the same process and showed the circuit at the end. This observation covers simulator process RSS and CPU. The previous project was reopened and the temporary copy removed.

## Reuse layout during drawing

The optimized build profile showed repeated layout calculations in `drawClassicComponent`. The renderer now resolves layout once and reuses its bounds and terminals, preserving drawing order. All 20 existing tests for drawing, text, IEC symbols, and I/O passed.

On the same simulated iPad, with `gates-256` at 33% zoom, the before and after canvas region had zero differences across 2,172,480 pixels. A four-second drag followed by two seconds for settling produced the observations below. The total duration includes simulator communication.

| Optimization stage | Observed total time | Process CPU |
| --- | --- | --- |
| Before layout reuse | 9.265 s | 5.70 s |
| After layout reuse | 9.038 s | 4.96 s |

These are single simulator samples. The verified change is reduced layout reconstruction. Physical-device budgets remain pending.

## Open criteria for issue 48

The JVM runner measures import, propagation, and serialization with verified outputs. The canvas uses spatial indexes to restrict wire and junction drawing to the visible area. The two Android samples in `validation.md` used different gesture cadences, so the gain from this optimization remains unmeasured.

On the simulated iPhone 17, opening Settings for 20 seconds interrupted a run. On return, the control showed Run and the counter stayed at 381 on subsequent reads. This verifies lifecycle pausing. Background CPU and energy still need measurement.

Comparable drawing, pan and zoom, invalidation or recomposition counts, engine cancellation latency, physical-device budgets, and continuous and background consumption on those devices remain pending. Passive captures supply only part of the evidence required for these criteria.
