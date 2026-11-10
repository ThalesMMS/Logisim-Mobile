# Android portable folder window capture order

Attempt 13849 failed after its first real bundle export with `The actual export session is not linked to the imported workspace`. The complete original run is copied unchanged into `failed-13849`. It does not qualify the folder roundtrip or issue 53.

The imported snapshot records active project `72be893d7f51138afdb9f99380ea7957`, revision 1. For session `36d6551b-a976-41f1-95e3-1a0dabfb9124`, its stable window link still points to previous project `f33b7bd5cea6be9a6459411a64b31c74`. The same TAR also contains a `.workspace-link.new` already pointing to the imported project. That temporary file is recorded separately from the stable link.

Both later failure TARs are byte-identical. They record the same active project at revision 2, with the stable window link pointing to the imported project and no pending window link. The captured states establish this order. They do not measure the interval between the catalog update and the stable window-link replacement. The frozen scenario's export guard compares the actual CreateDocument session against the earlier imported snapshot.

The CreateDocument JSON records that exact session, `Top53.lsebdl`, and MIME `application/x-logisim-bundle`. The retained before-restart export is a real partial app export of 4,905 bytes, SHA-256 `0bc5e1a2a7cbf699324d692964d8c7a1eb24038b31a271072cdb1201267da689`. Its four ZIP entries pass CRC checks. There is no after-restart peer, passed native scenario receipt, second launch, or restored/reopened snapshot. No strict artifact qualification or desktop reopen was run for this attempt.

`replay.py` reads the three TARs in memory without extraction, validates their paths and envelope checksums, and reuses the exact frozen scenario's pure token readers. It verifies the stable and pending window links, CreateDocument session, failed result and partial ZIP. It writes only to standard output and contacts no device. Its checked output is retained in `replay-report.json`.

Run the replay from the repository root:

```sh
python3 docs/validation/portable53-android-folder-window-order/replay.py
```

`copy-chain.json` binds all 71 original files to their source paths, byte lengths and hashes. `SHA256SUMS` covers the copied files and this package's generated records. No APK or reference JAR is included. The original manifest records the frozen product `59d5606bfe70e5a8119a387f5c4b5c2a85884c8e`, harness `1d146f334f80fe35e0c47e46568cd3761f6583f2`, and all three installed APK hashes. This evidence makes no performance claim.
