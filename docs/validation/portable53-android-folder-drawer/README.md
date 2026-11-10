# Android portable folder drawer census

Attempt 14368 failed while opening the exported bundle for the final fresh-copy step. It had already imported the source folder, exported before restart, restored after restart and exported again. The complete original run is retained unchanged in `failed-14368`. Its status remains `FAILED_AFTER_EXPORTS_BEFORE_REOPEN`. No reopened snapshot or native scenario receipt marked `PASSED` exists.

Both actual app exports contain 4,899 bytes and SHA-256 `93da3a9c25dac5e1b012f3448f55437033fa5311cac2416a4b63478f7ef5ed51`. Their four ZIP entries pass CRC checks. These exports remain partial evidence because the final reopen did not complete. No strict artifact validator, storage oracle or desktop qualification was run for this attempt.

The last navigation invocation, `713a48c4…`, records the Downloads tap at 6.4758 seconds, followed by 12 completed hierarchy queries and one terminal query that timed out. The invocation ends at 36.5346 seconds, about 30.06 seconds after the tap. Its earlier presentation and roots-opening queries are counted separately. The original trace records event times and durations, not the content of every intermediate hierarchy.

The final screenshot shows the drawer open with Downloads highlighted and file content visible under the shadow behind it. The final XML exposes one focused `roots_list` and no `dir_list`. The absence of `dir_list` is an observation about that XML, not proof that directory content was physically absent. The read-only activity/window census identifies the resumed and focused activity as `com.google.android.documentsui/com.android.documentsui.picker.PickActivity`. The package census records DocumentsUI version 15, `versionCode=350820300`.

The premise to review is that selecting a native root always closes the drawer. This is a hypothesis for the next source review, not an established cause. The trace does not record the tap bounds or coordinates. Bounds in the final XML describe a later observation and cannot establish where the earlier tap occurred.

`census.py` produces the invocation/phase table from the frozen traces and reads the final XML and package/focus census. It inventories the three snapshot stages from the runner's recorded JSON, hashes their TARs, and checks both ZIPs. It reads local files only, writes to standard output and contacts no device. `census-report.json` and `census-table.md` retain its output. Run it from the repository root:

```sh
python3 docs/validation/portable53-android-folder-drawer/census.py
python3 docs/validation/portable53-android-folder-drawer/census.py --format markdown
```

`copy-chain.json` binds all 91 original files to source paths, byte lengths and SHA-256 hashes. `SHA256SUMS` covers the retained files and generated records. No original bytes were normalized. The original manifest records product `59d5606bfe70e5a8119a387f5c4b5c2a85884c8e`, harness `40493ec1` and the three installed APK hashes. No APK or reference JAR is included, and no performance claim is made.
