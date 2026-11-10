# Differential replay at Mobile 9ef2232e against Evolution d58a61e0

New run (2026-11-07) for the `reverificar` sweep of #236. It replaces `oracle-replay-51b03c89` as the evidence of `format/circ`, whose watched files changed in `99a90313` (#310, #312, #313).

The replay passed on source commit `9ef2232e2cf67038daf986ddea7d83febe595e12` (`origin/main`), against desktop commit `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, on macOS (Apple silicon) with Homebrew OpenJDK 21.0.13, from a clean worktree. Both runners used `--skip-build`.

The seven circuit fixtures produced 222 records with zero differences, including the desktop reopening of each exported file. The six temporal fixtures match in both Mobile modes, `settle` and `step`. The per-fixture records are identical to `oracle-replay-51b03c89`; only local paths and the core JAR hash differ. #311 (`796ad2f0`) changed the default shown characters (U for unknown, - for don't-care, as Evolution's `AppPreferences`), but files and traces keep fixed symbols, so the records did not change.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar` at the source commit) | `5376545481b40008dd7b889cfb5af9cedba241845a68d7b3416b9a1558e4d898` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

`receipt.json` is the unchanged replay receipt with the complete commands, exit codes and hashes. The archived `source/` and `inputs/` copies were removed before committing; their hashes remain in the receipt.

Command:

```sh
./gradlew :core:jvmJar && cp core/build/libs/core-jvm.jar /tmp/reverify-core-jvm.jar
python3 scripts/oracle-replay-receipt.py --source-root . \
  --mobile-revision 9ef2232e2cf67038daf986ddea7d83febe595e12 \
  --desktop-revision d58a61e0c290361722b43df5b1a6b86c1f7c7f0d \
  --core-jar /tmp/reverify-core-jvm.jar --desktop-jar EVOLUTION_JAR \
  --core-sha256 5376545481b40008dd7b889cfb5af9cedba241845a68d7b3416b9a1558e4d898 \
  --desktop-sha256 d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038 \
  --output docs/parity-evidence/oracle-replay-9ef2232e
```

This evidence covers the headless JVM circuit and temporal corpora. It does not establish Android or iOS execution, UI parity, hardware or performance.
