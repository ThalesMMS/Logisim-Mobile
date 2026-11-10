# Shared circuit drawing

The interactive canvas and PNG/PDF export use `DrawScope.drawCircuitDrawing` in world coordinates. Viewport transforms and grid stay in CircuitCanvas; selection, drag previews and a pending wire are explicit optional drawing decorations. Export passes no editor decoration.

`RenderedCircuitExportSource` captures immutable context snapshots and creates request-local text and image caches. Its source methods run on background workers. `circuitVisualExportEditorTool(registry, assets, CircuitDrawingStyle(...))` captures font resolution, density, layout direction and signal display settings from the host. Native delivery uses the existing binary asset bridge.

Measured bounds include annotation fonts, custom symbol text, external component labels, terminal dots and wire strokes. Classic labels are represented by the same VisualPrimitive.Text for drawing and measurement. Junctions use an interval index instead of a quadratic scan. The source exports exactly selected components/wires, preserving document paint order. SVG is offered only for a complete scene of wires, annotations and supported custom symbols; other components remain available in PNG and printable PDF through the same canvas renderer.

The host passes `childCircuit = state.project::circuit` to CircuitCanvas so it can resolve active custom appearances. AdditionalEditorTools registers `circuitVisualExportEditorTool` and forwards the current high-contrast/radix preferences. Native image selection is provided by `assets.annotationImagePicker()`.

Geometry tests cover T-junctions, crossing/elbow behavior, negative and large sets of wires, explicit selection filtering, font/label anchors and shared wire palettes. Real canvas/file-picker/export/print behavior remains an integration runtime check.
