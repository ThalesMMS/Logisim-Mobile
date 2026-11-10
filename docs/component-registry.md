# Component definitions

`ComponentRegistry` is an immutable catalog of component identities, attributes, terminals, and behavior. Register a family by composing its definitions with the catalog used by the codec, editor, network builder, and simulator.

```kotlin
val registry = ComponentCatalog.registry.withDefinitions(MyFamily.definitions())
val result = CircCodec.readProject(xml, registry)
val simulator = CircuitSimulator(registry = registry)
val editor = CircuitEditor(document, registry)
```

`withAdditional` rejects duplicate kinds. `withDefinitions` deliberately replaces definitions with matching kinds and retains unrelated components. The constructor rejects duplicate kinds and file resolution rejects ambiguous matches.

## Identity and file format

`ComponentKind` is a nominal identifier. The existing constants and `entries` remain available, and a family can declare another kind without changing a central enum.

`ComponentDefinition` maps a kind to a library and file name. `matchesFileAttributes` distinguishes file identities that share a name, including input and output `Pin`. Import and export use the same mapping. An unregistered kind produces an error instead of acquiring fallback gate terminals.

## Typed attributes

`AttributeKey<T>` supplies a default, parser, formatter, and validator. `AttributeSchema.parse` validates known values and returns `AttributeValues`; use `values[key]` to obtain a typed value. `values.raw` retains unrecognized fields.

The integer, long, double, Boolean, choice, text, and direction factories cover common attributes. Each definition owns its schema. Defaults can differ between definitions even when their attribute names match.

Use `registry.reconfigure(component, updates)` for attribute edits. A definition can normalize the complete attribute map with `reconfigureAttributes` before schema and layout validation. Wiring uses this callback to sign extend a Constant when its width grows and to remap Splitter branches when its dimensions change. Unknown attributes remain in the resulting map.

The codec combines file-version defaults with definition defaults in `implicitAttributes`. The writer omits unchanged implicit values, preserving the original file choices. New and old file identities use `defaultsForVersion` without changing the source map.

## One terminal layout

`ComponentLayout` contains bounds and an ordered list of `ComponentTerminal`. Coordinates are absolute circuit coordinates. Each terminal has a stable name, direction, width, and label. Width zero requests network inference.

The public `layout`, `inputPorts`, `outputPorts`, `portWidths`, and `bounds` functions accept the same registry. Their default is `ComponentCatalog.registry`. A caller that supplies another catalog must pass it consistently to every consumer, including drawing and hit testing.

Layout functions own orientation. Memory symbols retain their desktop geometry even when an irrelevant `facing` field exists. The registry does not apply another rotation to a resolved layout.

Attribute edits reconnect terminals by name. Removing a connected terminal rejects the edit before changing the document. `CircuitEditor.updateComponents` validates a group on a temporary editor and publishes one history entry only when every requested change succeeds.

## Instance state and event evaluation

Definitions hold behavior, while `SimulationState.componentStates` holds state for one instance. Two instances of the same subcircuit do not share component state. The state map is transient and is not written to a `.circ` document.

Behaviors that explicitly set `usesPeerStates` can coordinate registered states within that instance using `peers`, `peerStates`, and `peerStateUpdates`. Each such evaluation receives a fresh state snapshot. Other components do not copy or receive the circuit state map. Updates become visible to the next component in document order, before the current component's own result state is assigned. Updates cannot address another instance or a component without registered state.

When a transaction changes another component, the simulator revisits evaluation at the same propagation time before declaring the circuit settled. This lets a slave evaluated earlier update its outputs. External events are not delivered again during that pass. A bus master must retain its clock or transaction state so that another pass does not repeat the transaction.

`ComponentBehavior.evaluate` receives named inputs, connected input names, previous state, logical time, caller-supplied elapsed time, project options, and external events. An external event is delivered once per transaction. The result supplies named outputs, new state, delays, diagnostics, and an optional next wake-up time.

Output-specific delays override the shared delay. Wall-clock components use `elapsedTimeMillis` and `nextWakeupMillis`; they must not treat logical propagation time as real time. `resetState` can retain nonvolatile memory while clearing volatile state.

`isCombinational` is explicit and defaults to false. Analysis tools can reject clocks, storage, and interactive state instead of silently analyzing their initial values.

## Electrical topology

`CircuitNetworks` consumes the same terminal layouts. It infers zero-width terminals from connected fixed widths and applies weak pull values only to bits left undriven by strong outputs.

`bitNetwork(point, bit)` and `bitNetworks()` expose equivalence classes after wire, tunnel, and splitter elaboration. Identifiers are stable within that topology and have no meaning across different network instances. Analysis and HDL generation can reuse these classes instead of implementing another junction algorithm.

## Migration and validation

The existing geometry remains in an internal adapter while component families supply their own definitions. This preserves the original twelve types and the SAP-1 path during migration. It does not claim that every component family has been implemented.

`ComponentRegistryTest` exercises a new nominal kind through import, export, typed attributes, rotation, reconnection, group history, and weak pull resolution. `RegisteredSimulatorTest` exercises stateless and stateful evaluation through the actual event simulator. The complete core suite also retains the SAP-1 integration test.
