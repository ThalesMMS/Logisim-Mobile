# Android Files stopped at the SAF import status

One `--scenario files` invocation failed on API 35 phone emulator `emulator-5558`, with English app UI. The fresh initial VIEW import and the incoming SEND case reached their public Copy XML checks. The next SAF local-import case failed while waiting 30s for its named `status_file_opened` control. The native unit ran once without fallback. The seven later cases were not run; this archive does not close #35 or invalidate the earlier #25 evidence.

| Original step | Result |
| --- | --- |
| Initial VIEW before the nine cases | Public XML, named status and original source-preservation assertion completed. |
| incoming-share-imports-a-copy | PASS, recorded 63.21s. |
| saf-local-import-preserves-source | FAIL: named `status_file_opened` was not observed within 30s. |
| cancel-open-preserves-project | NOT_RUN |
| cancel-export-preserves-project | NOT_RUN |
| cancel-share-preserves-project | NOT_RUN |
| external-receiver-reads-share-grant | NOT_RUN |
| invalid-and-incompatible-files-preserve-project | NOT_RUN |
| save-as-local-document | NOT_RUN |
| recover-create-document-after-process-death | NOT_RUN |
| native-file-workflow outer record | FAIL, propagating the SAF error. |

The controller recorded one invocation, exit 1, and 233.289508292s total. The [original results](raw/results.json), [primary traceback](raw/failure-error.txt), [controller record](control/unit.json) and [archive receipt](receipt.json) retain the values. These durations are observations of this run, without an idle-host claim or performance budget. This unit followed editing R3 on the same emulator; it does not isolate a cause across attempts.

The frozen execution checkout was `abc480b3`; its Files source included `e7933b18` and the original readiness change `a39f7e14`. Main was producer `3a0a01f1`, test APK remained `19ab4b9d…`, and helper came from `5c2abc60`. Before/after controller readbacks match the three pinned APK hashes, all 71 prepared source entries and all four local APK/receipt entries. Recorded settings stayed equal, the reader was off, and the recorded last-ANR output did not change. The runner's own completed `installedArtifactsAfter` field is absent because it stopped at the failure; the controller independently completed its postflight.

The two public Copy XML files, [incoming-sap1.circ](raw/incoming-sap1.circ) and [incoming-share.circ](raw/incoming-share.circ), each contain seven circuits, 156 components and 318 wires according to the authenticated historical SAP1 oracle. That oracle checks structural counts, not complete equivalence to every original SAP1 attribute/wire. Their observed tokens are distinct (`565fd484…`, `b2b4a0bc…`). `last-document.circ` is byte-identical to the SEND export. The incoming-open records retain route, URI, expected name and observed named status. The frozen VIEW producer compared external source bytes after importing; SEND checked its token/status/public XML but did not perform that source readback. The archive does not invent separately retained original provider payloads or readback stdout.

The staged SAF source [logisim-e2e-source-android35-files-c3d7-5558-001.circ](raw/logisim-e2e-source-android35-files-c3d7-5558-001.circ) is 48424 bytes, with token `aa7bea2d4d7441a899115cc43f3f3db0`. Replay reconstructs those exact bytes by applying the authenticated historical marking function to the frozen example. [Report 4](raw/import-report-4-ui.xml) shows that filename and seven circuits. The three failure trees each retain `Operation cancelled.` and a Canvas. These are retained observations; they do not establish why the expected status was absent or identify callback, Back, IME or lifecycle delivery.

There is no public `local-import.circ`. Failure collectors extracted private workspace checkpoints 9/10/11 with the SAF token and seven circuits/156 components/318 wires. Their normalized XML bytes match across all three collectors: 52858 bytes, SHA256 `bbfb6f8397d5d837f794bb0bdbcd487d1e747785b9349b8c7673681152d5b6a5`. They are private persistence observations, not a completed public SAF export or a PASS for source preservation. The three original TARs are byte-identical and remain local with SHA references; only the final 53068-byte workspace member is included under `private/`.

Cancellation readiness, invalid-input preservation, receiver share grants, Save As and recovery were not reached. Their files are not required or fabricated here. In a completed future run, the names `before-share.circ`/`after-share.circ` would be overwritten by the second share case; they must not be attributed independently to both cancel and delivery. This failed unit has neither file. There are no SDK focus captures or recovery PID-transition evidence in this execution.

Cleanup force-stopped only the owned Main app, then sampled an empty Main PID. Before that cleanup, the controller's test-package PID getter returned 17772 and its helper PID getter was empty. The archive does not claim that the test/provider and helper were both stopped by Main cleanup. The 301 audit records contain ADB command requests and timestamps; they do not prove completion or app event delivery.

## Portable replay

The package retains 95 unchanged raw files, 101 unchanged controller files, 83 frozen source inputs plus their authenticated manifest, one private member and two provenance files: 283 authenticated files in total. No APK, JAR or original TAR is included. A full local mirror also preserves the three TARs and was compared by size/SHA before and after copying; Root was informed before reusing the device lane.

```sh
python3 -B docs/validation/android35-files-3a0a01f1-001/verify.py
python3 -B -m unittest discover -s docs/validation/android35-files-3a0a01f1-001 -p test_verify.py -v
```

The verifier uses Python's standard library and this packet only. Its replay PASS means that the archived FAIL was verified; `originalNativeStatus` remains FAIL and `nativeReplay` remains NOT_RUN. It authenticates the historical source manifest before loading the SAP1 oracle or executing the two small historical helper definitions. Twelve checks cover public SAP1 count corruption, source/token and private/public substitutions, result promotion, an invented public SAF export, wrong report/status, changed settings despite matching getters/census, source tampering before import, links and relocation outside a checkout. Semantic controls recompute inventory hashes and assert the relevant semantic rejection. The [replay output](verification.json) and [test log](checks.log) contain no new native execution.
