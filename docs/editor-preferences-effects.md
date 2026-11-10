# Gate shapes and hardware keyboard preferences

The gate-shape preference selects ANSI or IEC drawing for AND, NAND, OR, NOR, XOR,
XNOR, buffer, inverter, controlled buffer, and controlled inverter components.
IEC uses rectangular bodies with `&`, `≥1`, `=1`, `2k+1`, or `1`, input/output inversion
bubbles, and the existing control terminals. Facing and terminal order come from
the component registry. Selecting a style does not edit components, wires, or XML.

`CircuitDrawingStyle.gateShape` is used by the canvas and the native raster/print
export source. Both call the shared circuit drawing function. IEC primitives use
the same world text renderer as annotations and custom symbols.

The platform shortcut scheme uses Control on Android and Meta on iOS. The Control
scheme uses Control on both platforms. Copy, cut, paste and fit selection retain
Shift variants; Duplicate does not, because Evolution assigns Ctrl+Shift+D to
Move circuit down. Canvas commands also retain
Escape, Delete, Backspace, and the separate arrow-key movement/panning path.
Preferences remain in the existing versioned preference store.

Source checks performed for this change: common-source portability and formatting,
`git diff --check`, localization generation/UI checks, and the ten Python
localization tests. Kotlin tests cover gate primitives, facing, inverted inputs,
control terminals, XML invariance, modifier combinations, and command dispatch.
The integrator runs those Kotlin tests and the Android/iOS build gates.
