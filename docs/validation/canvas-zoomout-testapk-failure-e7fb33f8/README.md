# Failed e7 test APK build

The test-only build for source `e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630`
failed. It produced no new test APK. Native execution remains `NOT_RUN`.
The earlier `b06cefcc` scroll result and failed pinch preparation retain their
original producer and qualification.

The initial dry run recorded 100 tasks. The subsequent guarded dry run admitted
37 AndroidTest tasks and excluded 63 producer tasks. It completed successfully
without executing the graph. The actual build then failed with exit 1 after
14.210 seconds. Ten preparation tasks executed before the Kotlin task failed.
The log also records one skipped task and one task already up to date.

Gradle rejected the Kotlin task's `friendPathsSet$kotlin_gradle_plugin_common`
property because the excluded `:androidApp:bundleE2eClassesToCompileJar` producer
had not completed. The build failed during that property evaluation.
The separately archived bytecode study is `SOURCE_REVIEW_ONLY`;
its proposed disabled-producer experiment was not executed by that review.
This archive does not implement that proposal or authorize a retry.

The post-failure census found zero Kotlin test output files, compared with 22
copied from the old build. All 34 Java test output files were byte-identical to
the old `b06cefcc` files. Neither new collector class existed. No new test APK
existed. Old output files are not attributed to `e7fb33f8`.

The build driver recorded all 11,985 original frozen build files unchanged.
It separately compared 11,448 copied production-input files before and after
the failed build; those identities are equal. Test output files were excluded
from that production comparison. The recorded 312 production source files match
the frozen producer. The app APK and snapshot helper were reused, not rebuilt.

`index.json` records the origin, raw size and SHA-256, archive size and SHA-256,
and compression for each of 18 inputs. JSON files over 64 KiB use gzip with
mtime zero and no embedded filename. All other inputs retain their exact bytes.
The packet contains no APK, class or JAR binaries. It includes the failed build,
the initial graph/preflight, and the later read-only provider-completion study.
Copied scripts and Gradle files are evidence and are never executed by the
verifier. Origin paths are provenance, not verification dependencies.

From this directory, run:

```sh
python3 verify.py
python3 -m unittest discover -s . -p test_verify.py -v
```

`VERIFIED_ARCHIVE_ONLY` confirms archive integrity and the recorded failure
boundaries. It never qualifies a successful build, pinch, cache operation,
Native run, physical input device, or issue #27.
