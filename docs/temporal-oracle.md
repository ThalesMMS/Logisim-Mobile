# Temporal propagation oracle

`examples/corpus/temporal` contains six circuits, their external stimuli and the reviewed desktop transitions. All inputs, including simultaneous changes, pass through the normal Pin behavior. The common test requires registered evaluators for every component.

| Circuit | Behavior observed | Desktop transition time after the action |
| --- | --- | --- |
| `simultaneous-registers` | Two registers capture their pre-edge data together, including a data change concurrent with the clock | Both outputs at 9, from Pin 1 plus Register 8 |
| `derived-clock-feedback` | An inverter supplies the clock to a D flip-flop whose inverted output feeds its data input | Derived clock at 2, Q at 7, feedback at 8 |
| `ram-feedback` | A derived clock writes the inverted asynchronous RAM read value back into the same address | Derived clock at 2, memory at 12, feedback at 13 |
| `subcircuit-ripple` | An 8-bit ripple adder built from eight full-adder subcircuits, with every port connected by tunnels | Sum at 5 after setting A. Carry-out at 33 after the carry ripples, because each crossing of a subcircuit boundary adds one unit on input and one on output |
| `gate-input-tail` | An inverter input changes twice in one action, with the queue drained between the changes | The output falls at 2 and rises at 4. A gate is not propagated again when only its own output changes |
| `register-input-tail` | A register data change is drained before the clock rises in the same action | Q at 18. A data change without a clock edge still sets Q again after 8 units, so the drain ends at 9 |

Each sequential circuit also exercises clearing stored state. Priming operations drain the queue before the measured actions. Event times are relative to the scheduler time immediately before each action. They are simulation delay units, not milliseconds or device timing.

The desktop helper is compiled against revision `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, the parity matrix reference. It calls `Propagator.step(null)`, which applies all queued events at the next timestamp before evaluating components. Reflection reads the private scheduler clock; it does not change simulator state. Random delay noise is disabled. Pin writes mark their component dirty, as in the desktop test-vector evaluator.

Each record is `E`, action, relative event time, probe ID and binary value, separated by tabs. A snapshot contains every probe. Consecutive snapshots with identical values are omitted because the two engines may omit different redundant output events. Every observable transition and its timestamp must match exactly. This compares transitions through gates and stored state, without requiring identical internal queues.

Run the differential check with a locally built desktop JAR:

```sh
python3 scripts/verify-temporal-oracle.py \
  --desktop-jar /path/to/logisim-evolution-5.1.0dev-all.jar
```

The command compares desktop batches against both a full mobile settle and repeated `stepEvent` calls. It also checks the frozen desktop records and fixture/JAR hashes. `--update-reference` replaces the records and regenerates the common test data only after both mobile modes match the desktop. The normal check rejects stale embedded data. Review the changes and run the common tests:

```sh
python3 scripts/generate-temporal-fixtures.py
python3 scripts/generate-temporal-fixtures.py --check
./gradlew :core:jvmTest --tests '*TemporalOracleTest'
```

`TemporalOracleTest` is in `commonTest`; the same immutable corpus runs on Kotlin/Native with `:core:iosSimulatorArm64Test`. A JVM run proves desktop equality. A Native run proves that target reproduces the frozen desktop transitions. Neither proves physical hardware timing or touch interaction.
