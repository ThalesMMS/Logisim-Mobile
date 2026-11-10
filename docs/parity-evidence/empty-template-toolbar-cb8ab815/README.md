# Empty-template toolbar against Evolution d58a61e0 (2026-11-07)

Issue #344, found while closing #339. macOS 27.0 on Apple silicon, Homebrew OpenJDK 21, worktree at `cb8ab815`
(rebased on `main` `e272b65d`).

| Artifact | SHA-256 |
| --- | --- |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |
| `fixtures/creation-defaults/evolution-load.tsv` (regenerated, byte-identical to the #339 record) | `5f8b44c886d42cda9f7619a42fee1519f029209c88f0b2cce010e5ecdf99e446` |

## Commands

```sh
scripts/oracle/creation-defaults/run.sh EVOLUTION_JAR   # exit 0; evolution-load.tsv and evolution-*.circ unchanged
./gradlew :composeApp:testAndroidHostTest --tests '*CreationDefaultsEvolutionTest' --tests '*EditorProjectToolbarTest' \
  --tests '*EditorToolbarLayoutTest' --tests '*TextToolTest' --tests '*ImportedEditToolTest' --tests '*CanvasMouseMappingTest'
```

All 28 tests pass at `cb8ab815`. The new `CreationDefaultsEvolutionTest.displayedToolbarsMatchTheToolbarsEvolutionLoaded`
opens each saved Mobile file and compares the toolbar the editor shows (`projectToolbarEntries`) with the `toolbar` rows
Evolution's loader records for the same profile.

## Result

| Profile | Evolution toolbar | Mobile before | Mobile now |
| --- | --- | --- | --- |
| `empty` (Template.createEmpty()) | none | default.templ's 18 entries | none |
| three default.templ profiles | default.templ's 18 entries | same | same |

XmlReader fills `ToolbarData` only from a `<toolbar>` element, so any file without one shows an empty toolbar in
Evolution; Mobile now does the same in the editor and in Project > Toolbar. A toolbar whose tools none can be bound still
falls back to default.templ's toolbar, as before. The touch-only Move control stays in the editor header.
