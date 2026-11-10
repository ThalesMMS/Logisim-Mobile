# Menu actions compared with Evolution d58a61e0 (2026-11-07)

Issue #332. macOS (Apple silicon), Homebrew OpenJDK 21.0.13, Homebrew Yosys and Icarus, clean worktree at `a6c86b177a089b22c780d9984d6c163ccdea8aca`. The same change landed on `main` after rebasing as `65649c084c0ad1ce10dd4a27814d2347367e28fb`.

| Artifact | SHA-256 |
| --- | --- |
| Core JAR (`./gradlew :core:jvmJar` at `a6c86b17`) | `5e3cac10aef425115d114322ff878a32e233416e4bf0a81df9767b6adf661a4c` |
| Evolution JAR (`logisim-evolution-5.1.0dev-all.jar`, `d58a61e0`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

`scripts/oracle/menus/run.sh EVOLUTION_JAR` runs Gradle’s `MenuActionsParityExportTest` followed by headless Evolution. [Method](../../../scripts/oracle/menus/README.md).

The logs replace local paths with `EVOLUTION/`, `MOBILE/`, and `TMP`. [Results by case](menu-actions.json) · [Logs](logs).

## Result

In 53 of 59 cases, the resulting documents (both reopened and resaved by Evolution) or observed states match. The remaining six differences are accepted in `scripts/oracle/menus/expect.json`. The run fails on any other difference.

| Matrix row | Case | Result |
| --- | --- | --- |
| `MenuEdit/undoHistory`, `redoHistory`, `clearHistory` | Perform three actions, undo and redo to the second entry, then clear | Document and stack counts match (`3`, `1/2`, `2/1`, `0/0`) |
| `MenuEdit/cut`, `dup` | AND, NOT, and a wire in `main`. Duplicate the NOT. | Match. Evolution places the copy at a free offset (10,10) or under the pointer. Mobile places it at the tapped point. |
| `MenuEdit/raise`, `lower`, `raiseTop`, `lowerBottom` | `ModelReorderAction` on the shapes in the `half` appearance | Drawn shape order matches. The instance omits the anchor and ports. |
| `MenuEdit/addCtrl`, `remCtrl` | Insert a vertex on the closing edge and remove (85,85) | Match. Mobile’s *Adicionar vértice* appends a vertex. Inserting at another point requires editing the coordinates. |
| `MenuProject/addCircuit`, `moveUp`, `moveDown`, `remove`, `setAsMain` | Circuit actions | Match |
| `MenuProject/revertAppearance` | `RevertAppearanceAction` | In `d58a61e0` the action only saves the drawing (`setDefaultAppearance` has been commented out since `6dce9c7f8`). Mobile restores the default appearance. |
| `MenuProject/appearance`, `layout` | First edit of the default appearance of a *logisim_evolution* circuit and a *classic* circuit | Match relative to the anchor. Evolution draws with `OFFS = 50` and "Courier 10 Pitch" (or Java’s default when unavailable). Mobile draws at the origin with "Monospaced". Switching to *Layout* preserves the document in both. |
| `MenuProject/options` | `simlimit=500`, `gateUndefined=error` | Match after the #341 fix in this commit |
| `MenuFile/open`, `save`, `saveAs` | Open and save `project.circ` | Match |
| `MenuFile/merge` | `top` from `merge.circ` with dependencies | Match after this commit orders dependencies like `addCircWithDeps`. For duplicate names, Evolution asks whether to replace or rename to `_1`. Mobile renames automatically. |
| `MenuFile/openRecent` | Open r01…r12 and reopen r03 | Match, `r03 r12 r11 … r04` (10 entries, most recent first) |
| `MenuFile/extractRunProj` | `portable53.lsebdl` | Match, main `Top53`, circuits `Bridge53, Cell53, Top53`, outputs `00001000` and `00000011`. Mobile stores library circuits with the prefix `library_<digest>_`. |
| `MenuProject/loadBuiltin`, `unload` | Unload and reload `#Memory` | Match |
| `MenuProject/loadLogisim` | `library.circ` | Mobile copies library circuits into the project where Evolution references `file#library.circ`. |
| `MenuSimulate/tickHalf`, `tickFull`, `reset`, `step`, `downStateMenu`, `upStateMenu` | `sim.circ` (two T flip-flops, one in a subcircuit), `step.circ` (three NOT gates) | Every state matches, including each propagation step. |
| `MenuSimulate/ticksEnabled` | 4 automatic ticks at 64 Hz | State matches. Mobile’s period is 16 ms (integer milliseconds), Evolution’s is 15.625 ms. |
| `MenuSimulate/tickFreq` | Save 8 Hz in the document | Document matches. Mobile’s menu offers 1–64 Hz, Evolution’s 0.25 Hz–2048 kHz. The options panel accepts any value. |
| `MenuSimulate/log` | Clock, flip-flop, and `q0` over four half-ticks | Values match. Evolution starts with these signals selected. Mobile starts with an empty selection. |
| `MenuSimulate/test` | `half.txt` with one incorrect row | Match, `4/1` and the same rows |
| `MenuProject/analyze` | Table and minimal expressions for `half` | Match, `S = ~A·B + A·~B` and `C = A·B` |
| `MenuProject/stats` | Statistics for `main` | Match after this commit excludes Text, as `FileStatistics` does. |
| `MenuProject/addVhdl`, `importVhdl` | Evolution template for `my_entity`, `counter.vhd` | Match after this commit writes `<vhdl>` after the circuits. Mobile accepts pasted text without a prefilled template, and the shared definition also inserts an instance. |
| `MenuProject/importVerilog` | `hierarchy`, `gates`, `macros` | Match. Evolution’s `VerilogImporter` (Yosys) and Mobile’s import pass the same 40 Icarus vectors with the same pins. |
| `MenuFpga/BoardEditor` | Resave GECKO5_EDUCATION and BASYS3 | Divergent (#342). Mobile writes explicit pull and activity settings where the board left them unspecified. Evolution’s `BoardWriterClass` changes image pixels and Mobile preserves them. |

## Help, window, and integration items compared by content

| Row | Evolution `d58a61e0` | Mobile |
| --- | --- | --- |
| `MenuHelp/www` | Opens `BuildInfo.url` = `https://github.com/logisim-evolution/` | Opens `AppIdentity.repository` = `https://github.com/ThalesMMS/logisim-mobile-dev` |
| `MenuHelp/about` | `About` with version 5.1.0dev, credits, and GPLv3 | App version and build (`help_build`) and legal notices, including Logisim Evolution’s GPL |
| `MenuHelp/tutorial`, `guide`, `library` | JavaHelp `doc_en.hs`, topics `tutorial`, `guide`, and `libs` (13 libraries) | Built-in guides for editing, wiring, simulation, diagnostics, saving, and navigation, verifiable examples, `docs/user-guide.md`, and a recorded component count per library |
| `MenuHelp/search` | *Find Action* runs `OmniSearchDialog` over menu actions | Action and project search (`EditorSearchCommands`, `ProjectSearch`), also available by pressing Shift twice |
| `WindowMenu/toolbar` | Toggles `TOOLBAR_PLACEMENT` between `north` and `hidden` | The `toolbarPlacement` preference (`TOP` … `HIDDEN`) controls this outside the menu. |
| `WindowMenu/explorer` | Toggles the window’s project panel | Side explorer button on wide screens |
| `WindowMenu/close`, `MenuFile/close` | `Project.isFileDirty` determines whether to prompt to save | The workspace draft saves before closing. The unsaved-changes prompt comes from session state. |
| `MenuFile/prefs` | Tabbed preferences window | Preferences panel. Each preference has its own matrix row (#336). |
| `MenuProject/loadLibrary` | Submenu for built-in, Logisim, and JAR | Library manager with the same three sources (`loadBuiltin` and `loadLogisim` compared above) |
| `MenuProject/loadJar` | Loads the library class in the JVM | The JAR runs in the companion, off the mobile device. |
| `MenuSimulate/simulateVhdlEnable` | Enables global VHDL simulation in QuestaSim (out of scope) | Per-component simulation through the companion |

The same `run.sh` records the HDL import oracle (`logs/hdl-import-receipt.json`, PASS for 44 rows). This commit updates `HdlImportMobileOracle` to the current `HdlImportOptions` signature.

This receipt covers JVM behavior. `MenuFile/print`, `MenuFpga/FPGACommander`, `MenuSimulate/assemblyWindow` (#343), and the Android and iOS interfaces are outside this comparison.
