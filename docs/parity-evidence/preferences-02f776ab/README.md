# User preferences against Evolution d58a61e0 (2026-11-08)

Issue #336 (campaign for the 93 "Não comparado" rows of the Preferences group: preferences 79, FPGA 10, HDL 2,
localization 2). macOS 27.0.1 on Apple silicon, Homebrew OpenJDK 21.0.13, worktree at
`02f776ab6da3418507f721ec347f4eb06f5b8a09` (the oracle and the gate-shape fix, rebased on `main` `90fe302d`).

| Artifact | SHA-256 |
| --- | --- |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |
| `fixtures/preferences/evolution.tsv` (661 rows) | `58dd698eaca201bf6df557174119eb238d94f18a466471361255908646676521` |
| `fixtures/preferences/mobile.tsv` (421 rows) | `89934bbd5544390d23edb28c23036f6fd1a8e68f97cc38d51aa051a151de6d05` |

## Commands

```sh
scripts/oracle/preferences/run.sh EVOLUTION_JAR            # Evolution rows, about 40 s
./gradlew :composeApp:testAndroidHostTest --tests '*EditorPreferencesEvolutionTest' --tests '*EditorPreferencesTest'
python3 scripts/oracle/preferences/compare.py --rows <the 93 keys> --output comparison.tsv
```

The host tests passed at that revision: `EditorPreferencesEvolutionTest` (2 tests: `mobile.tsv` and the two DRC
fixtures are what Mobile produces), `EditorPreferencesTest` (20), `AppearancePreferencesTest` (11),
`FpgaPreferencesTest` (2) and `PreferencesRunIsolationTest` (1). The Evolution rows do not depend on the Mobile revision.

## What is compared

`PreferencesOracle` runs in the Evolution JAR with in-memory `java.util.prefs` (`MemoryPreferencesFactory`), so the
user's own preferences are neither read nor written. `EditorPreferencesEvolutionTest` writes the same rows from
Mobile's model and renderers. [`comparison.tsv`](comparison.tsv) has one line per matrix row with the number of
facts and every difference.

| Facts | Evolution side | Mobile side |
| --- | --- | --- |
| Default, dark default, choices | every `PrefMonitor` of `AppPreferences` by reflection (`ThemeColorPreference.darkDefault`, `PrefMonitorStringOpts.opts`, `SymbolChooser` items) | `EditorPreferences()`, enums mapped to Evolution's stored names, `EditorColor.defaultColor` and `DarkEditorColors` |
| Shortcuts | `PrefMonitorKeyStroke.getList()`; `menu` is `hotkeyMenuMask` (Alt in headless mode, Ctrl/⌘ in the GUI) | default binding and aliases, each resolved back to its command by `editorShortcutFor` |
| Value characters | each choice set through the preference; `SimOptions`' listener updates `Value.*CHAR`; 4-bit 1010, unknown, error and don't-care rendering | `LogicVector.display` with each `ValueCharacters` choice |
| Wire Poke | `PokeTool.WireCaret.draw` text (radix 1, radix 2 for width > 1, float for 8/16/32/64 bits; reproduced because `draw` needs a Canvas) over 8 values, each radix as primary and as secondary | `wirePokeReadings` joined by ` / ` |
| VHDL keyword case | `Vhdl.getVhdlKeyword` for the 103 reserved words, upper and lower | `VhdlKeywordCase.apply` |
| Pin dots | pixels filled by `ComponentDrawContext.drawPinMarker` at a terminal | `CircuitDrawingStyle.terminalRadius` |
| FPGA warnings | `Netlist.designRuleCheckResult` on `drc-gated.circ` and `drc-pin-clock.circ` (written by Mobile), messages caught through `Reporter`; warning groups (open pins, gated clocks) for the 4 suppression settings | `FpgaCircuitRules.review` notice keys grouped the same way |
| Board | `SelectedBoard` after `FpgaBoards` starts; built-in board list | `FpgaBoardCatalogStore().load().selected`; `fpga-boards/index.txt` |
| Gate shape by locale | `GATE_SHAPE` default in JVMs started with `-Duser.language=ru` and `pt` | `defaultEditorGateShape(language)` |
| Panels | the `AppPreferences` fields each panel's source edits (`panel-source` rows; `HotkeyOptions` lists every keystroke preference) | the result of those preference rows |

Out of scope of these facts: what the user sees on screen. The #310 and #312 effects (system theme, HDL editor
themes, halo, tips, pin dots, lined K-map, appearance grid) were proven on screen in
[`display-preferences-6f4a7735`](../../validation/display-preferences-6f4a7735/README.md), and the #311 colours and
characters in [`remaining-colors-311`](../../validation/remaining-colors-311/README.md). The number of DRC messages
differs (Evolution reports open inputs per component, Mobile per terminal); the preference only decides which groups
are shown, and those match.

## Result

69 rows SAME (`igual`), 7 explained differences, 17 divergent.

Fixed during the run: the gate shape default now follows the system language (rectangular for Russian), like
`AppPreferences.getDefaultGateShape()` (`defaultEditorGateShape`, test `gateShapeDefaultFollowsTheSystemLanguageLikeEvolution`).

Explained differences (Mobile keeps every Evolution choice and offers one more, or follows a platform convention):
`GATE_SHAPE` (DIN 40700), `TOOLBAR_PLACEMENT` (down-middle), `POKE_WIRE_RADIX2` (none), `HOTKEY_EDIT_REDO`
(Ctrl/⌘+Y aliases), `FollowSystemTheme` (default on), and the `IntlOptions` and `HotkeyOptions` panels.

Divergent, with issues:

| Issue | Rows |
| --- | --- |
| #337 | `GRID_DOT_COLOR`, `GRID_ZOOMED_DOT_COLOR`, `COMPONENT_COLOR`, `COMPONENT_GHOST_COLOR`, `COMPONENT_ICON_COLOR`, `TRUE_COLOR`, `UNKNOWN_COLOR`, `ERROR_COLOR`, `NIL_COLOR`, `PinAppearance`; panels `WindowOptions`, `LayoutOptions`, `SimOptions` |
| #338 | `HdlType`, `SelectedBoard`; panel `FpgaOptions` |
| #339 | panel `TemplateOptions` (no empty template) |

These runs prove JVM and host behaviour only, not Android or iOS rendering on a device.
