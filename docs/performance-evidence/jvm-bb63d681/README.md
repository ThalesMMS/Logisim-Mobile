# JVM core captures at bb63d681

Two distinct synthetic JVM captures completed with exit 0. The first completed in 9.992 seconds, but root subsequently reported possible overlap with eight javac tests in another child. Its functional and identity checks passed; its quiet-window qualification remains pending exact compiler timestamps. All original bytes are preserved. This receipt does not claim that the first capture had no concurrent compilation.

Root then explicitly authorized one repetition after all Native/build owners and children confirmed HOLD. Two process censuses, separated by one second, are archived. Their command names remained truncated at16 characters despite the `-ww` option; they cannot establish the absence of Java or compiler processes. The second capture completed in 9.390 seconds. Its duration includes the wrapper and JFR-enabled JVM invocation. No third benchmark, app installation, Native scenario or JFR event exporter ran here. `qualification.json` records the two runs separately.

The producer is the clean, frozen revision `bb63d681afac30197e1db0e3869672b94662acc9`. Preparation compiled 1,255 classes from 302 source/configuration files and recorded 1,257 classpath files. Both runs retained the same stamp, source hashes, class/resource hashes and runner hash before and after measurement. The runner used physical copies of the main/test classpath and Kotlin stdlib, verified the Java launcher, and published results only after its identity guards passed.

```sh
ANDROID_HOME=/Users/thales/Library/Android/sdk \
python3 /tmp/logisim-gradle-serial.py python3 scripts/benchmark-circuits.py \
  --workload circuits --sizes 16,64,256,384 --iterations 7 --warmups 2 \
  --skip-build --output outputs/performance/bb63d681/jvm-quiet-02.json \
  --jfr outputs/performance/bb63d681/jvm-quiet-02.jfr
```

Each capture contains 40 result groups and 280 measured samples: two circuit families, four sizes and five phases. Each group also executed two checked warmups. The sizes denote observed eight-bit outputs; the main circuit contains four times as many components and one wire per output. Verification runs outside each timed operation. Serialization requires the fixture XML, import checks the reference model and canonical XML, and simulation checks all expected outputs, hierarchy identity and absence of structured error diagnostics. A failed check would stop the invocation rather than publish valid results.

## Observed values from the second capture

These are descriptive values from one JFR-enabled run, not a mobile performance budget or a demonstrated optimization. At 384 outputs, each main circuit has 1,536 components and 384 wires.

| Family | Phase | Median wall time | Median thread allocation |
| --- | --- | ---: | ---: |
| Independent | Serialize | 2.080 ms | 8,993,696 bytes |
| Independent | Import | 10.272 ms | 29,000,272 bytes |
| Independent | Cold settle | 47.272 ms | 150,494,768 bytes |
| Independent | Input change | 30.807 ms | 92,356,680 bytes |
| Independent | Unchanged settle | 10.004 ms | 24,828,720 bytes |
| Hierarchical | Serialize | 1.734 ms | 7,403,848 bytes |
| Hierarchical | Import | 13.757 ms | 38,241,344 bytes |
| Hierarchical | Cold settle | 46.219 ms | 128,307,824 bytes |
| Hierarchical | Input change | 34.272 ms | 95,224,016 bytes |
| Hierarchical | Unchanged settle | 15.783 ms | 42,366,280 bytes |

The runtime was JBR 21.0.11 on macOS arm64 with 10 logical CPUs. Host load averages were 5.52/6.95/12.38 before and 5.69/6.94/12.31 after the second run. The raw environment and PID/command-name/CPU/RSS censuses are preserved; process arguments were not collected. Both runs have truncated command names. The second run requested wide output, but independent review confirmed that the macOS `comm` values still have a16-character limit. The coordinated window does not establish that the host was idle or eliminate unobserved background activity.

CPU and allocated bytes cover the measured thread. Heap is observed after an operation without forcing collection, so it does not measure retained memory or RSS. The second run recorded 86 GC collections and 147 ms of GC time across preparation, warmups, verification and the measured loop. JFR also covers those activities and can add overhead. `cold_settle` means a new simulator in a warm JVM. With seven samples, the reported p95 equals the maximum. The schema-2 values are not directly comparable with older measurements whose checks were inside timed regions. The JFR identities are recorded, but the binaries remain private at the producer paths. The default profile captured a sensitive environment variable, so neither binary is committed. Their original bytes remain intact; `jfr-local-only.json` records their sizes, hashes and local paths. Event stacks have not been exported or analyzed in this receipt.

## Integrity and limits

The original JSON, CSV, logs, preparation stamp and capture/environment receipts are copied byte for byte. JFR binaries are deliberately absent from this archive and represented only by private-original references and hashes. Absolute producer paths inside them are historical provenance. The offline verifier resolves archived files by basename and checks their original hashes, source/class identities, all 280 unique samples per run, every median/max summary and the expected workload inventory. By default it verifies the JFR references against the capture/result hashes without reading or claiming access to the private binary content. `--check-local-jfr` additionally verifies both original binaries on the producer machine without printing their contents. Run it without Java or a build:

```sh
python3 docs/performance-evidence/jvm-bb63d681/verify.py
```

Neither run replays the ten historical portable XMLs or `examples/sap1.circ`; `--workload corpus` generates new inputs instead of accepting existing files. These captures do not measure drawing, pan/zoom, touch latency, cancellation latency, retained memory, continuous/background operation, Native execution, energy or physical-device budgets. They neither evaluate a paired optimization nor close issue #48. The portable files' acceptance/rejection and SAP-1 temporal behavior need their own current-artifact replay, and mobile/software captures remain separate work.
