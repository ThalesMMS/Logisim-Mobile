# Differential replay on the 2e20e5f core artifact

The replay passed on source commit `2e20e5f84213657f0e2daa493abd3b30b5cb3671`, against desktop commit `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01`. Both runners used `--skip-build`. No Gradle invocation, fixture update, or reference update occurred.

The seven circuit fixtures produced 222 records: 7 circuit counts, 182 terminal geometries, and 33 signal values. The current desktop original, mobile output, desktop reopening of each exported file, and frozen reference records agree. `corpus/summary.json` reports zero differences. The directory preserves each operation script, exported circuit, and three sets of records.

The temporal corpus contains three fixtures with 57, 57, and 18 records. Both mobile modes, `settle` and `step`, match the current desktop and frozen records, for 132 reference records and 264 mobile comparisons. The saved mode records and successful runner log are in `temporal/`. The temporal harness does not save a separate current-desktop stdout file; its pinned source performs that comparison during the run.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR | `01073771c0c7643efa38d7b25d8c6905d31651f27538e9a6a8f4cd82a75720c4` |
| Desktop JAR | `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01` |
| Raw replay receipt | `4ebc4eb8882a7fb10dd48be628b30ba277bb184ca7a5fd89619d2ab850d31dcd` |

`receipt.json` is an unchanged copy of the replay receipt. It records the declared producer, Git trees, scripts and fixture hashes, verified JAR copies, complete commands, log hashes, exit codes, and before/after checks. The source worktree remained clean at the declared pin throughout the run. `evidence-index.json` lists the hashes of all 47 preserved output files. An independent read-only audit checked the archived files against the Git pin and compared the saved records directly.

To repeat the run, use `scripts/oracle-replay-receipt.py` from the pinned source and supply the recorded core and desktop artifacts. Pass their expected SHA-256 values, both full source revisions, and a new output directory. The tool archives the source pin and copies each JAR before invoking the two existing oracle runners. Its `--help` lists the required arguments. The producer declared the binary's source revision; the replay verifies the supplied binary hashes and source archive rather than inferring binary provenance from a filename.

This evidence covers the reviewed headless JVM circuit and temporal corpora. It does not establish complete component behavior, other compatibility fixtures, Android or iOS execution, UI parity, physical hardware, performance, or energy use.
