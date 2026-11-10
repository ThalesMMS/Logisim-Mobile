# Use the editor and run SAP-1

This guide covers the editor controls. The [parity matrix](parity.md) shows partial and missing features, and the [validation record](validation.md) identifies the platforms and scenarios tested.

## Open an example

Choose **File → Open example: AND** to test a simple gate or **File → Open example: SAP-1** to load the example computer. In a wide window, the explorer on the left shows the project's circuits. On a phone, tap the explorer button to open the same panel. Select `main` to return to the main circuit.

Loading an example replaces the open project. Export a copy through **File → Import or export .circ XML…** before switching projects. You can also undo the replacement while the session is open.

## View the circuit

Tap the zoom percentage in the status bar to see or set the zoom level, from 5% to 1000%. Use **View → Fit** to fit the drawing to the available space. Use two fingers to zoom in, zoom out, and pan. You can also navigate with a mouse or trackpad. Zooming in makes it easier to select terminals in a large circuit.

On wide screens, the explorer and properties sit beside the drawing. The first button in the header hides that panel to give the circuit more space. On phones, use the explorer button and the **Properties** button on the toolbar to open those panels. When the tools exceed the available width, tap the expand button (⌄) to see the rest in additional rows.

## Run SAP-1

1. Choose **File → Open example: SAP-1** and select `main`.
2. Tap **Step** to toggle the clock once. **Run** keeps toggling it, and **Pause** lets you inspect the state. The built-in example applies the initial reset pulse when it loads.
3. Watch the output register and the two hexadecimal displays. The included program calculates `2 + 3 + 4 - 1`, with an expected result of `08`.

The `Valor` bus can be undriven between transfers. Read the final result from the output register. The **Reset** (⟲) button on the toolbar resets the simulation state and repeats the initial pulse for an example just loaded from the library. If you reopen the saved project or import SAP-1 using **Import or export .circ XML…**, toggle the `Reset` input from `0` to `1` and back to `0` in the **Signals** panel before running it.

The [example reference](../examples/SAP-1.md) lists the exact source of the file, the position of the output register, and the test procedure. Open a subcircuit to see its implementation. Its instance in the main circuit controls its input pins.

## Edit components and wires

Choose a component in the library and tap the grid to insert it. Select **Move** to drag a component. Select the component and use **Properties** to edit its label, width, or direction. Check nearby connections after changing the geometry.

To connect two points, choose **Wire**, tap the start point, and then tap the destination. Tap the start point again to cancel. Use **Undo** and **Redo** to review the edit. **Copy**, **Duplicate**, **Paste**, and **Delete** appear on the toolbar when they apply to the selection.

In **Simulate** mode, tap an input pin to toggle its value. This changes the simulation state outside the drawing's edit history.

## Import, export, and recover

The **.circ** button opens the project's XML. **Copy** puts the text on the clipboard. To import a project, paste the contents of a `.circ` file and choose **Import**. If the reader rejects the file, the message explains why and the open project should be preserved.

The app saves local changes through the platform adapter. Export important files to keep an independent copy. Issues [#25](https://github.com/ThalesMMS/logisim-mobile-dev/issues/25) and [#26](https://github.com/ThalesMMS/logisim-mobile-dev/issues/26) track native document pickers, sharing, and file providers.

## Interpret the simulation state

Wires and components show the values the simulator produces. Treat unknown or conflicting values as distinct from `0`. If the app reports that the simulation failed to settle, pause and examine the feedback connections and inputs involved.

The SAP-1 run verifies that project and its execution path. Consult the parity matrix for desktop libraries, timing options, physical-device coverage, and any other component or integration you plan to use.
