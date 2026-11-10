# Partial Android folder export: desktop inspection

The first actual export from failed Android folder attempt `13849` passed an isolated mobile/desktop artifact inspection. The 4,905-byte file has SHA-256 `0bc5e1a2a7cbf699324d692964d8c7a1eb24038b31a271072cdb1201267da689`; it is copied here as `actual-export-before-restart.lsebdl`. It originated from `59d5606b-portable-folder-5558-en-1d146f33`, with product APK SHA-256 `aefbd33fd958a32c81e35aa667b19ccc9ea96609e80107281204dbbd1a8cd546`.

The native result remains literally `failed`: `The actual export session is not linked to the imported workspace`. The driver stopped at its session/workspace guard before force-stop. There is no second export, completed restart/restoration proof, reopened workspace or passed UI receipt for this attempt. This directory does not qualify the complete Android flow, issue #53 or the epic.

`partial-export1-desktop.json` is explicitly `PARTIAL_REAL_APP_EXPORT_DESKTOP_VALIDATED`, with `INCOMPLETE_EXPORT1_ONLY`, native status `FAILED` and no peer. The existing Kotlin artifact oracle verified ZIP CRCs, source metadata, explicit identities, three definitions, 11 components, seven wires and four runtime child states. The desktop JAR reopened this actual exported package, retained the two shared Bridge53 instances and produced `00001000` and `00000011`. Folder mode requires the referenced sources and does not require opaque bundle entries.

## Small provenance proof

`native-observation/` retains the original failed results, producer/build manifests, pending native CreateDocument operation, earlier workspace metadata, save-completed UI capture, frozen driver and guard traceback. The APKs/JARs and full run are excluded. `copy-provenance.json` records original paths and hashes; `SHA256SUMS` covers all files in this directory.

The exported session was `36d6551b-a976-41f1-95e3-1a0dabfb9124`. The earlier imported snapshot recorded project `72be893d7f51138afdb9f99380ea7957` while its window still targeted `f33b7bd5cea6be9a6459411a64b31c74`. The later TAR collected at failure already targeted `72be893d7f51138afdb9f99380ea7957`. The exact 110-byte window-link member is retained as `native-observation/window-at-failure.workspace-link`, with the TAR/member hashes and decoding recorded in `guard-observation.json`. This contrast documents the earlier observation used by the guard; it does not promote the failed flow or prove a process restart.

## Oracle invocation

`commands.json` and `commands.sh` record the exact direct Java/Kotlin compiler and low-level artifact commands; `oracle.log` records their successful execution. They use the frozen oracle sources already preserved in `../portable53-ios-native-pass/frozen/offline-replay/` and an existing compiler cache. No Gradle, Xcode, device or validator modification was used.

- Core JAR SHA-256: `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20`.
- Desktop JAR SHA-256: `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`.
- `expect-opaque` was `false`; origin was `partial-app-export`; no peer argument was supplied.

The low-level oracle checks an artifact and does not enforce the complete native receipt contract. Its transient artifact-only output was explicitly reclassified into the partial report above and removed. Repeating the recorded command produces only another low-level artifact inspection. The strict `verify-portable53.py --origin app-export` validator remains unchanged and still requires two actual exports bound to a passed native receipt.

Run `shasum -a 256 -c SHA256SUMS` here to verify the committed copies. Original absolute paths in reports and commands are retained as historical observations; the exported bytes and small native provenance proof are available at the relative paths above.
