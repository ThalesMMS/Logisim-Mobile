# Android creation and properties passed before a wiring failure

The selected Android editing invocation passed `editing-create-properties` in **249.87 seconds**, then failed `editing-wires-truth-table` while preparing Search after starting a connection. The wrapper returned exit 1 after **292.686911 seconds**. Selection/history and viewport/touch-parity groups did not run. Issue 35 remains incomplete.

The producer is `3a0a01f1af05dd3fea75d5a1499c762d039c478a`, helper `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`, harness `1bb155453a05acca8585d5da15c182c08bda41d0`, API 35 emulator `emulator-5558`, English UI. R1 used 5556 and R2 used 5558; this is not an isolated A/B comparison and does not assign the earlier failure's cause.

The first group completed six public Copy XML checkpoints, all checked by the frozen historical editor contract:

| Exports | Literal document result | SHA256 |
| --- | --- | --- |
| Empty | One empty circuit | `c9ae3bb10cf2ea2b72180e899ee0c18c991ae20f26684fad55756eaab78dd481` |
| Components, property Undo, property restored | Three components, NOT East, output West, no wires; complete bytes equal | `049e8daa354dd0ed76a918b6358845c46ec23fbc7082019e3926bb2380d4d9b9` |
| Property North, property Redo | Three components, NOT North, output West, no wires; complete bytes equal | `5acc9b6d2200c8b6084fd825f9037521cae4fc155ecf0917ef791af4ad06582e` |

`last-document.circ` equals the restored public export. The final private checkpoint 44 independently matches the components contract with no wires; its XML came from the failure collector's workspace TAR, not a later public Copy XML action. No complete connection or truth table is qualified by this run.

The primary stack is `wiring_and_truth_table` → `connect(E2E_IN,out,E2E_NOT,in)` → `search(destination)`, which reports `The circuit search viewport disappeared`. SDK captures 49 and 50 both retain the public text `Connection starts at E2E_IN, out. Choose a destination terminal.` The retained bounds show:

- Capture 49: Search contains `E2E_IN`, rectangle64,643–1016,811; its list viewport begins at 689, so the field extends 46px above it.
- Capture 50: the list viewport remains 64,689–1016,2273, but Search is absent from the tree.
- Terminal hierarchy: Canvas is present; Explorer sheet and list tags are absent.

The audit log contains three forward swipe requests from `(540,2035)` to `(540,1085)`, followed by one reverse request. Each requested distance is 950px and duration 250ms. These are requested commands and observed trees, not proof of gesture delivery, internal callback counts, or a causal explanation for dismissal.

All 50 SDK snapshots are complete, with PID 29923 before/after each query. This does not qualify SDK recovery or uninterrupted process continuity. Before/after raw getters, installed APK identities and source hashes match; settings differences are empty and the recorded last-ANR text is unchanged. The reader was off. Cleanup force-stopped only the owned isolated app and observed an empty app PID. The runner had performed its normal isolated reinstall/reset before this invocation.

The source-preparation record remains unchanged as historical `SOURCE_PREPARED` / `NOT_RUN`; the later execution has its own Native manifest, controller receipt and results. This packet retains 328 raw files and 74 controller files, both historical oracle inputs, ten source inputs, the preparation record and the final private-workspace member. **417 inventory entries total 5,848,299 bytes**. Both 106,042-byte original TARs remain local and unchanged, SHA256 `bd417d069a1ceb87aee8acec85b4e1605cb8042a595a92f4f249d828e51a2577`. Their references and the necessary 1,946-byte member are published; the TARs and APKs are not.

Run offline after copying this directory elsewhere:

```sh
python3 -B verifier.py
python3 -B -m unittest discover -s . -p test_verify.py -v
```

The verifier checks both oracle hashes before execution, all six public documents, the private checkpoint, metadata, source pre/post equality, raw getter readback, 50 SDK records, Search census, final tree, primary trace and swipe requests. Four semantic mutations recompute hashes before rejection; a relocation test needs neither Android nor Git. No new device, build or Native operation occurs during replay.
