# Measure core performance

The benchmark generates banks of 8-bit gates and banks of hierarchical instances. Each bank has `n` observed outputs, `4n` components in the main circuit, and `n` wires. The runner checks all outputs against `0x00` or `0x5a` before accepting each propagation sample.

## Run

Use JDK 21 and configure the Android SDK as for a normal build.

```sh
python3 scripts/benchmark-circuits.py --sizes 16,64,256 --iterations 7 --warmups 2 --output outputs/performance/current.json
```

The command builds the JVM test classes and writes JSON with medians, p95, thread CPU time, bytes allocated by the thread, and observed heap usage. The adjacent CSV contains individual samples. Use `--skip-build` only when the existing classes match the current sources.

Use `--phases serialize` to measure one phase, or provide a list such as `--phases cold_settle,input_change`. Before measuring, the runner also imports and runs an exported copy of the circuit outside the measured phase. The hierarchical case exports its appearance to preserve terminal positions.

Each phase is measured separately.

| Phase | Operation |
| --- | --- |
| `serialize` | Export the complete project to XML |
| `import` | Read the XML and verify the component count |
| `cold_settle` | Create a simulator and settle the project |
| `input_change` | Toggle inputs and settle from the previous state |
| `unchanged_settle` | Settle again with unchanged inputs |

Heap usage is observed after each operation without forcing garbage collection. Allocated bytes and CPU time are measured on the benchmark thread, excluding GC and JIT compiler threads.

## Capture CPU time and allocations

```sh
python3 scripts/benchmark-circuits.py --jfr outputs/performance/core.jfr
"$JAVA_HOME/bin/jfr" print --json --events jdk.ExecutionSample,jdk.ObjectAllocationSample outputs/performance/core.jfr > outputs/performance/core-events.json
python3 scripts/summarize-circuit-jfr.py outputs/performance/core-events.json
```

The summary selects the first core frame in each sample. `ObjectAllocationSample` weights estimate allocations by sampling. Raw files are kept in `outputs/` outside version control.

The runner uses a restricted, hashed JFR profile that excludes initial environment, system property, and JVM argument events. Java receives only the runtime, locale, and temporary-directory variables listed in the [host procedure](performance-hosts.md). The runner filters out inherited Java options and credentials. Python checks use fake tools and sentinel values to verify this boundary, separately from JVM and JFR measurements. Keep older captures that contain sensitive data local and record their hashes.

## November 4, 2026 baseline

The baseline uses the engine from commit `7ef4187`, before the extensible registry changes and without an elaboration cache. It ran on an Apple M4 with macOS ARM64 and JBR 21.0.11, with the JVM heap capped at 2 GiB. Each phase used two warmups and seven samples. This development measurement leaves mobile-device budgets open.

| Case | Bank elements | Median import | Median input propagation | Median settle without changes | Allocation per settle without changes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Independent gates | 256 | 11.87 ms | 22.44 ms | 9.45 ms | 3.84 MB |
| Hierarchical instances | 256 | 12.87 ms | 67.64 ms | 67.56 ms | 10.13 MB |

The four-second JFR attributed 90 CPU samples to `CircuitNetworks.<init>` and 25 to `CircuitSimulator.propagate`. Repeated network construction is a candidate for optimization in this workload. Repeat the measurement after integrating the new registry and any cache.

The XML figures above come from the first corpus version. The current hierarchical case includes its terminal appearance in the export. Use the same corpus version when comparing measurements. A later attempt to reduce allocations in XML escaping passed content tests but showed no consistent CPU reduction. The codec change was dropped.

## Event engine baseline

Core commit `7dc0cd5`, measured in worktree `77d16df`, uses the event engine and component registry. The command was as follows.

```sh
python3 scripts/benchmark-circuits.py --sizes 16,64,256 --iterations 7 --warmups 2 --jfr outputs/performance/event-engine-baseline.jfr --output outputs/performance/event-engine-baseline.json
```

The cases still check every output for `00` or `5a`. On the same development machine, the medians for 256 outputs were as follows.

| Case | Import | Input propagation | Allocation per input change | No change | Allocation without changes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Independent | 15.43 ms | 28.48 ms | 52.09 MB | 11.43 ms | 17.14 MB |
| Hierarchical | 15.61 ms | 36.98 ms | 59.08 MB | 18.12 ms | 28.90 MB |

This JFR workload attributed 22 CPU samples to `Instance.applyOutput`, nine to network construction, and nine to `CircuitSimulator.run`. `AttributeSchema.getDefaults` had the highest sampled allocation weight. These weights identify candidates for investigation. Exact byte totals and frame costs require other measurements.

A separate experiment precomputed private attribute defaults and preserved public copies. The ABBA comparison observed 14–27% fewer allocated bytes across the four groups of 256 outputs. Times varied considerably, and the host finished with a load average of 414.9 and 9,280.94 MiB of swap in use. CPU and latency effects remain undetermined. The change remains outside the main delivery pending measurement in a suitable environment. Local raw data is under `outputs/performance/schema-paired-linked/`.

## Query visible items

`SpatialIndex<T>` stores an immutable tree of rectangles. Queries include boundary intersections and return items in original order, preserving drawing order. Horizontal wires, vertical wires, and points may have zero-width or zero-height rectangles. A long line occupies one indexed rectangle independently of grid cells.

The caller must supply complete visual bounds, including labels and stroke width, and rebuild the index when the document changes. A drag preview outside the document must be drawn separately. The index operates independently of simulation state.

```sh
python3 scripts/benchmark-circuits.py --workload spatial-index --iterations 9 --warmups 3 --output outputs/performance/spatial-index.json
```

Each sample executes 64 queries in a static scene. Before measurement, each result is compared with a linear search. On the baseline machine, the latest run measured the following.

| Items in the scene | Linear scan, median per 64 queries | Index, median per 64 queries |
| ---: | ---: | ---: |
| 1,000 | 4.61 ms | 0.09 ms |
| 10,000 | 6.82 ms | 0.70 ms |
| 50,000 | 23.41 ms | 0.92 ms |

These figures cover spatial queries. Frame rendering and index construction are separate costs, and the JSON records index construction. The improvement depends on the share of visible items. A query covering the entire scene still returns every item.

## Measurements still pending

Drawing, pan and zoom, cancellation latency, background usage, and energy are outside the JVM runner's measurements. Closing issue #48 requires Android and iOS host captures and physical-device budgets in addition to JVM results.

The collectors verify app identity before measuring. The [host procedure](performance-hosts.md) describes portable corpus generation, capture windows, Android and iOS commands, and metrics that remain unavailable. The corpus also preserves files rejected by the current parser limit.

## First Android window after loading SAP-1

The Android owner provided a paused window on the API 35 emulator using APK `39103ec1…de9f26` and declared pin `aaa1f62`. The collector verified the APK and PID 8614 before and after the capture without gestures, installation, termination, or reset. The five-second window produced five samples and excluded 107 initial buffer frames using `--no-gfx-reset`.

Estimated CPU usage was 3.87% of one core, with bounds of 3.44–4.29% accounting for ADB read time and counter quantization. PSS fell from 126,696 to 112,187 KiB and RSS fell from 256,364 to 242,136 KiB. No new frames were observed in this window. Compose invalidations, input latency, continuous execution, and physical energy remain separate measurements. The final overall baseline is still pending.

The [checked-in receipt](performance-evidence/android-aaa1f62-sap1-idle.json) records hashes, metrics, and limits. Local raw data remains under `outputs/performance/android-aaa1f62-sap1-idle-01/`. The original manifest was retained and CPU bounds analysis was saved separately.
