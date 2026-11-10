# I/O component validation

`IoDemoCircuits.controls()` builds one project containing the twelve components covered by issue #44. It has named, observable paths for the host UI tests:

| Control | Expected observation |
| --- | --- |
| Press and hold `Button` | `LED` lights until release; cancellation releases it. |
| Toggle `DIP` switches | Only the selected switch output changes. The `last` mode publishes saved positions. |
| Move `Joystick` | The X and Y hex displays change; release centers both at `8` with the default 4-bit width. |
| Type text in `Keyboard` | Text enters the queue. Clock edges transfer characters into `TTY` while Read and Write are high. |
| Send ASCII backspace or form feed | `TTY` erases its last character or clears its display when the control reaches the input. |
| Toggle R, G, and B inputs | `RGB` mixes exactly those color channels. |
| Change `Segments` | The seven-segment display follows the bus bits, including its decimal point. |
| Change a matrix row input | `Dot matrix` changes the corresponding row; its initial pattern is an A. |
| Press a key in the top keypad row | Its column changes low in PMOD mode; release returns high. Other rows are not selected. |

The common tests verify the state transitions and named terminals. `IoGeometryOracleTest` compares 77 configurations with values produced by the desktop JAR. The randomized state oracle compares keyboard edits, TTY scrolling/resizing, and matrix values/persistence against the desktop implementations.

Android and iOS host validation must exercise focus, text entry, press/release, cancellation, and the displayed results after these files are integrated into the shared editor. Compilation alone does not complete that part of issue #44.
