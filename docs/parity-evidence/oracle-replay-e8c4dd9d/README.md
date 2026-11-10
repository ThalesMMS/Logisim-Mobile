# Differential replay at Mobile e8c4dd9d against Evolution d58a61e0

New run (2026-11-07) after #321, #323 and #324 changed `CircCodec.kt`, the file `format/circ` watches. It replaces `oracle-replay-9ef2232e` as the evidence of that row.

The replay passed on source commit `e8c4dd9deebd5c16ab1f6ed46ed081a419075794`, against desktop commit `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, on macOS (Apple silicon) with Homebrew OpenJDK 21.0.13, from a clean worktree. Both runners used `--skip-build`.

The seven circuit fixtures produced 222 records with zero differences, including the desktop reopening of each exported file. The six temporal fixtures (155 records) match in both Mobile modes, `settle` and `step`. #321 renamed the labels of `examples/and.circ` (`A AND B` to `A_AND_B`) and `examples/hierarchy.circ` (`Saída` to `Y`), which Evolution would rename on open; their desktop records did not change, and `e8c4dd9d` only updates the two fixture hashes in `examples/corpus/reference/provenance.json` (`verify-corpus.py --update-reference`). A first replay at `4a3b2ae1` failed on exactly those two hashes and was discarded.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar` at `4a3b2ae1`; `e8c4dd9d` changes no core source) | `2704f94dfe553ebac735829454b73aa206bc97cd9cd1a2e4145e53fdae447900` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

`receipt.json` is the unchanged replay receipt with the complete commands, exit codes and hashes. The archived `source/` and `inputs/` copies were removed before committing; their hashes remain in the receipt.

Command:

```sh
./gradlew :core:jvmJar && cp core/build/libs/core-jvm.jar /tmp/t3-ce0b-core-jvm.jar
python3 scripts/oracle-replay-receipt.py --source-root . \
  --mobile-revision e8c4dd9deebd5c16ab1f6ed46ed081a419075794 \
  --desktop-revision d58a61e0c290361722b43df5b1a6b86c1f7c7f0d \
  --core-jar /tmp/t3-ce0b-core-jvm.jar --desktop-jar EVOLUTION_JAR \
  --core-sha256 2704f94dfe553ebac735829454b73aa206bc97cd9cd1a2e4145e53fdae447900 \
  --desktop-sha256 d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038 \
  --output docs/parity-evidence/oracle-replay-e8c4dd9d
```

This evidence covers the headless JVM circuit and temporal corpora. It does not establish Android or iOS execution, UI parity, hardware or performance.
