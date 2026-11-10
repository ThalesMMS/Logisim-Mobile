# Project options and Base tools against Evolution (#331), Mobile a4e57de9

Mobile `a4e57de9eb3555fb5e6eed78ab6d5470e43ee229` (`origin/main`, no Kotlin changes in the worktree), core JAR `core/build/libs/core-jvm.jar` built from it with `./gradlew :core:jvmJar` (SHA-256 `0e596cb9d1a760eeb30d3f871adf5d009b3305f67f2cb5d3f82bef1205a500b9`). Evolution `d58a61e0`, JAR `logisim-evolution-5.1.0dev-all.jar` (SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`). macOS (Apple silicon), Homebrew OpenJDK 21.0.13, JVM only, 2026-11-07. Evolution ran headless with an in-memory `java.util.prefs` factory (`scripts/oracle/project-options/MemoryPreferencesFactory.java`), so the user's preferences were neither read nor written.

## Commands

```sh
./gradlew :core:jvmJar
scripts/oracle/project-options/run.sh EVOLUTION_JAR OUT   # exit 1 while the simlimit difference stands
scripts/oracle/base-tools/run.sh EVOLUTION_JAR OUT
```

`project-options/generate.py` writes Evolution 5.1.0dev fixtures that differ only in `<options>`. `run.sh` runs Evolution `-tty table` (or `table,halt`) and Mobile `HeadlessCircuitCli FILE --tty-evolution -tty table` on each one and compares stdout and exit code. It compares the halt reason separately, because Mobile writes it to stdout and Evolution writes it to stderr through SLF4J. Each process has a 60 s limit.

## Results: project options

| Row | Fixtures | Result |
| --- | --- | --- |
| `option/gateUndefined` | `gate-undefined-{ignore,error,default}`: AND/OR/XOR/NAND/NOT/Buffer/Controlled Buffer with open ports, floating stubs and driven inputs; 512 input rows each | SAME (3/3). `error` turns open ports and open buffer control into `E`; `ignore` drops them; `default` equals `ignore`. |
| `option/simlimit` | `simlimit-chain12-{2,11,12,13,14,15,1000,default}`: 12 buffers settled from a new state | SAME (8/8): both report the `E` row up to 13 and settle from 14. |
| `option/simlimit` | `simlimit-ring-halt-{3,4,5,6,1000}`: Clock into a NOR loop, `-tty table,halt` | Exit code 1 and `halted due to detected oscillation` in all 5. The last row differs in 4 of 5 (see below). |
| `option/simlimit` | `pulse-latch-halt-0`: a deterministic one-step pulse sets a NOR latch whose output is `halt` | DIFF: Evolution `halted due to detected oscillation`, exit 1; Mobile `halted due to halt pin`, exit 0. |
| `option/simrand` | `simrand-{network,chain12}-{0,32}`: order-independent circuits | SAME (4/4); the 5 Evolution runs at `simrand=32` produced the same table. Mobile warns `Random propagation delays are enabled (simrand=32)`. |
| `option/simrand` | `race/simrand-race-{0,32}`, 5 runs each | `0`: neither halts (Evolution killed after 10 s, Mobile stops at `--ticks 5000`). `32`: both halt at a random tick (Evolution 21–123, Mobile 8–87). Evolution ends on oscillation and Mobile on the halt pin, the same difference as `pulse-latch-halt-0`. |

### simlimit: Mobile applies one more step when it stops at the limit

At a limit `L`, Evolution's `Propagator.propagate` runs `L-1` steps and sets `isOscillating` on iteration `L` without running it. Mobile's `CircuitSimulator.settle` runs `for (round in 0 until propagationLimit)` and applies the next timestamp in every round, including the last. So it applies `L` steps before it reports the circuit as unsettled. When the circuit settles, the two limits match (chain fixtures). When it oscillates, the value frozen at the cutoff has the opposite parity. With `-tty`, the halt pin is read from that value, so Mobile halts on the pin where Evolution reports oscillation (`pulse-latch-halt-0`, the `simrand-race-32` runs). Mobile with limit `L-1` reproduces Evolution with limit `L`: Mobile 4 = Evolution 5 = `E 0 1`, Mobile 3 = Evolution 4 = `E 0`, and `pulse-latch` with Mobile `simlimit=999` exits 1 with oscillation.

Candidate fix (not applied): in the round loop of `CircuitSimulator.settle`, stop before applying `ready` events when `round == propagationLimit - 1`. Leave the state unsettled, then run the chain and ring fixtures above as a focused test.

Out of scope here, noted for `cli/tty`: under `--tty-evolution`, Mobile prints `halted due to …` on stdout, while Evolution logs it on stderr.

## Results: Base tools (model level)

- **PokeTool.** `PokeDesktopOracle` drives Evolution's `Pin.PinPoker.handleBitPress`, the path a Poke Tool click on a pin bit takes ([log](logs/base-tools/poke-desktop.tsv)). Mobile's poke on a pin is `EditorState.toggle`, read from source at `a4e57de9`; it has no host test yet. Only one-bit two-state pins match (`0→1→0`, as `EditorStateTest`). Three differences:
  - A tri-state one-bit pin goes `U→0→1→U` in Evolution and `U→1→0→1` in Mobile.
  - Multi-bit pins: Evolution changes the bit (radix 2) or digit (radix 8/16) under the pointer, with a carry inside that digit. Mobile adds 1 to the whole value. In Evolution `h8` goes `01, 02`, then a poke on digit 1 gives `12`, then `13`; in Mobile it goes `01, 02, 03, 04`.
  - On a tri-state bus (`t3`, `UUU`), Evolution gives `UU0, UU1, UUU, U0U`, while Mobile starts from 0 and counts `001, 010…`.

  Clock poke (`Simulator.tick(1)`, all clocks) matches `EditorState.tick()` for Evolution projects.
- **TextTool.** `TextToolOracle` applies the same edits in both programs: Evolution's `TextTool.editingStopped` actions, and the core calls that `EditorState.beginTextToolEdit`/`applyTextToolEdit` make. It reopens both results in Evolution and compares every attribute. The results match for a new text from the toolbar tool, a Pin label edit, a Text edit and an empty new text, which neither program adds. Two differences:
  1. Clearing an existing Text: Evolution's `editingStopped` builds `CircuitMutation.add` under the "remove" label, so the text stays `Old note`. Mobile removes the component.
  2. The default font: Evolution's `TextAttributes` uses `SansSerif bold 16` (`StdAttr.DEFAULT_LABEL_FONT`). Mobile's `AnnotationLibrary` falls back to `SansSerif plain 12`, the 2.7.1 default, for every source version. The written XML is the same, because neither program writes the default. Mobile, however, measures and draws a Text without `font` smaller: bounds `176,136,88x24` against Evolution's `162,128,117x32` for the two-line text ([log](logs/base-tools/text-tool.log)). A Text with an explicit font has the same bounds in both.
- **MenuTool.** `MenuDesktopOracle` builds Evolution's `MenuTool.MenuComponent`, plus each component's `MenuExtender`, and `MenuSelection` ([log](logs/base-tools/menu-desktop.tsv)). Evolution's popups are:
  - a component with a facing: Rotate Left/Right, then Delete and Show Attributes;
  - extender items: View for subcircuits; Edit/Clear/Load/Save Contents for RAM and ROM; Edit PLA Program; Splitter Distribute Ascending/Descending;
  - a multi-selection: Delete/Cut/Copy Selection;
  - empty canvas: no menu.

  Mobile's canvas menu (`CircuitCanvas.kt`, `CanvasContextMenu`) has these items:
  - the tip;
  - Show attributes, only with accessibility actions;
  - View subcircuit;
  - Split wire here;
  - Copy, Cut, Paste, Duplicate, Delete, Select all, Fit selection.

  It opens on empty canvas too. It has no Rotate items and none of the extender items, although Mobile offers those actions elsewhere (for example, Clear contents in `MemoryEditorPanel`).

Not covered: pointer hit-testing, rendering and native menus, which need the composeApp UI.
