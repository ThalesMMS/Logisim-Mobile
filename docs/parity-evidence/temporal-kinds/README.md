# Temporal sweep by component and attribute

These files are the output of `scripts/oracle/temporal-kinds/run.sh` on Evolution `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (JAR SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`). [`receipt.json`](receipt.json) records, for each suite, the Mobile source revision, the core JAR SHA-256, the command and the artifact SHA-256. The first campaign ran every suite on Mobile `72b6bae3b07b4b4f8ae69de8c7bb486713dc377f` (core JAR `17714d7e9ccd72b842711fcdd448c770e1200c2787618d8a611e9d744a1b15b6`). On 2026-11-07, `python3 scripts/parity_matrix.py reverify temporal-kinds --suites default` re-ran the default suite on Mobile `51b03c89` with identical results. Evolution was built locally with JDK 21.0.13. The runs used macOS on Apple silicon.

Each line of `default.tsv`, `variants.tsv`, `negative.tsv` and `pairs.tsv` gives a fixture ID, then the result in Mobile `settle` mode and in Mobile `step` mode. `attribute-options.tsv` is the desktop attribute table that the variants were generated from. The script README explains the results and the known causes.

`roundtrip.tsv` gives the result of simulating each file re-saved by Evolution in Mobile `settle` mode.

The table lists the hashes of the first campaign; `receipt.json` holds the current ones.

| File | SHA-256 |
| --- | --- |
| `attribute-options.tsv` | `76f38442a53d6189a4d79f82a639b3ea85a0610cf51652e7723b0f4a7e9bf81a` |
| `default.tsv` | `0e59499f3592e335c572ab64edef4394c6a74dfde62fad660056ca6d8ac42904` |
| `negative.tsv` | `5059309e1479b8e215d460b33177d98d1c1412d4f68bc9142e62c7ac5bc6fbf5` |
| `pairs.tsv` | `051a80ddfbeaf65b57421c21b1e17fcebfaefe94be3dcb8e3c0b3b014900b08c` |
| `variants.tsv` | `39d1692dcd2b34252027dda702669b249a9226822207ab28fed8b36e8f4ee1ab` |
| `roundtrip.tsv` | `4bdea8c47e277437929961f6c647aee83dc9b1c58d25a926d762f9a00aefbd99` |
