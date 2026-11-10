# Preference defaults against Evolution d58a61e0 (2026-11-08)

Issues #337 (canvas colours and pin dots), #338 (HDL language and FPGA board) and #339 (empty template), found by
the #336 campaign ([`preferences-02f776ab`](../preferences-02f776ab/README.md)). macOS 27.0.1 on Apple silicon,
Homebrew OpenJDK 21.0.13, worktree at `f392a689` (the fix and the template-state follow-up, rebased on `main` `f465061e`).

| Artifact | SHA-256 |
| --- | --- |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |
| `fixtures/preferences/evolution.tsv` (661 rows, reused from #336: the Evolution side does not depend on Mobile) | `58dd698eaca201bf6df557174119eb238d94f18a466471361255908646676521` |
| `fixtures/preferences/mobile.tsv` (422 rows) | `b143b4295ca09970d78df9107df218ed3fd0b5e987e2bca79331167383b6e657` |
| `fixtures/creation-defaults/evolution-load.tsv` (with the new `empty` profile) | `5f8b44c886d42cda9f7619a42fee1519f029209c88f0b2cce010e5ecdf99e446` |

## Commands

```sh
LOGISIM_REGENERATE_FIXTURES=1 ./gradlew :composeApp:testAndroidHostTest --tests '*EditorPreferencesEvolutionTest' --tests '*CreationDefaultsEvolutionTest'
scripts/oracle/creation-defaults/run.sh EVOLUTION_JAR      # exit 0: every profile, including empty, loads like Evolution's file
./gradlew :composeApp:testAndroidHostTest --tests '*PreferencesEvolutionDefaultsTest' --tests '*EditorPreferencesEvolutionTest' \
  --tests '*CreationDefaultsEvolutionTest' ...               # the focused list below
python3 scripts/oracle/preferences/compare.py --rows <the 17 keys> --output comparison.tsv
```

Focused host tests, all passing at `f392a689`: `PreferencesEvolutionDefaultsTest` (7), `EditorPreferencesEvolutionTest`
(2), `CreationDefaultsEvolutionTest` (1, four profiles), `CircuitColorPreferencesTest` (2), `AppearancePreferencesTest`
(11), `DarkCanvasContrastTest` (4), `DarkThemeTextColorTest` (5), `FpgaBoardCatalogStoreTest` (2), `EditorPreferencesTest` (20),
`FpgaPreferencesTest` (2), `PreferencesRunIsolationTest` (1), `CatalogWorkspaceRuntimeTest` (16), `PrinterViewTest` (2),
`RendererDINTest` (6), `RendererDINExportTest` (1), `CircuitDrawingTest` (8), `CircuitAccessibilityViewportTest` (6),
`AdditionalEditorToolsTest` (13), `VisualExportToolsTest` (7). `scripts/generate-localization.py --check --check-ui` and
`scripts/test-localization.py` pass. The iOS raster test (`CircuitDrawingRasterTest`) has its expected pixels updated to
the new ink and unknown colour; `:composeApp:compileTestKotlinIosSimulatorArm64` passed before the rebase on
`f465061e`, but the test was not run (no simulator was used).

## Result

[`comparison.tsv`](comparison.tsv): the 12 preference rows and the `TemplateOptions`, `FpgaOptions` and `SimOptions`
panels are SAME. `WindowOptions` and `LayoutOptions` keep only the explained differences of their own rows
(`FollowSystemTheme`, `TOOLBAR_PLACEMENT`, `POKE_WIRE_RADIX2`).

| Preference | Evolution | Mobile before | Mobile now |
| --- | --- | --- | --- |
| `GRID_DOT_COLOR` / `GRID_ZOOMED_DOT_COLOR` | `777777` / `CCCCCC` | `C8C8C8` | same as Evolution |
| `COMPONENT_COLOR`, `COMPONENT_ICON_COLOR` | `000000` | `222222`, `202020` | `000000` (renderers, previews, printing, vector ink) |
| `COMPONENT_GHOST_COLOR` | `999999` | `222222` (ghost only with a custom colour) | `999999`, always used by placement previews |
| `TRUE_COLOR`, `UNKNOWN_COLOR`, `ERROR_COLOR` | `00D200`, `2828FF`, `C00000` | `00CC00`, `0000CC`, `CC0000` | same as Evolution; pin markers use the wire colour of their point (`drawPin`) |
| `NIL_COLOR` | `808080`, dark `BBBBBB`, for `Value.NIL` (bundle without width) | none (unknown colour) | wires whose net has no terminal, canvas and exports |
| `PinAppearance` | 4/6/8/10 px at offset 2/3/4/5 | 3.2/4.8/6.4/8 | same as Evolution |
| `HdlType` | saved, `VHDL`; `VHDL`, `Verilog`, `None` | Verilog at each opening, not saved | `fpga.hdlType`, VHDL; export and FPGA build follow it; None shows `FpgaGuiSelectHdl` |
| `SelectedBoard` | first built-in board (`ALCHITRY_AU_IO`) without a valid choice | none | same as Evolution, saved; removing the selected external board falls back to it |
| `TEMPLATE_TYPE` | `empty,plain,custom`, plain | `plain,custom` | `empty,plain,custom`, plain |

The empty template ([`creation-defaults-empty.tsv`](creation-defaults-empty.tsv)): Evolution with
`setTemplateType(TEMPLATE_EMPTY)`, File › New and a new circuit, against Mobile's empty template and a new circuit.
No libraries, toolbar or mappings; the main circuit carries the creation defaults (`appearance`,
`circuitnamedboxfixedsize`, `simulationFrequency=1.0`). `circuits-and-components-match`, `template-matches` and
`evolution-resave-unchanged` are true. The other three profiles are unchanged.

Saved choices: colours stored by the user, a saved HDL language and board, and a custom template installed before
the template choice existed (read as `custom`) are kept.
