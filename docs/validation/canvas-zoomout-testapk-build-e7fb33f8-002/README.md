# Test-only build 002

The test APK build for source `e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630`
completed in 4.960695 seconds. Its qualification is
`BUILD_ONLY_FROZEN_PRODUCTION_INPUTS`; Native execution remains `NOT_RUN`.
The prior build failure remains preserved in commit
`1412dc22a5afee6a92ca68aad72ef8bd5878ecf4`. This success does not change that
record or qualify the previously failed pinch.

The dry run and execution admitted 37 AndroidTest tasks plus one disabled
producer completion marker. The other 62 producer tasks were excluded.
`:androidApp:bundleE2eClassesToCompileJar` remained disabled with zero actions.
Its recorded Gradle state was executed and skipped, with `didWork=false` and
no failure. Its sole frozen JAR retained SHA-256
`2b34b6c3fc3eddf347456d9c77cbd30114a7af0771f475416a84d9edd4c64656`
at graph admission, before the task, and after the task. The recorded skipped
state allowed the dependent test compilation to proceed without rebuilding
the production JAR.

The record contains 312 production source identities, 11,985 frozen original
build-file identities, and 11,448 copied production-input identities unchanged
before and after execution. The app APK and snapshot helper were reused.
Neither was rebuilt. Kotlin test output now contains 25 files and Java test
output 37, including the two collector classes. These counts refer to files,
including Kotlin module metadata, rather than a count of runtime test cases.
The compiler postcheck records no input difference for either compiler task.
All three resolved compile/runtime configurations retain
`androidx.compose.ui:ui-android:1.13.0-beta02`.

The new test APK is 318,555 bytes with SHA-256
`33b888b38368b6ea56bc512bcef7e5c1d20d62fb8ab461e7f8da457800ba962a`.
The captured package is `thalesmms.logisim.mobile.e2e.test`, with runner
`dev.logisim.mobile.documents.DocumentIntegrationInstrumentation` targeting
`thalesmms.logisim.mobile.e2e`. The APK and reused app share the captured debug
signer. The captured verification output reports APK signature scheme v2.
This build does not establish that the collector ran on a device.

`index.json` preserves the size and SHA-256 of each of 21 raw inputs and its
archived bytes. JSON files larger than 64 KiB use gzip with mtime zero and no
embedded filename. APKs, JARs, and class binaries are not copied into this
packet. The four local artifact references are optional verification inputs.
Origin paths are provenance and are not read by default verification.
The scripts and Gradle guard copied under `raw` remain evidence only.

```sh
python3 verify.py
python3 verify.py --local-artifacts
python3 -m unittest discover -s . -p test_verify.py -v
```

`VERIFIED_ARCHIVE_ONLY` confirms byte integrity and the recorded build-only
boundary. It never qualifies Native execution, cache behavior, a successful
pinch, physical input devices, or issue #27. No copied script or Gradle task
runs during verification.
