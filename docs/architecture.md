# Port architecture

The project model, codec, and simulator live in `core/commonMain` and run independently of Compose, Swing, and platform APIs. The editor in `composeApp/commonMain` draws the document and converts interactions to core operations. The Android and iOS hosts initialize the UI and implement file storage.

## Project and components

`CircuitProject` groups the circuits and identifies the main circuit. Each `CircuitDocument` stores components, wires, and metadata preserved from the `.circ` file. A component's identity is stable within its circuit. Subcircuit instances reference their circuit by name.

`CircuitComponent` stores position, label, type, and attributes. The catalog maps types to names and libraries in the desktop format. The codec, editor, and simulator share terminal geometry. Custom subcircuit appearances provide explicit terminals and connections to internal pins.

The catalog remains based on `ComponentKind`, with attributes stored in text maps. This implementation runs the SAP-1 subset. The extensible registry with typed attributes is tracked in [issue #4](https://github.com/ThalesMMS/logisim-mobile-dev/issues/4).

## Values and networks

`LogicVector` represents values from 1 to 64 bits. Separate masks store known, unknown, and error bits. Logic operations and driver resolution work per bit. The single-bit `LogicValue` API remains available to existing callers.

`CircuitNetworks` groups connected terminals and segments, joins tunnels by label, and connects bits distributed by splitters. Terminals and endpoints create junctions. A crossing between the interiors of two segments requires a junction to create a connection. Width mismatches produce diagnostics.

## Simulation and hierarchy

`CircuitSimulator.settle()` takes a document or project and returns `SimulationState`. The state contains values, sequential component memory, previous clock levels, diagnostics, and state for each subcircuit instance. Two instances of the same circuit maintain independent memory.

The engine elaborates the hierarchy and resolves connections between external and internal pins. It then schedules output changes with per-component delays. Changes at the same time apply before the next evaluation. A propagation limit stops circuits that fail to settle and returns a diagnostic.

`SimulationComponents` handles combinational behavior, including gates, controlled buffers, arithmetic, and ROM reads. The simulator handles register capture, counters, and flip-flops. Each call with `tick = true` toggles the external clock once. The UI controls repetition and pausing.

```kotlin
val project = CircuitProject(listOf(DemoCircuits.andGate()))
val simulator = CircuitSimulator()
val initial = simulator.settle(project)
val next = simulator.settle(project, initial, tick = true)
val xml = CircCodec.writeProject(project)
```

The desktop comparison covers SAP-1 and the sequence recorded in [validation.md](validation.md). Timing equivalence for other components requires separate evidence. RAM, Multiplexer, Decoder, and Bit Extender have identities in the model but the simulator reports them as unavailable.

## Editing and UI

`CircuitEditor` applies changes to the document. `EditorState` coordinates the project, open circuit, selection, history, clipboard, and simulation state. Structural edits use snapshots for undo and redo. Operating an input or advancing the clock affects simulation only.

`CircuitCanvas` controls framing, zoom, pan, gestures, and grid interaction. `ClassicRenderer` draws the components. `App` brings together the library, search, inspector, and simulation controls. On wide screens, the library and inspector sit alongside the canvas. On phones, they open in bottom sheets.

The SAP-1 preset loads the original project and pulses its `Reset` input. Generic import leaves the circuit inputs as provided. This lets the example run immediately while preserving the semantics of imported files.

## Files and recovery

`CircCodec.readProject()` imports the complete project and returns success or diagnostics. `writeProject()` writes the circuits and accepted metadata. The older `read()` API remains available for single-circuit documents and rejects projects with multiple circuits to prevent silent data loss. The reader must reject unsupported content before the open project is replaced.

`WorkspaceRepository` stores a checkpoint with version, revision, length, and checksum. The platform adapter writes each file atomically. The repository keeps a valid previous copy before replacing it. The reader can recover that copy if the current checkpoint is invalid. Migration preserves the initial version's original preferences.

This mechanism stores the open project. A catalog of multiple documents, native file pickers, and document providers require separate mechanisms. The saved file excludes transient simulation state.

## Decisions retained from the first version

The baseline `ed7fdca086919c9c1b011928d1652273c3865a8c` used an immutable document with explicit simulation state and snapshot-based history. This separation remains. It allows tests to reproduce scenarios and the JVM and Kotlin/Native targets to share the core.

The initial engine settled single-bit values without delays. The SAP-1 expansion added multiple projects, multibit signals, hierarchy, and events. The desktop source remains in a separate repository and serves as a reference and oracle. The mobile app runs independently.
