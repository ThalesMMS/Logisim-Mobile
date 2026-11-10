# Portable project UI qualification

This fixture prepares the acceptance proof for issue #53. It does not record a native UI pass. `ui-receipt.template.json` deliberately starts with `status=NOT_RUN` and `executedViaUi=false`.

The reference source graph is:

```text
main.circ / Top53
  FIRST53  ─┐
             ├─ library/bridge.circ / Bridge53
  SECOND53 ─┘      └─ library/cell.circ / Cell53
```

Top53 has two visible Bridge53 instances. Each forwards a separate 8-bit constant through its own Cell53 instance. Expected output pins are `OUT_FIRST53=00001000` and `OUT_SECOND53=00000011`. There are three referenced definitions, eleven components, seven wires, and four runtime child instances.

Each source project has a distinct `qualification-source` marker and main-element metadata. Component IDs are explicit. Circuit IDs from separately granted folder imports may vary by platform/source identity; compare them within one workspace across export, restart, and reexport. A fresh `WorkspaceProjectId` is required for every import copy.

`portable53.lsebdl` also contains `readme53.txt` and an unreferenced `library/unreferenced53.circ`. Preserve both byte-for-byte when exporting a bundle. A folder import captures its referenced `.circ` graph only, so use `--allow-no-opaque` for that mode.

## Prepare and verify the fixture

```sh
./scripts/prepare-portable53-fixture.py --check
./scripts/verify-portable53.py \
  --core-jar /path/to/existing/core-jvm.jar \
  --desktop-jar /path/to/logisim-evolution-all.jar \
  --artifact fixtures/portable-projects/portable53.lsebdl \
  --origin fixture --output /tmp/portable53-preparation.json
```

The verifier uses the cached Kotlin compiler, the existing mobile codecs and simulator, and the desktop loader/propagator. It invokes neither Gradle nor a device. A preparation report says `PREPARATION_ARTIFACT_VALIDATED`, `nativeUiStatus=NOT_RUN`; it cannot satisfy the three-platform UI criterion.

## Native scenario

Run both `bundle` and `folder` modes through the production **Tools → Portable projects** panel:

1. Capture the existing workspace directory and current UI. Keep the previous workspace ID and its native link, if any.
2. For `bundle`, select `portable53.lsebdl` using **Open .lsebdl bundle**. For `folder`, choose a dedicated parent containing `sources/` in the native folder picker, select `sources`, confirm the grant, and select `main.circ` in the panel. Android must not grant the protected Downloads root.
3. Wait for the new workspace to commit. Capture the canvas and workspace directory. Confirm two instances labelled FIRST53/SECOND53, a fresh project ID, no `external-<new ID>.workspace-link`, and a `portable-source` attachment.
4. Use **Export .lsebdl bundle**, complete the native save chooser, and copy the actual output file to the evidence directory. Record its path and SHA-256. Capture the native chooser and completion.
5. Terminate the app process completely. Relaunch the installed artifact and capture its restored workspace directory and UI. Do not import the fixture again. The same persistent project ID and source-archive descriptor/hash must survive.
6. Export again from the restored workspace. Comparing this actual file with the first export proves the retained source graph participates after the in-memory tool session has gone away.
7. Reopen the app-exported bundle through the same tool. Require another fresh workspace ID, the same definition/component identities, and a source sidecar whose bytes equal the selected exported file.

`documentIdentity` is an ephemeral editor counter and is not the persistent workspace ID. A blob file by itself is insufficient: creation writes blobs before committing the catalog, and blobs deduplicate across copies. Validate the active catalog/payload/descriptor/hash together. Window links take precedence on restore; record the actual scene/window binding too.

Android source helper `scripts/android_portable_scenarios.py` exposes `run_portable(scenarios)` for the existing AndroidUi/Scenarios driver. It supplies file-picker, export, process restart and committed storage checks. Its folder case requires an owner-supplied `confirm_native_directory()` based on observed native controls. It never invents confirmation coordinates or runs a device when imported. It correlates the actual window UUID with the pending native CreateDocument metadata, before and after restart.

The iOS runner should stage these files through `Run.fixtures` and capture exported URLs plus its sandbox snapshots. No simulator, target, or harness configuration is changed by this fixture package.

## Verify native evidence

Unpack the four native workspace snapshots into directories without modifying their contents. Android workspace data is under `files/workspace`; iOS uses `Library/Application Support/LogisimMobile/workspace`. Pass the directory that directly contains `catalog.workspace`:

```sh
./scripts/verify-portable53.py \
  --core-jar /path/to/existing/core-jvm.jar --desktop-jar /path/to/logisim-evolution-all.jar \
  --workspace-before /evidence/before/workspace \
  --workspace-imported /evidence/imported/workspace \
  --workspace-restarted /evidence/restarted/workspace \
  --workspace-reopened /evidence/reopened/workspace \
  --output /evidence/storage-proof.json
```

This uses the production catalog/attachment decoders and the common window-link parser. It checks that both imported and reopened IDs are absent from every preceding complete catalog and that all prior workspace IDs remain present, source-archive persistence and hash, transitive definitions, outputs, and absence of an inherited native reference. It never writes into evidence directories. A storage proof alone still does not certify UI execution.

After a native owner fills the receipt with actual UI evidence, export paths, installed artifact fingerprint and IDs, run the final desktop/mobile check against an **app-exported** file:

```sh
./scripts/verify-portable53.py \
  --core-jar /path/to/existing/core-jvm.jar --desktop-jar /path/to/logisim-evolution-all.jar \
  --artifact /evidence/export-after-restart.lsebdl --peer /evidence/export-before-restart.lsebdl \
  --origin app-export --ui-receipt /evidence/ui-receipt.json \
  --output /evidence/desktop-app-export-proof.json
```

Use `--allow-no-opaque` for folder mode. The wrapper refuses `--origin app-export` without a passed native receipt, both export paths and hashes listed exactly once in that same receipt with opposite `afterRestart` stages, the app artifact hash, and existing evidence files. The two files must be distinct, and neither may be a checked-in input fixture. Both actual Android and iOS exports need their own desktop proof. Do not replace either with the checked-in fixture's preparation result.
