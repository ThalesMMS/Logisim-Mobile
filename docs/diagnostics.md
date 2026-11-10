# Network diagnostics and statistics

`CircuitDiagnosticAnalyzer` classifies width mismatches, conflicts between known drivers, disconnected inputs, propagated error values, invalid references, recursion, and failure to settle. The report distinguishes electrical faults, document faults, and execution limits.

## Identity and source

Each diagnostic stores the circuit ID, instance path, component IDs, terminal names, and the points and wire segments involved. IDs use named references and canonical anchors independently of the internal network union index. Splitting a segment, normalizing wires, or reordering components preserves the identity of an equivalent network.

`CircuitTopology` uses the same electrical model through `CircuitNetworks.bitNetworks()`. The report keeps physical bundles and per-bit connections separate so a conflict through a splitter can be located without marking all bits as errors.

Conflict detection uses `SimulationState.componentOutputs` before electrical resolution. Weak pulls participate in the diagnostic only when the bit lacks a strong driver with a known or error value. A component output of `ERROR` is distinct from a conflict among drivers. Disconnected inputs are warnings. Some control terminals have default values.

When propagation fails to settle, the analyzer locates components in combinational feedback. It joins per-bit networks across subcircuit interfaces to find loops across instances, including children that contain only wires. Targets retain the full instance path. Without an established source, it reports an execution limit and leaves the cause open. Hierarchy analysis has an explicit limit of 4,096 instances per call and reports when results are partial.

For bitwise gates, the graph tracks dependencies per bit. Other families use conservative component-level dependencies. The selection identifies structural feedback candidates and leaves the exact oscillating bit undetermined.

## Integrate the panel

Add `diagnosticsEditorTool(registry)` to the tools supplied to `App`. Use the same registry as the simulator. The panel analyzes root state, distinguishes instances, and uses `EditorToolContext.navigate` to open and frame the source. Selection uses the same editor commands and leaves the project intact.

During automatic execution, a single producer polls the latest simulation state every 200 ms. Each sample must finish before another begins. Analysis uses this polling schedule independently of simulator ticks.

`DiagnosticTarget.editorLocation()` produces the destination expected by navigation. Convert import errors with `CircuitDiagnosticAnalyzer.importFailure(detail, sourceId)`. Import errors have no selectable electrical component in the preserved project. The host must keep the import message visible and may add it to the displayed report.

Statistics come from the current project. They count component types, circuits, expanded instances, segments, length, terminals, junctions, and networks. Unresolved layouts retain the component count and mark the topology metrics as partial. Cycles and count overflow leave totals undetermined.

## Verification

```sh
./gradlew :core:jvmTest --tests '*CircuitDiagnosticsTest' --tests '*CircuitStatisticsTest' --tests '*HierarchicalDiagnosticFeedbackTest' :composeApp:testAndroidHostTest --tests '*DiagnosticNavigationTest'
```

Open `examples/diagnostics/net-errors.circ` to check a partial driver conflict, a width mismatch, and a disconnected input. In the panel, select the source and verify the selection and framing. State tests cover navigation to a child instance, history preservation, and statistics updates after inserting, deleting, and undoing.

The panel on Android and iOS requires separate verification from the core and state tests. Host integration and that verification are pending in issues #19 and #30.
