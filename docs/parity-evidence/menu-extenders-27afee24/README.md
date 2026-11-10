# Canvas menu: SoC, PLA ROM and Image MenuExtenders against Evolution (#359)

Mobile `27afee24` (the fix, on `main`). Core JAR from `./gradlew :core:jvmJar` at `27afee24`, SHA-256 `54cd741eaa2470e338680c6d622818d14791dc5d3d76eeb01d8d1464e377fb84`. Evolution `d58a61e0`, JAR `logisim-evolution-5.1.0dev-all.jar`, SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. macOS (Apple silicon), Homebrew OpenJDK 21.0.13, 2026-11-08. The comparison of the other menus (#347) stays in [base-tools-bc0a598f](../base-tools-bc0a598f/README.md); this run repeats it.

## JVM oracle (at `27afee24`)

```sh
./gradlew :core:jvmJar
scripts/oracle/base-tools/run.sh EVOLUTION_JAR OUT   # exit 0: SAME poke, SAME menu, SAME image, SAME image-menu, SAME text-tool
```

The menu oracle now needs a graphics environment, because the SoC bus builds a Swing dialog (`java.awt.headless=false`, `apple.awt.UIElement=true`, no window shown). Each Evolution process runs under a 60 s SIGKILL watchdog.

| Comparison | Result | Log |
| --- | --- | --- |
| Menu items (`MenuTool.MenuComponent` + the component's `MenuExtender`) | SAME for all 20 popups. New: `Rv32im` and `Nios2` (`SocUpMenuProvider`): Open assembler, Read ELF file; `SocBus` (`SocBusMenuProvider`): Show memory map, Insert a bus transaction; `SocPio` (`PioMenu`) and `SocVga` (`VgaMenu`): Export C-templates; `Socmem`: no extender; `PlaRom` (`PlaMenu`): Edit Contents…, Clear Contents; `Image`: Reset to Original Size, Optimize Image Size, Make Outer Background Transparent, without a separator. After loading `fixtures/soc/sum-rv32.elf` and `sum-nios2.elf` (Evolution `ProcessorReadElf`, Mobile `SocLoadExecutableEvent` through `CircuitSimulator`), both CPUs add Show loaded program. | [desktop](logs/oracle/menu-desktop.tsv), [mobile](logs/oracle/menu-mobile.tsv) |
| Image menu decisions (`ImageUtil.enforceMaxDimensions`, `getOptimizedTargetDimensions`) | SAME: cached sizes of 5 images (2048 limit); Reset to Original Size and the Optimize decision (already optimized, or resize and the new frame for "fit") for 2 PNGs × 8 frames in fit, stretch and cover. | [desktop](logs/oracle/image-desktop.tsv), [mobile](logs/oracle/image-mobile.tsv) |
| Image menu pixels (`ImageUtil.optimizeImage`, `makeWhiteTransparent`, stored with `bufferedImageToBase64`) | SAME: all 14 stored PNGs (12 resizes and 2 transparencies) match pixel for pixel, maximum channel difference 0. `ImageMenu.scale` follows Java2D's `TransformHelper` bilinear loop: 32.32 fixed-point coordinates accumulated per row and column, 8-bit fractions, premultiplied samples and AlphaMath's `mul8table`/`div8table`. | [pixels](logs/oracle/image-pixels.log) |
| Poke, Text, other menus | SAME, as in #345–#347. | [run](logs/oracle/run.log) |

The fixtures are `scripts/oracle/base-tools/menu-tool.circ` (now with the SoC, PLA ROM and Image components), `fixtures/soc/program-*.circ` with their ELF files, and `badge.png` and `photo.png` (white border, an enclosed white area, a near-white corner, semi-transparent and transparent border pixels, an alpha ramp). `ImageMenuDesktopOracle` calls the `ImageUtil` functions each menu action calls on the cached image. Optimize asks through `JOptionPane`, so the oracle takes "yes" in place of the dialog.

## Effects

| Item | Evolution | Mobile |
| --- | --- | --- |
| Open assembler, Show loaded program | Opens the assembler or disassembler window of the CPU | Selects the CPU and opens the SoC tool, which holds the assembler (#353, #357) and the loaded program. |
| Read ELF file | File chooser, `ProcessorReadElf`, then a success or error dialog | Platform picker, `SocLoadExecutableEvent`; the success notice is `ProcReadElfLoadedAndEntrySet`, and failures report the simulator's notice. |
| Show memory map, Insert a bus transaction | Memory map window; transaction dialog | Selects the bus and opens the SoC tool, which shows the memory map (#177) and the transaction form. |
| Export C-templates | Directory chooser, writes `<NAME>.h` and `<NAME>.c` | Writes the same two files from `SocDriverExport` (#197) through the batch file picker; without one, opens the SoC tool. |
| Edit Contents… (PLA ROM) | Matrix editor window | Opens the attribute inspector, which holds the matrix editor. |
| Clear Contents (PLA ROM) | `clearMatrixValues`, saves an empty `Contents` | Same value, as one undoable edit; no change when already empty. |
| Image items | `CircuitMutation` actions; Optimize confirms or reports "already optimized" | Same attributes as one undoable edit; Optimize confirms with the same message or reports "already optimized"; no image does nothing, as in Evolution. |

## Tests

At the tree of `27afee24` before its rebases onto `45578ffd` and `c0b76868`. The rebases brought only visual-export changes (#356, #358, #360), which touch none of these files.

- `./gradlew :core:jvmTest --tests '*ComponentMenuTest' --tests '*ImageMenuTest'`: 3 + 3 pass ([xml](logs/tests/TEST-dev.logisim.mobile.core.ComponentMenuTest.xml), [xml](logs/tests/TEST-dev.logisim.mobile.core.ImageMenuTest.xml)).
- `./gradlew :composeApp:jvmTest --tests '*ComponentMenuActionsHostTest'`: 3 pass ([xml](logs/tests/TEST-dev.logisim.mobile.ComponentMenuActionsHostTest.xml)). It drives `EditorState` with Skia decoding and encoding, as on iOS: Reset to Original Size, Make Outer Background Transparent and a confirmed Optimize, each undoable, an "already optimized" notice, and PLA ROM Clear Contents.
- `./gradlew :composeApp:testAndroidHostTest --tests '*PinPokeEditorTest'` passes, which also compiles the Android `encodeAnnotationPng`.
- **iOS**: `./gradlew :composeApp:iosSimulatorArm64Test --tests '*CanvasPokeAndMenuTest' -PiosTestDevice=<own iPhone 17e, iOS 27.2 simulator, deleted afterwards>`: 4/4 pass ([xml](logs/tests/TEST-iosSimulatorArm64Test.dev.logisim.mobile.CanvasPokeAndMenuTest.xml)). The new case long-presses a CPU holding a program and an Image on the real `CircuitCanvas`. It sees exactly Delete, Show Attributes and the three SoC items, then Delete, Show Attributes and the three Image items, and both clicks reach the menu handler.
- `python3 scripts/generate-localization.py --check --check-ui --require-reference-locales` and `python3 scripts/check-source.py` pass. The item names come from Evolution's translations, through `scripts/seed-evolution-translations.py`, where Evolution has them.

## Remaining Mobile differences

- The SoC windows (assembler, disassembler, memory map, transaction dialog) are sections of the SoC tool sheet, not separate windows. On the canvas, Evolution's trace-window item appears only for a CPU or bus reached through the simulation hierarchy (`CircuitStateHolder`). The canvas menu never shows it, in Evolution or in Mobile, and the SoC tool shows the bus trace.
- Android stores the Image results through `Bitmap.compress`, which premultiplies. A fully transparent pixel keeps alpha 0 but loses its colour, which Evolution's PNG keeps. The image looks the same.
- `ProgrammableGenerator` has a `MenuExtender` (Edit Contents…, Clear Contents), but Evolution `d58a61e0` comments it out of the Input/Output-Extra library ("Broken component"). It cannot be placed or loaded, so it has no menu to compare. `docs/extra-components.md` already records it as excluded.
- Android was not driven on an emulator for these items. The iOS test runs the shared `CircuitCanvas`, and #347 already proved Android's canvas menu routing.
