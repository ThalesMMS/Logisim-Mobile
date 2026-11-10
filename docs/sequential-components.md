# Sequential components

`SequentialLibrary.definitions` registers the eight sequential devices in the `Memory` library. The components share a registry for `.circ` identification, attributes, terminals, and behavior.

| File name | Mobile identifier | Width | Trigger |
| --- | --- | --- | --- |
| D Flip-Flop | D_FLIP_FLOP | 1 | rising, falling, high, low |
| T Flip-Flop | T_FLIP_FLOP | 1 | rising, falling |
| J-K Flip-Flop | JK_FLIP_FLOP | 1 | rising, falling |
| S-R Flip-Flop | SR_FLIP_FLOP | 1 | rising, falling, high, low |
| Register | REGISTER | 1 to 64 | rising, falling, high, low |
| Counter | COUNTER | 1 to 64 | rising, falling |
| Shift Register | SHIFT_REGISTER | 1 to 64 per stage | rising, falling |
| Random | RANDOM | 1 to 64 | rising, falling |

The `classic` and `logisim_evolution` appearances preserve desktop terminal positions. These symbols have fixed geometry. `Shift Register` accepts 1 to 64 stages and uses `parallel` to add or remove parallel ports.

## State and controls

Each instance maintains its own state. Transient state is excluded from the saved circuit. Resetting the simulation recreates it.

In flip-flops, clear takes priority over preset. Capture replaces the stored bit only when the next bit is defined. Setting S and R high simultaneously therefore holds the state, matching the current desktop implementation.

Register captures input bits, including unknown and error values. Low Enable blocks capture. A disconnected Enable allows it. Counter prioritizes clear, then load. Counting depends on enable and direction. `ongoal` accepts `wrap`, `stay`, `continue`, and `load`. The `max` limit is constrained by the counter width.

Shift Register prioritizes clear, then parallel load at a clock event, then shifting. The order of parallel terminals matches the desktop appearance. The serial output exposes the oldest stage.

The engine can set `memoryStartupUnknown=true` for an undefined initial state. Counter operation without an edge follows desktop's unsigned integer read and converts that initial state to all ones. A capture from an undefined state returns an error and a diagnostic on mobile.

## Random sequences

Random uses xoshiro256++ with SplitMix64 initialization, matching desktop. Explicit seeds are 32-bit integers interpreted as unsigned. Reset restores the seeded sequence and blocks advancement while held high.

With `seed=0`, `options["randomSeed"]` makes a session reproducible. Without that option, the modern profile uses `elapsedTimeMillis` and the Logisim 2.7.1 profile uses the system clock to seed its 48-bit congruential generator. The legacy profile chooses a seed at startup and when reset is activated, then holds the value while reset is active.

## Validation

Common tests check flip-flop tables, priorities, level latches, enable, bounds, stage order, widths from 1 to 64, and the reference random sequence. Integration tests use the codec and simulator to verify simultaneous capture, hierarchy, and reset.

Run the desktop comparison for these components.

```sh
./gradlew :core:jvmJar
scripts/oracle/sequential/run.sh /path/to/logisim-evolution-all.jar
```

The runner compares outputs after every propagation for all eight types, applicable triggers, widths 1, 8, 32, and 64, and both initial-state modes. The stimulus sequence uses a fixed seed. Whole-circuit timing has a separate oracle.

The implementation validation ran 154 JVM core tests with no failures, including 31 tests specific to this family and its integration. The oracle performed 104,000 output comparisons with the desktop JAR. It uses an in-memory preferences store, preserving the installed app's preferences.
