# Differential replay at Mobile 4764cf9a against Evolution d58a61e0

Run on 2026-11-08 for #354, which changes the oscillation cutoff of `CircuitSimulator.settle`. Source commit `4764cf9afdc13da60d5e5349a0e28586fd82672e`, desktop commit `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, macOS (Apple silicon), Homebrew OpenJDK 21.0.13, clean worktree.

The seven circuit fixtures produced 222 records with zero differences, including the desktop reopening of each exported file. The six temporal fixtures (155 records) match in both Mobile modes, `settle` and `step`. The per-fixture TSV records are identical to `oracle-replay-9ef2232e`; the exported `and` and `hierarchy` files differ only in the label text written by later export changes on `main`.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar` at the source commit) | `02f8b35d15ab82f4930f21bc3761c1e95b42e23987c30bb2bff3c9371f2af7be` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

`receipt.json` is the unchanged replay receipt. The archived `source/` and `inputs/` copies were removed before committing; their hashes remain in the receipt.

```sh
./gradlew :core:jvmJar && cp core/build/libs/core-jvm.jar /tmp/core-jvm-354.jar
python3 scripts/oracle-replay-receipt.py --source-root . \
  --mobile-revision 4764cf9afdc13da60d5e5349a0e28586fd82672e \
  --desktop-revision d58a61e0c290361722b43df5b1a6b86c1f7c7f0d \
  --core-jar /tmp/core-jvm-354.jar --desktop-jar EVOLUTION_JAR \
  --core-sha256 02f8b35d15ab82f4930f21bc3761c1e95b42e23987c30bb2bff3c9371f2af7be \
  --desktop-sha256 d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038 \
  --output docs/parity-evidence/oracle-replay-4764cf9a
```

This evidence covers the headless JVM circuit and temporal corpora only.
