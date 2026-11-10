# Android folder integrity review for issue #53

Result: PASS. No integrity mismatch, missing receipt path, or silent inventory exclusion was found in exact commit `2832d29b053da7026540dcc63bd62aed1d040845`, path `docs/validation/portable53-android-folder-pass`.

The package was extracted directly with `git archive` into an isolated temporary directory. This review used no UI, ADB, build, Gradle, repository edit, push, or issue-state action. Behavioral oracle replay belongs to the parent acceptance review.

| Check | Result | Recomputed evidence |
| --- | --- | --- |
| Package hash coverage | PASS | 163 files, 3,777,668 bytes. Every one of the 162 `SHA256SUMS` entries matches. Only `SHA256SUMS` itself is uncovered. There are no duplicate, missing, or extraneous entries. |
| Copy chain | PASS | All 156 destinations match recorded SHA-256 and byte length. All 155 filesystem originals match byte for byte, and the one contract Git source matches `5cb2fead2743cbba84f0efbad51e8bca822c7f05:fixtures/user-scenarios/editor-actions-v1.json`. No duplicate destination paths. |
| Entire native run copied | PASS | Original run sourceRoot and committed `passed-48824` each contain exactly 84 files. Their relative names and bytes match completely. |
| Native receipt | PASS | Literal status `PASSED`; SHA-256 `ac99fccb7831d3276446a96e1681db1260bf311dc628aacb6263569589a1ccb3` matches qualification summary. All 16 receipt evidence paths, 4 raw TAR paths, and 2 exported archive paths exist. |
| Four raw/extracted snapshots | PASS | Before/imported/restarted/reopened TARs contain respectively 9/11/13/15 workspace files. Each TAR's member names and bytes exactly match its committed extracted workspace. No unsafe member, link, duplicate name, or non-flat regular workspace path was found. These checks do not establish shared-lock capture. |
| Two actual exports | PASS | Distinct before/after evidence paths, each 4,905 bytes with SHA-256 `7c0c1b4a4572a29c14273d8dd676afe43ef70f41e144c73ca4d26142b1a5c8bd`, matching its receipt. Restart flags are respectively false and true. |
| Native identity record | PASS | Receipt records import and restart as `13dc653f7acf119c9f70fe84ad1aa6a1`, and reopen as `ef5cd59f81bd8ad0e9d2a63a43b78854`. The four corresponding snapshot copies are present and byte-identical to the originals. Semantic identity checks are delegated to the strict storage replay. |
| Frozen native sources | PASS | All 8 scenario-source hashes and the fixture-contract hash match the native run manifest. Product source remains `59d5606bfe70e5a8119a387f5c4b5c2a85884c8e`; harness source remains `5cb2fead2743cbba84f0efbad51e8bca822c7f05`. |
| APK provenance metadata | PASS | All three installed package/hash pairs match before and after. Editor SHA-256 is `aefbd33fd958a32c81e35aa667b19ccc9ea96609e80107281204dbbd1a8cd546`. This is a metadata comparison, not a new installation or binary execution. |
| Native results | PASS | Unchanged original results record exactly `portable-folder-storage-roundtrip`, `passed`, `217.81` seconds. |
| Earlier FAILED separation | PASS | The success commit only adds the 163 files under its new evidence path. It modifies no prior failure or bundle artifact. The earlier Android bundle package's `results.json` is byte-identical to commit `495fccc7` and still records its later folder attempt as failed due to missing Downloads. The new native result is a separate run, not a rewritten old receipt. |
| Behavioral oracle replay | NOT_RUN | Parent reviewer runs the three strict storage/Desktop commands against these bytes. |

The five files without original copy-chain sources are the newly authored README and qualification summary plus three relocated oracle reports. They are all covered by `SHA256SUMS`. The copy-chain and hash-list files are also covered except for the hash list's expected self-exclusion.

This PASS applies to integrity of the recorded Android folder product and emulator run. It does not reattribute the run to a newer main product or claim performance, physical-device, iOS, or Epic-wide qualification.

Detailed recomputation results: `/tmp/logisim-portable53-folder-integrity-results.json`.
Recheck script: `/tmp/logisim-portable53-folder-integrity-check.py`.
Exact extraction location: `/tmp/logisim-portable53-folder-integrity-path.txt`.
