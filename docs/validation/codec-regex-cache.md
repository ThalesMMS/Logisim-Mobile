# Codec regex cache validation

The Android SAP-1 ANR trace stopped in `PatternNative.compile` while `CircCodec.parsePoint` ran during synchronous workspace validation. This change reuses two immutable `Regex` values in `CircCodec`. Both pattern strings, match operations, diagnostics, and coordinate limits are unchanged. Each match still owns its matcher.

The bytecode gate found one regex constructor in each original hot method (`parsePoint` and `appendAttribute`) and zero after caching. The two cached patterns initialize once with the codec object.

## Reproduce without Gradle

Use an already compiled compatible core JAR and the Kotlin compiler already in the Gradle cache:

```sh
./scripts/benchmark-codec-regex.py \
  --reference-jar /path/to/core-jvm.jar \
  --baseline-revision 48ee36c \
  --output /tmp/codec-regex.json
```

The runner copies the reference JAR, compiles only `CircCodec` and its benchmark harness into temporary directories, and executes baseline/cached/cached/baseline JVM processes. It does not invoke Gradle or modify build outputs. Each phase uses 31 warmups and 31 measured operations. It checks 23 valid/invalid coordinate and XML attribute-name cases and requires byte-identical exported XML across all runs.

## Measured result

JVM 21.0.11 on macOS arm64, baseline `48ee36cbd4450353637468b35802b0a33eaa3ae2`. The reference JAR SHA-256 is recorded in the JSON report. The table combines the two process medians for each variant.

| Project / phase | CPU before → after (ms) | Allocated bytes before → after |
| --- | ---: | ---: |
| SAP-1 import | 2.678 → 2.535 | 4,822,472 → 3,864,816 |
| SAP-1 export | 0.895 → 0.896 | 4,817,448 → 1,909,232 |
| 2,048 components import | 6.314 → 5.157 | 21,116,108 → 13,669,728 |
| 2,048 components export | 3.246 → 1.728 | 24,502,352 → 9,831,512 |

The SAP-1 artifact retained 7 circuits, 156 components and 318 wires; its exported SHA-256 was `da28b60f0e5ca65daafd3d35e20ac1e77a443b341b176397476cc82147c3febf`. The larger fixture retained 2,048 components and 2,048 wires; its exported SHA-256 was `d22bba339e4747c3ab4705d119214c0ff2921571e2bbb97d9e4605bc4539959a`.

These are JVM allocation and CPU measurements. Android uses a different regex runtime; the integrated app must repeat the SAP-1 loading flow to verify the ANR outcome. Moving workspace validation and I/O off the main thread is handled separately.
