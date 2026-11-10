# Parity matrix sources and maintenance

The [parity matrix](../docs/parity.md) shows which parts of Logisim Evolution `d58a61e0` Mobile reproduces and where gaps remain. The header of `scripts/parity_matrix.py` describes the statuses and rules used to generate the matrix. Statuses reflect recorded runs. A source file's presence alone only establishes that an implementation exists.

| File | Role |
| --- | --- |
| `matrix.json` | Hand-maintained declarative source for evidence, differences found in sweeps, scope decisions, areas, and product rows |
| `evolution-d58a61e0.json` | Read-only inventory of 205 entries for libraries, menus, preferences, options, formats, CLI features, and integrations in the pinned Evolution revision |
| `registry/mobile.json` | Mobile component registry exported from a JAR by `scripts/export-registry.py`. It maps Evolution libraries and file IDs to Mobile component types. |

## Statuses

`Matches Evolution`, `Explained difference`, `Unexplained difference`, `Not compared`, `Missing`, and `Out of scope`. The `⚠ Needs recheck` warning indicates that a file belonging to the component changed after the Mobile revision in the evidence. The verified status remains until a new run. Verified coverage equals (matches + explained differences) / (total − out of scope).

## Record evidence

1. Run the comparison or scenario and save the record in the repository. Use `docs/parity-evidence/` for oracles and `docs/validation/` for native scenarios.
2. Add the evidence under `evidence` in `matrix.json`. Include `kind` (`oracle` or `scenario`), `title`, `platform`, `mobile_revision` (the full revision used for the run), `command`, and `record`. An oracle also records `evolution_revision` and `evolution_jar_sha256` when available.
3. Under `rows`, use the inventory key to record `mobile` (the implementation), `evidence`, and `result` (`igual`, `diferenca`, or `divergente`). Supply `difference` for results other than `igual` and list the component's own files under `watch`. A later change to those files flags the row for rechecking.
4. Regenerate and verify the matrix.

```sh
python3 scripts/parity_matrix.py
python3 scripts/parity_matrix.py --check
python3 -m unittest scripts/tests/test_parity_matrix.py
```

A row without an entry in `rows` is `Missing`. A row with `mobile` but without evidence is `Not compared`. Evolution behaviors without their own inventory entries go in `extra_rows`. Record the maintainer's scope decisions and reasons in `out_of_scope`.

## Libraries and temporal sweep

Library entries use the sweep in `scripts/oracle/temporal-kinds/run.sh`. The [temporal sweep receipt](../docs/parity-evidence/temporal-kinds/receipt.json) records the Evolution JAR and each suite's command, Mobile revision, and SHA-256 hashes for the core JAR and TSV. Each TSV row has an ID in the form `<lowercase-kind>[__attribute_value...]` and a result for each Mobile mode. Results other than `SAME` require an entry under `differences` in `matrix.json`.

- `excluded` means the comparison is infeasible because Evolution fails or the fixture is invalid. The row is excluded from the count.
- `accepted` means the difference is documented and accepted, so the status is `Explained difference`. This includes components Mobile simulates that Evolution intentionally leaves unsimulated, such as Reptar Local Bus and a VHDL entity without QuestaSim.
- `open` means the cause is known and acceptance is pending, so the status is `Unexplained difference`.

Watch the files that own the component type's registry definition, excluding `ComponentDefinition`. The corpus and tests cover shared files such as the simulator and codec, so changes to those files require no per-row recheck.

To recheck the sweep, build the core with a clean `core/` tree, then rerun the sweep. This writes the TSVs and updates the receipt with the current revision.

```sh
until mkdir /tmp/logisim-build.lock 2>/dev/null; do sleep 20; done; ./gradlew :core:jvmJar; rmdir /tmp/logisim-build.lock
python3 scripts/parity_matrix.py reverify temporal-kinds --evolution-jar /path/to/logisim-evolution-5.1.0dev-all.jar
python3 scripts/parity_matrix.py
```

`--suites default,variants` limits which suites run. `roundtrip` requires `variants` in the same run or `--work` pointing to a previous run. The Evolution JAR must match the SHA-256 in the receipt.

## Mobile registry

When a definition, file ID, or definition owner changes, export the registry from the core JAR built for that revision, then regenerate the matrix. Use `--check` to compare against the snapshot without overwriting it.

```sh
python3 scripts/export-registry.py --core-jar core/build/libs/core-jvm.jar \
  --expected-sha256 <jar-sha256> --source-revision <source-commit> [--check]
```

Previous export receipts are in `docs/parity-evidence/mobile-registry-*.json`. The `scripts/tests/test_desktop_registry_ids.py` test checks all 205 inventory entries against the Evolution checkout specified by `LOGISIM_DESKTOP_SOURCE`.
