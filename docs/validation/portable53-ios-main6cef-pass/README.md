# iOS Portable53 MAIN6cef qualification

Both native cases passed on the B25 iPad simulator running iOS 27.0, using production artifact `6cef05429cf66be23569aaebc3c72950cf3fe53a`. The product manifest records test-only validation source `85da031e4ed11474c2e763976392729810ef142d`. The selected native cases, their original logs, actual Run configurations and receipts are retained unchanged.

| Native case | Gate | Test duration | Export bytes, each | Export SHA-256, before and after restart |
| --- | --- | ---: | ---: | --- |
| Bundle | `NATIVE_PASS`, exit 0 | 356.118 s | 5,395 | `930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113` |
| Folder | `NATIVE_PASS`, exit 0 | 318.815 s | 4,905 | `d9ff48bdcb17770ecfc11b37309b92cf0bcb1bb15ed3b24000c78fd0634bbe0e` |

Each case imported its source, archived a real app export before restart, observed termination and a new foreground process, restored the same workspace, exported again and reopened the actual export as a fresh workspace. The canvas evidence shows independent outputs 8 and 3. The receipts preserve the observed process IDs and actual native file destinations. The bundle case retains its two opaque entries; the folder case preserves the three referenced `.circ` inputs.

Six new strict offline commands validated the four snapshots for each case and opened every actual before/after export separately in the desktop reference implementation. Each desktop command bound the artifact and opposite-restart peer to that case's new native `PASSED` receipt. All six commands passed. Each export preserves three circuit definitions, two top-level instances and outputs 8 and 3. The copied evidence passed six further commands from `replay.py`. Original and replay reports have separate paths, command records and logs.

| Case | Imported and restored workspace | Fresh reopened workspace |
| --- | --- | --- |
| Bundle | `6086b81489d71caa348cc9d04fc3fa2f` | `a796d460c21455246db49f725dfd0770` |
| Folder | `28edb25a840b9557ca04f35cdc4b63b1` | `86faeb465532963c228972ddafbe0952` |

The strict workspace checks retain earlier catalog entries, require each new copy to be absent from all earlier catalogs, validate source-archive persistence across restart and bind the reopened copy's source to the exact app export. Native editable-file associations are absent from the new copies.

The installed artifact during both proofs and their post-run records has tree SHA-256 `d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080`, executable SHA-256 `198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946` and debug dylib SHA-256 `db2c909500c95f345362fdf06f5fd17c3647af9800863fc53d0b6a9bf31647cf`. Bundle installation replaced an earlier app, as its original `before` record shows. Folder then reused the installed MAIN6cef artifact. No claim is made that the app present before bundle installation already had those fingerprints.

Each actual harness contains its executed `Run.json`, Catalog with 2,496 messages and 17 original Swift files. Catalog SHA-256 is `83a6f69e2a6d530a8afff326250dcd2c09416d4deeb35187d03117b2b5f6c6ca`. The original shared-lock collector is `PortableWorkspaceEvidence.swift`, SHA-256 `fad83696f0ec4b0af3abfdb6a203e3f3ae5a7e4abc273cea6b0cf1552e1c3660`. Its eight snapshot records retain `LOCKED_READ_ONLY_SNAPSHOT`; the collector uses `LOCK_SH` on the existing catalog lock. The evidence stores preserve catalog, project, attachment and window-link bytes.

`preparation-only` contains the original prepared inputs, including UUID-zero Run placeholders and `NOT_RUN` states. These are provenance, not additional native executions. Its product manifest SHA-256 is `6006751321c48752f5676ddaba3b6ae20cbc7f10f43f60a5000c855a1e34ac17`; frozen source-inventory SHA-256 is `6b2ad9659a66d45b233d30ac665b54b3dbefc565ad5c8269788078fad9004bf7`. `producer-metadata` retains the original 301-entry production-input manifest with digest `fe8c7e9337359543a49fb38d9a6c6f003866436a16cb327acbb76abc8c80c9e2`, independent source review, cache provenance and completed build pipeline. Producer build-only `NOT_RUN` states retain their original meaning alongside the two later native gates.

`independent-admission` preserves the separate `ADMITTED_NO_FINDINGS` records, their original hash inventory and read-only record-review script. That review covers 681 files in the eight snapshots, four actual exports and the 301 production inputs. Its general gate remains `PARTIAL`; its positive admission concerns these two Portable53 simulator cases. The record-review script was archived without executing it again.

`native` retains both complete documents and harness directories, external peer fixtures, results, test/build logs and installed-after records. `original-oracles` retains the six new source-path reports and their logs. `oracle-source` contains the frozen original verifier, contracts, Kotlin oracle, supporting sources and fixtures. `copy-chain.json` binds copied bytes to their original locations; `SHA256SUMS` inventories this package. Additional attachment exports, simulator logs and last-screen exports remain at their original locations and are indexed separately by hash in `additional-native-files-not-copied.json`. App bundles, JARs, DerivedData and xcresult packages are excluded.

The retained save-picker frames show the app's progress panel, and the subsequent records show the completed app export. They are not labeled as stable screenshots of the native save picker. The native source pickers, folder confirmation, saved files, restart, durable snapshots and fresh reopen have their own retained records. Earlier Beta and Android qualifications remain separate; no old exports or reports were substituted. This package concerns these simulator cases and their desktop checks, not physical-device coverage or performance.

`execution-context.json` records owner-declared context that Phone17e issue 21/case 003 was active on FINAL6cef with its older exclusive DerivedData while B25 used new isolated DerivedData. This offline task accessed neither that phone nor its cache. CPU/GPU concurrency was not measured. The two reported times are XCTest case durations and provide no performance evidence for issue 48.

Replay all six offline checks from the repository root, using the pinned reference JARs and a new output directory:

```sh
python3 docs/validation/portable53-ios-main6cef-pass/replay.py \
  --core-jar "$PORTABLE53_CORE_JAR" \
  --desktop-jar "$PORTABLE53_DESKTOP_JAR" \
  --output-dir /tmp/portable53-ios-main6cef-replay
```

The replay validates copied-file hashes and both native gates, uses the frozen verifier inside this package, and runs no device tools. Bundle checks require opaque entries; only folder checks use `--allow-no-opaque`. Reference JAR SHA-256 values are Core `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20` and desktop `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`.
