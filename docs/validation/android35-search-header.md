# Reveal the earlier Android Search header

This is source preparation for the four original Android editing groups. The preceding R2 used harness `1bb15545` on `emulator-5558`. Creation and properties passed in 249.87 seconds, including six Copy XML checkpoints for components, North, Undo, Redo and restoration. The wire group then failed while searching E2E_NOT after starting a connection at E2E_IN's output. The selection/history and viewport groups did not run. R2 remains failed. It used a different emulator from R1, so it is not an isolated comparison of the Inspector change.

## Observed premise

The old Search resolver searched forward first, then backward with a long list swipe. The common `AccessibleCircuitExplorer` declares Search before commands, automatic values, notices, components and expanded terminals. Looking farther forward does not reveal that earlier header.

The retained R2 observations are sufficient to choose a different direction without changing the product:

| Observation | Retained value |
| --- | --- |
| SDK49 Search | `[64,643][1016,811]` |
| SDK49 list viewport | `[64,689][1016,2273]` |
| Search clipping | Its top starts 46 pixels above the viewport |
| SDK50 Search | Absent from the tree; the connection-start notice remains |
| Later swipe requests | Three 2035→1085 forward swipes, then one 1085→2035 backward swipe, each 950 pixels and 250 ms |
| Terminal tree | Canvas/toolbars present; Explorer absent |

These are sequential observations and command requests. They do not identify which event dismissed the sheet or establish callback delivery. The terminal error was `The circuit search viewport disappeared`.

## Preparation

Only the private Search resolver changes. If its field is already completely visible, it enters the requested query without navigation. Otherwise it moves backward by at most 96 screen pixels and one quarter of the intersection between the observed list viewport and window. The same 18-poll maximum and stable-content stop remain. A field observed below its viewport fails conservatively instead of using a different reveal route. Losing the list stops immediately, without scrolling the background library.

Every poll retains its XML, viewport and Search rectangles. Each navigation record retains requested points, duration, monotonic time and whether the command returned. This distinguishes observation from delivery and will allow the next run to be checked without reconstructing discarded CLI trees.

The text input and keyboard policy remain unchanged. An AST comparison confirms the other 30 `_Editing` methods, including generic swipe/reveal, insertion, connection and the four group bodies, are identical to `1bb15545`. The document contract, its oracles, fixtures, app, APKs and global timeouts do not change.

Thirty-three offline selector checks pass. Restoring the old `1bb15545` Search method makes the focused R2 geometry check fail. The new source remains `NOT_RUN` natively. This preparation does not qualify wiring, cancellation, selection or issue #35 as complete.

```sh
python3 -B -m unittest discover -s scripts -p test_android_editing_scenarios.py -v
```
