# Android 04a cold-restore diagnostic

One controlled process restart reproduced the readiness failure on `emulator-5558`. The installed APK matched SHA-256 `e93a285e3294f91f971a0cc9c80b1f2cd51dc29fa0bdf7989a8ca3f2c38bb5c3` before and after capture. The operator attested source commit `04a0b34a38689932a3f3aaed7f4ea5cb6b63c8db`. The package was `thalesmms.logisim.mobile.e2e`; PID 8880 was replaced by PID 9898.

The existing harness kept its 30-second `wait_editor` budget, starting after `am start -W` returned. The timeout was preserved. The earlier observation taken 145 seconds after another failure does not measure launch-to-ready duration.

| Observed milestone | Seconds from launch request |
| --- | ---: |
| `am start -W` returned | 10.923 |
| Fresh hierarchy, loading | 18.323 |
| SIGQUIT requested after observing loading | 18.648 |
| Fresh hierarchy, loading | 25.728 |
| Fresh hierarchy, loading | 33.637 |
| Readiness/hierarchy timeout | 41.530 |

The timeout occurred 30.607 seconds after `am start` returned. The screenshot commands targeted 12 and 30 seconds and completed at 13.764 and 32.553 seconds; those labels are scheduling targets. No `editor-ready` event was observed during the budget. [events.json](events.json) preserves the command envelopes and hierarchy observations. The later final UI capture is a post-run observation and does not establish the instant of readiness.

## Stack and phase

The SIGQUIT dump was taken at about 18.9 seconds from launch. The Main thread was drawing `CircularProgressIndicator`. Default worker TID 10060 was runnable in this call path:

```text
String.charAt/indexOf → Kotlin contains → CircXmlParser.name:126
→ element:52/82 → parse:35 → CircCodec.readProject:46
→ CatalogWorkspaceRuntime validator:103
→ WorkspaceCatalogRepository.validXml:324 → readDocument:244
→ WorkspaceSession.open:39 → CatalogWorkspaceBinding.start:101
```

This sample locates active parsing in the first catalog START validation. The fresh hierarchies contain only `Loading project…`, without the catalog header. The previous failure's later capture had a header and therefore represented a different phase. No internal parse-start/parse-end timestamps were recorded, so this sample cannot assign a duration to parsing, initialization, or simulation. The parser's four-character name delimiter check has constant cost per character; the sample does not demonstrate quadratic scanning.

[sigquit-9898-trace.txt](sigquit-9898-trace.txt) preserves the full stack. At the dump, Main had used 2.768 CPU-seconds and accumulated 9.529 seconds of runnable scheduler wait. The parser worker had used 0.481 CPU-seconds with 5.636 seconds of wait; JIT had used 1.052 CPU-seconds with 11.709 seconds of wait. These are accumulated thread counters, not method timings.

## Counters and environment

[summary.json](summary.json) is generated from the raw samples by `scripts/summarize-android-readiness.py`. The 17 observations of PID 9898 share start tick 266496. The two observations of PID 8880 and the empty process-transition sample are kept separate.

- Process CPU advanced by 3,068 ticks, or 30.68 CPU-seconds at the confirmed target rate of 100 Hz. The endpoint read envelopes span 62.417–68.525 seconds, with a midpoint estimate of 65.471 seconds. The estimate is 46.860% of one core, bounded by 44.743–49.185% after timing envelopes and tick quantization. It is not a point estimate at the timeout or a whole-device percentage.
- Main, Default worker, RenderThread, and JIT advanced by 1,080, 721, 654, and 494 ticks in different observation windows. Sequential reads do not give an atomic division of process CPU.
- `read_bytes` advanced by 3,784,704 bytes and `write_bytes` by 90,112 bytes. After the probe whose envelope was launch+24.262–32.766 seconds, another 2,231 CPU ticks elapsed without an increase in `read_bytes`. No file-lock wait appears in the captured worker stack. These observations do not assign all subsequent time to computation.
- Probe durations ranged from 0.570 to 8.504 seconds, with a median of 3.797 seconds. Requests occupied 98.76% of the capture's wall-clock span. That is time in commands, including waits, not measured observer CPU. The requested one-second interval did not produce 1 Hz sampling.

The capture-start host load averages were **181.85 / 120.85 / 62.73**. Post-run metadata confirms a 10-core, 32-GiB Mac and a two-vCPU guest. Post-run swap usage was reported as `14585.62M`; these later measurements are labeled separately in the receipt. The root integration was running Android/Native build and test work during this capture.

Host process mapping identifies PID 28678 as the separate `ISIS-VALIDATE` AVD on port 5556, and PID 45345 as the temporary two-vCPU AVD on port 5558. Only 5558 was used here. A later guest `top` showed PID 9898 idle. Neither the earlier high host load nor the other emulator's CPU usage is attributed to a particular application task by this evidence.

The independent [original quantitative review](quantitative-review-original.md) was written before target clock-rate readback. Its HZ uncertainty was subsequently resolved by `HZ=100` in the captured dump and [guest-hz.txt](guest-hz.txt). The generated summary uses that confirmed value.

## Provenance and reproduction

`passive/receipt.json`, `provenance.json`, and `evidence-index.json` preserve artifact, observer, harness, and raw-file hashes. `observer-source-at-capture.py` is the exact observer used. The future observer in `scripts/profile-android-readiness.py` batches reads into two `cat` processes per app PID and records guest HZ/vCPUs. That revised code passed fake-ADB tests and has not been used for a new device measurement.

The original harness sources were copied from owner artifacts whose hashes match the values recorded before launch. The current owner files had changed after capture; [harness-source-provenance.json](harness-source-provenance.json) records the matching archived sources. The UID log contained historical PIDs, so `app-logcat-pid9898.txt` preserves only lines for the new process. Full raw-log and host-inventory hashes remain in the index.

To recalculate the summary from the committed evidence, choose a new output path outside this directory:

```sh
python3 scripts/summarize-android-readiness.py \
  --input docs/performance-evidence/android-04a-coldrestore \
  --pid 9898 --hz 100 --output /tmp/android-04a-readiness-summary.json
```

The analyzer reads files only. The passive observer verifies the APK and initial PID; it does not launch, stop, signal, reset diagnostic buffers, or interact with the application. `capture-coldrestore-once.py` records the separate, case-specific control sequence authorized for this one diagnostic.

All ten pre-existing workspace files kept their hashes, including the catalog and five project revisions. One window link was added and `profileInstalled` changed during the normal restart, so the entire private directory is not asserted unchanged. SIGQUIT perturbed this diagnostic window. After sampling ended, temporary adbd root access read the protected trace; unroot completed, shell identity and the same PID were confirmed. Final UI/process state was preserved, then only the .e2e app on 5558 was force-stopped as authorized. No new launch followed, and 5556 was not changed.

This case supports further isolated readiness investigation. Host scheduling contention, memory pressure, and observer cost prevent treating it as a quiet performance baseline or a device budget. No production parser/cache change or timeout increase is included. UI parity, physical-device performance, energy, and a quiet reproduction remain unverified.
