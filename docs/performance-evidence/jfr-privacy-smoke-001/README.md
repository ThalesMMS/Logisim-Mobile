# Restricted JFR privacy smoke

Exactly one JBR 21 source-launcher JVM completed with exit 0 on the clean producer `8c79c7eb6ee2921aac383031426f18d3a302503c`. The invocation used the approved `env -i` parent with fake sentinels and the frozen restricted profile. There was no Gradle, separate javac, Native scenario, circuit workload or repeat. The source launcher compiled the helper internally. The receipt records 515,697,666 ns for the invocation and bookkeeping; this is not a startup benchmark or a performance budget.

The helper selected the named running startup recording, enabled one custom marker without fields or stack, and committed it. It then stopped, dumped and closed the recording before counting actual records with the official `jdk.jfr.consumer.RecordingFile` API in that same JVM. It read event names/types, without exporting event fields or environment values.

| Check | Observed result |
| --- | --- |
| Data-free marker | Exactly 1 |
| `jdk.InitialEnvironmentVariable` | 0 records, setting disabled |
| `jdk.InitialSystemProperty` | 0 records, setting disabled |
| `jdk.JVMInformation` | 0 records, setting disabled |
| Six fake credential/option environment variables | All absent in Java |
| Injected option properties | Absent |
| Fake sentinel bytes in JFR/log/receipts | Absent |
| Source, launcher and copied input identity | Equal before/after |

Other observed records were 3 Java execution samples, 3 native-method samples, 88 allocation samples, 2 garbage collections and 2 GC pauses. The archive verifier requires these exact captured counts. Those counts confirm the recording had content beyond the marker. They do not measure circuit performance or frame/energy behavior, and the original smoke acceptance did not require CPU/allocation sampling to occur.

The Python driver used a declarative parent fixture and parsed only the literal allowed key names from the frozen benchmark runner. Java received seven runtime/locale/temp keys. The driver did not read inherited environment values or any real credential. Only fake sentinels were compared during the post-run review. The three sensitive settings were also checked through the running Recording API before the marker was emitted, and their actual event counts were checked after the recording closed.

The raw public JSON, log, source and profile copies are byte-identical to their producer files. The JFR remains local pending root review, at the path in `jfr-local-only.json`, with 209,700 bytes and SHA-256 `ecfb11bfc308f76ff110b5c350cd7b1ac3d5c36154a6a4416138490159855ffd`. No JFR binary is automatically committed. The original bb63 timing runs and their private recordings remain separate and unchanged.

Verify this archive without another JVM:

```sh
python3 docs/performance-evidence/jfr-privacy-smoke-001/verify.py
```

`--check-local-jfr` additionally checks the private original and launcher hashes on the producer machine. It does not decode another recording or launch Java. The committed helper/driver snapshots show the official decoding path used by the single approved invocation.

This is a real privacy/configuration smoke for the restricted profile and explicit environment envelope. It does not replay the historical XML corpus or SAP-1, rerun the benchmark CLI, reattribute the bb63 timing numbers, prove a perfectly idle host, qualify Native execution or close issue #48. No real credential was read or used for this smoke.
