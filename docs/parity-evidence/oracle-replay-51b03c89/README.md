# Differential replay at Mobile 51b03c89 against Evolution d58a61e0

The replay passed on source commit `51b03c8990265a667176c271f408021979c78d36`, against desktop commit `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`. It ran on 2026-11-07 on macOS (Apple silicon) with JDK 21, from a clean worktree at the source commit. Both runners used `--skip-build`.

The seven circuit fixtures produced 222 records with zero differences, including the desktop reopening of each exported file. The six temporal fixtures match in both Mobile modes, `settle` and `step`.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar` at the source commit) | `8fa1d01d0a6f396e71ef435fb5bd3122e0a2d3c13f137c10cb4060ffa9261e50` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

`receipt.json` is the unchanged replay receipt with the complete commands, exit codes and hashes. The archived `source/` and `inputs/` copies (about 400 MB) were removed before committing; their hashes remain in the receipt. `corpus/` and `temporal/` keep the per-fixture records.

Command:

```sh
git worktree add --detach /tmp/replay-src 51b03c89
python3 /tmp/replay-src/scripts/oracle-replay-receipt.py --source-root /tmp/replay-src \
  --mobile-revision 51b03c8990265a667176c271f408021979c78d36 \
  --desktop-revision d58a61e0c290361722b43df5b1a6b86c1f7c7f0d \
  --core-jar core/build/libs/core-jvm.jar --desktop-jar EVOLUTION_JAR \
  --core-sha256 8fa1d01d0a6f396e71ef435fb5bd3122e0a2d3c13f137c10cb4060ffa9261e50 \
  --desktop-sha256 d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038 \
  --output docs/parity-evidence/oracle-replay-51b03c89
```

This evidence covers the headless JVM circuit and temporal corpora. It does not establish Android or iOS execution, UI parity, hardware or performance.
