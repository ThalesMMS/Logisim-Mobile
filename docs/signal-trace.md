# Record signals

The signal recording panel uses the component identity and instance path. Two instances of the same subcircuit appear as distinct signals.

1. Open **Signal recording** and choose **Select signals**.
2. Choose components from the list or filter by label and path. For each selected signal, choose a radix for the table, timeline values, and files.
3. Advance the simulation. The panel captures the values supplied by the controller and shows the time unit.
4. Use **Pause capture** to inspect the history without adding samples.
5. Switch between **Timeline** and **Table**. Use the time controls to zoom or move through the period.
6. Tap the timeline to place cursor A. Press and hold to place cursor B. The distance between the cursors appears below.
7. Use **Export CSV** and copy the result.

The controller supplies a sample after every settled logical timestamp in the engine, including intermediate transitions caused by component delays. The trace uses `SimulationState.propagationTime` in `events`. The steps have no assigned physical duration. The CSV header declares `time_events`.

To write to a file, choose **Record TSV file**. **Logisim 2.7.1 format (changes only)** writes only the selected signal columns. After the first available sample, the recorder writes a row whenever a selected value changes. An enabled header contains labels and instance paths. In the 2.7.1 binary format, `x` indicates an unknown bit, `E` indicates an error, and an empty cell indicates a signal that could not be read. Turning off the option includes the `time_events` column in the TSV.

The trace retains up to 10,000 samples and 256 signals. The oldest samples are discarded when capacity is reached. A removed component appears as `<missing>` in CSV. Changing the selection or project clears the history. Clear the trace before capturing again after moving the clock backward.

The factory keeps the trace outside the panel composition, including across event updates. The host calls `EditorTool.onSimulation` even when the panel is closed and supplies `EditorToolContext.projectSimulation` to resolve all instances. Navigating to a subcircuit definition preserves the trace source.

`SignalTraceTest` covers capture in the actual AND circuit, pause and reset, instance paths, unknown and error values, export, and retention after 100,000 samples. The `traceEditorTool()` factory integrates the panel into the menu.
