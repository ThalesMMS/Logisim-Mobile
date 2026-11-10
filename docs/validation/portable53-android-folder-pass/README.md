# Android Portable53 folder qualification

The native folder scenario passed in 217.81 seconds on the temporary Android API 35 emulator 5558. It imported an explicitly granted folder containing three transitive `.circ` sources, exported before restart, restored after restart, exported again and reopened the actual export as a fresh workspace. The original `PASSED` receipt and every original run file are retained unchanged in `passed-48824`.

The product source was `59d5606bfe70e5a8119a387f5c4b5c2a85884c8e`; the harness was `5cb2fead2743cbba84f0efbad51e8bca822c7f05`. The installed editor APK SHA-256 was `aefbd33fd958a32c81e35aa667b19ccc9ea96609e80107281204dbbd1a8cd546`. All three installed APK records, including the snapshot helper, match before and after the run. Their metadata is retained; APKs and reference JARs are excluded.

Both real app exports contain 4,905 bytes, SHA-256 `7c0c1b4a4572a29c14273d8dd676afe43ef70f41e144c73ca4d26142b1a5c8bd`. Separate strict commands opened each file in the desktop reference implementation. Each command bound its artifact and opposite-restart peer to the same native `PASSED` receipt. Both desktop runs preserved three circuit definitions and two top-level instances and produced independent outputs 8 and 3. The mobile graph also contains 11 components, seven wires and four runtime instances.

The strict workspace oracle validated all four extracted snapshots. Import created `13dc653f7acf119c9f70fe84ad1aa6a1`, restart restored that project, and reopening created `ef5cd59f81bd8ad0e9d2a63a43b78854`, absent from every earlier catalog. Earlier catalog entries were retained. The 4,900-byte imported source archive survived restart; the reopened copy persisted the exact 4,905-byte app export as its source archive. Native editable-file associations were absent from the new copies. The native canvas evidence shows outputs 8 and 3 after import, restart and reopening.

The four raw TARs were read without following links. Extraction accepted only expected directories and flat regular `files/workspace/<filename>` members and rejected escapes, duplicate paths and aliases. `snapshots` retains the extracted copies. These are frozen Android TAR captures; no shared-lock capture is claimed.

`original-oracles` preserves the first three strict reports and input-admission record unchanged. `relocation` contains three additional passing reports run against this package's copied artifacts, receipt and snapshots. `oracle-source` and `fixtures` retain the exact oracle inputs and sources, including the scenario contract pinned by the native manifest. The reports record their source and JAR hashes. Folder import captures referenced `.circ` sources, so all three strict commands use `--allow-no-opaque`.

`copy-chain.json` binds all copied files to their source paths or immutable Git blobs. `SHA256SUMS` covers the package. The complete native run includes launch logs, source publication, CreateDocument session records, frozen scenario sources, per-query XMLs and navigation traces. Trace window context is explicitly XML-only, with WM token and PID unobserved at the tap. This qualification covers this Android folder flow and desktop reopen of its two exports. Earlier failed attempts and other platform runs retain their own status. This is not performance evidence.

Run these commands from the repository root with the pinned reference JARs supplied through the task-specific variables:

```sh
EVIDENCE=docs/validation/portable53-android-folder-pass
NATIVE="$EVIDENCE/passed-48824"
BEFORE="$NATIVE/logisim-portable53-f5fa1770c8a64097b7a8a81f25949e02-folder-before-restart.lsebdl"
AFTER="$NATIVE/logisim-portable53-f5fa1770c8a64097b7a8a81f25949e02-folder-after-restart.lsebdl"

python3 scripts/verify-portable53.py \
  --core-jar "$PORTABLE53_CORE_JAR" --desktop-jar "$PORTABLE53_DESKTOP_JAR" \
  --allow-no-opaque --origin app-export --artifact "$BEFORE" --peer "$AFTER" \
  --ui-receipt "$NATIVE/portable-folder-receipt.json" \
  --output /tmp/portable53-folder-desktop-before.json

python3 scripts/verify-portable53.py \
  --core-jar "$PORTABLE53_CORE_JAR" --desktop-jar "$PORTABLE53_DESKTOP_JAR" \
  --allow-no-opaque --origin app-export --artifact "$AFTER" --peer "$BEFORE" \
  --ui-receipt "$NATIVE/portable-folder-receipt.json" \
  --output /tmp/portable53-folder-desktop-after.json

python3 scripts/verify-portable53.py \
  --core-jar "$PORTABLE53_CORE_JAR" --desktop-jar "$PORTABLE53_DESKTOP_JAR" \
  --allow-no-opaque \
  --workspace-before "$EVIDENCE/snapshots/before/workspace" \
  --workspace-imported "$EVIDENCE/snapshots/imported/workspace" \
  --workspace-restarted "$EVIDENCE/snapshots/restarted/workspace" \
  --workspace-reopened "$EVIDENCE/snapshots/reopened/workspace" \
  --output /tmp/portable53-folder-storage.json
```

Reference SHA-256: Core `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20`; desktop Logisim `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`.
