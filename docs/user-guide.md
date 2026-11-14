# Use Logisim Evolution Mobile

Open **Tools → Help and examples** to read the guides and browse examples offline. The **Compatibility** tab explains the port's limits and points to the [parity matrix](parity.md). The matrix links each feature to its tracking issues and available evidence.

## Start with an example

In the **Examples** tab, choose a circuit and read its inputs and expected result. **Open a copy** asks for confirmation and opens the example separately from the original external file. The previous project stays in the workspace. Save a copy of important work before switching projects.

The reference scenario test runs an independent copy of the example. It leaves the open circuit intact and checks the example's original behavior. The [example catalog](../examples/README.md) describes the stimuli used in the tests.

## Edit the drawing

1. Choose a component from the toolbar (pins and gates, as in Evolution) or the **Library** tab in the explorer and tap the grid to insert it.
2. Use **Select** to pick a component or drag an area that includes components and wires. Use **Move** to drag the selection.
3. Open **Properties** to edit the available attributes. The panel reports invalid values. An accepted change enters history as one edit. **Undo** and **Redo** move through that history.
4. Use **Copy**, **Cut**, **Duplicate**, **Paste**, or **Delete** in the **Edit** menu.

On wide screens, the explorer and inspector sit alongside the drawing. The first button in the header hides the panel. On a phone, the explorer button and the toolbar's **Properties** button open panels. Close the panel to return to the canvas.

In **Editor preferences**, high-contrast signals add short dashes to wires with unknown values and long dashes to wires with errors.

## Connect terminals

Choose **Wire**. Tap the starting terminal and then the destination to create an orthogonal connection. Tap the start again to cancel the pending wire. Zoom in to distinguish nearby terminals.

With keyboard focus on a canvas component, press `Shift` + `Enter` to open the terminal list. Use the list to start or finish a connection. The screen reader offers **Terminals of …** when a component has many overlapping terminals.

Check terminals, junctions, and network values to confirm whether crossing wires connect. The editor may reject a property change that would remove a connected terminal to preserve its connections. Read the message and review the wires before trying again.

## Navigate and fit the view

**View → Fit**, or tapping the zoom percentage in the status bar, frames the whole circuit, including wires. **Fit selection** frames selected items. Use two fingers to zoom and pan. On a keyboard, `+` and `-` change zoom and `0` fits the circuit when the canvas has focus.

In the **Circuits** panel, select a definition or instance to navigate the project. The definition describes the circuit, and each instance maintains its own simulation state. An instance's inputs are driven by its parent circuit's connections.

## File and simulation shortcuts

Use `Ctrl` on Android and `⌘` on iOS. The **Shortcut key** preference lets you use `Ctrl` on both platforms. Shortcuts work on the canvas and in editor controls outside text fields. Close modal panels to return to the editor before using them.

| Key with `Ctrl` or `⌘` | Action |
| --- | --- |
| `N` | Create a project |
| `O` | Open a copy through the file picker |
| `S` | Save to the linked file or choose a destination |
| `Shift+S` | Save to another file |
| `E` | Run or pause simulation |
| `I` | Advance one propagation event |
| `T` | Advance one clock tick |
| `K` | Toggle automatic ticks |
| `R` | Reset simulation |
| `Shift+E` | Open drawing export |
| `P` | Open drawing export for **Print PDF** |
| `Shift+W` | Close the project and return to the project list |
| `Shift+U`, `Shift+D` | Move the current circuit up or down |
| `1`–`9`, `0` | Select toolbar tool 1–10, excluding the touch-only Move tool |

Tools 11–14 start without shortcuts, as in Evolution. Press `Shift` twice to open **Find Action…**. Its toggle is in preferences alongside **Shortcut key**.

## Tool, gate, and Auto Label shortcuts

With canvas focus, press these keys without `Ctrl` or `⌘`.

| Key | Action |
| --- | --- |
| `S` or `N`, `M`, `W` | Narrow, medium, or wide gate |
| `=` or `+`, `-` | Add or remove a gate input, with a minimum of 2 |
| `R` | Rotate the tool before placing the component |
| `T`, `V`, `H` | Toggle, show, or hide the label |
| `A` | End Auto Label numbering (`L` opens Auto Label) |
| `Insert` | Duplicate the selection |

These keys apply to the placement tool and to the selection in the editing tool. When the key does not apply, `+` and `-` still adjust zoom. To remap shortcuts, open **Editor preferences → Configure command shortcuts**. Leave a field blank to clear a command's shortcut.

## Simulate and inspect

The simulation controls use Evolution icons. **Run** (▶) starts continuous execution and **Pause** stops it. **Event** advances one propagation event, **Step** advances one clock tick, the clock enables automatic ticks, and ⟲ resets simulation. The **Simulate** menu repeats these commands and lets you choose a frequency. **Simulate → Simulation options…** adjusts the propagation limit and available project policies.

In **Simulate**, tap an input to change its value. Open **Signals** with the waveform button in the status bar to edit inputs and observe outputs. For multibit inputs, enter a number using the width and format indicated by the field. Input changes affect simulation only.

To run SAP-1 opened from Help or from a file, select `main` and, in **Signals**, toggle `Reset` to `1` and back to `0`. Advance 56 steps or use **Run**. The output register should show `08`, the result of `2 + 3 + 4 - 1`. **File → Open example: SAP-1** applies this initial pulse when loading. The [SAP-1 reference](../examples/SAP-1.md) describes the program and its origin.

## Find errors

Pause execution and open **Tools → Diagnostics and statistics**. Filters separate diagnostic types. Tap an entry to navigate to the related components or wires. **Circuit statistics** shows counts for the project and selected circuit.

An unknown or error bit needs investigation even when you expect `0`. Check undriven inputs, width mismatches, conflicting drivers, and feedback. An execution limit warning means simulation exceeded its budget. Check the diagnostic before increasing the limit.

## Save, share, and recover

Open **File → Import a copy** to select a `.circ` while keeping the original intact. **Save as** exports the project to the selected destination. **Share** opens the system share sheet with a copy. Whether another app receives it depends on the action selected in the share sheet.

On iOS and iPadOS, **Edit external file** keeps a link to the selected document. **Save to file** checks whether the external revision has changed. If there is a conflict, preserve the local edit with **Save as** or reopen the file to review the external version. On Android, the adapter uses **Save as** to preserve the original.

Cancelling a picker leaves the open project intact. If provider access fails or the file moves, read the message and choose the destination again. Local saves allow recovery of the session's project. Keep an exported copy of important work as well.

The **.circ** button offers XML import and copy. An import rejected by the reader preserves the open project. See the platform guides for [Android documents](android-documents.md) and [iOS and iPadOS documents](ios-documents.md).
