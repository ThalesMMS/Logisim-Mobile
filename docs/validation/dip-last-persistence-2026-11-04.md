# DIP last-position persistence

The editor previously kept a DIP switch's changed positions only in `SimulationState`. With `resetbehavior=last`, export still contained the old `positions` attribute and reopening lost the interaction. The editor revision also stayed unchanged, so the workspace autosave effect did not receive the new saved value.

The desktop source at `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01` establishes this contract:

- `DipSwitch.Poker.mousePressed`, lines 70–75, changes `ATTR_POSITIONS` only for the `last` reset mode.
- `DipSwitch.initialValue`, lines 172–175, reads the saved positions masked to the switch count on a fresh load or reset.
- `XmlWriter.addAttributeSetContent`, lines 227–244, writes non-default saved attributes.

This was a source review, not a new desktop execution. The poke path does not register a schematic undo action. No claim is made here about the desktop dirty indicator.

The mobile editor now saves positions after an accepted simulation result containing a DIP interaction. It reads the state of the instance that received the event, updates the circuit definition, and advances the document revision only when the serialized positions change. It does not scan all instance states on clock ticks. Button presses and the DIP `open` and `closed` reset modes remain transient.

A private versioned interaction record accompanies editor snapshots. It preserves a later input interaction when undoing an independent schematic edit, keeps explicit attribute edits' own Undo/Redo, and separates project replacement histories. The interaction itself does not add an undo action. Rejected results and preserved read-only documents do not change saved content.

## Focused verification

Base: `0ce9ba7cc2838fdde944d62c54e1b22deb00a23a`.

The first six tests reproduced five failures against the existing compiled editor. After the fix, the complete `EditorState.kt` and the test sources were compiled with Kotlin 2.4.21/JVM 21 and run with JUnit 4.13.2, without Gradle. All 18 tests passed:

- Nine new `EditorDipPersistenceTest` cases cover XML reopen, revision changes, transient stimuli, independent schematic Undo/Redo, explicit attribute Undo/Redo, delayed result acceptance, stale/read-only rejection, queued stimuli, project replacement, and two subcircuit instances sharing one saved definition.
- The existing `EditorStateTest`, `EditorInteractionGateTest`, and `EditorSimulationQueueTest` contributed nine passing regression cases.

The two-instance case keeps separate runtime states and saves the last interacted instance's positions. Subsequent clock ticks do not overwrite that saved value with another instance's state.

Local receipts are retained in `/tmp/logisim-dip-last-persistence/`, including source hashes and immutable classpath snapshots. Logs are `/tmp/logisim-dip-last-before.log` and `/tmp/logisim-dip-last-after.log`; the reproducer is `/tmp/verify-dip-last-persistence.py`. The original six-test source was retained and its SHA-256 checked against the before-run receipt.

The integrated Gradle/Native gate and the Android/iOS UI sequences remain separate validation. No native UI was exercised by this change.
