# Android Portable53 bundle evidence

The **bundle scenario passed** native import, export, process termination, restoration and reopening as a new workspace on the temporary Android API 35 emulator 5558. The editor APK came from product source `59d5606bfe70e5a8119a387f5c4b5c2a85884c8e`; its installed SHA-256 was `aefbd33fd958a32c81e35aa667b19ccc9ea96609e80107281204dbbd1a8cd546`. The native harness was `bc98f9349dd326a4ba34623e3912ac6321ed1a5d`. The original native receipt is preserved byte-for-byte.

The **outer wrapper later failed the independent folder scenario**, before folder selection, because its roots list did not offer Downloads. `results.json` preserves that failure. This package does not qualify the folder flow or the whole of issue 53.

Both actual exported archives contain 5,395 bytes and SHA-256 `930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113`. They are separate files bound to opposite restart stages in the same passed native receipt. The existing mobile/desktop artifact oracle checked the AFTER export and its BEFORE peer, preserving the three source definitions, shared subcircuit definition, component identities, metadata and opaque entries. The desktop reopened the AFTER archive and produced outputs 8 and 3. The peer has identical bytes and was independently checked against the receipt and mobile graph.

Four raw native TARs and their extracted copies are retained. Extraction accepted only expected directories and regular, flat `files/workspace/<filename>` members; it rejected links, escapes and duplicate names. The storage oracle verified fresh import/reopen identities, the same restored project, source archive persistence and absence of inherited native editable-file associations. These are frozen Android TAR snapshots validated by the repository reader; no shared-lock capture is claimed for Android.

`installed-artifacts-after-partial-group.json` is a metadata-only addendum: the three installed APK hashes were read again after the partial group and exactly match the original installed-artifact checks. It adds no UI run, folder qualification or performance claim.

`copy-chain.json` binds every retained source file to its original path and SHA-256. `package-index.json` inventories the final package. The run manifest, exact editor build receipt, triplet provenance, native source publication, launch logs, frozen scenario sources, pinned scenario contract and every PNG/XML listed by the receipt are included. APKs and reference JARs are deliberately excluded. `concurrent-environment.json` records simultaneous validation on other devices; this run is not performance evidence for issue 48.

`original-oracles/` preserves the original reports unchanged, including historical paths. `relocation/` contains fresh strict-verifier reports against the copied archives, receipt and snapshot directories. The native receipt and original report statuses were not rewritten.

The copied evidence can be verified from the repository root with the existing tool and reference JARs supplied through task-specific variables:

```sh
EVIDENCE=docs/validation/portable53-android-bundle-pass
python3 scripts/verify-portable53.py \
  --core-jar "$PORTABLE53_CORE_JAR" --desktop-jar "$PORTABLE53_DESKTOP_JAR" \
  --artifact "$EVIDENCE/logisim-portable53-3d9997d594f94520b9937dd29b5458d3-bundle-after-restart.lsebdl" \
  --peer "$EVIDENCE/logisim-portable53-3d9997d594f94520b9937dd29b5458d3-bundle-before-restart.lsebdl" \
  --origin app-export --ui-receipt "$EVIDENCE/portable-bundle-receipt.json" \
  --output /tmp/portable53-android-bundle-desktop.json

python3 scripts/verify-portable53.py \
  --core-jar "$PORTABLE53_CORE_JAR" --desktop-jar "$PORTABLE53_DESKTOP_JAR" \
  --workspace-before "$EVIDENCE/snapshots/before/workspace" \
  --workspace-imported "$EVIDENCE/snapshots/imported/workspace" \
  --workspace-restarted "$EVIDENCE/snapshots/restarted/workspace" \
  --workspace-reopened "$EVIDENCE/snapshots/reopened/workspace" \
  --output /tmp/portable53-android-bundle-storage.json
```

Reference SHA-256: Core `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20`; desktop Logisim `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`.
