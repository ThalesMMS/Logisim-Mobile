# Reproduce the Gates corpus

The corpus uses the 13 components registered in `GatesLibrary` in desktop Logisim Evolution. Each row records actual component propagation, delay, terminals, and drawing bounds.

```sh
./scripts/oracle/gates/generate.sh /path/to/logisim-evolution-all.jar
./gradlew :core:jvmTest --tests '*Gates*Test'
```

The corpus includes 312 cases covering widths of 1, 8, and 64 bits, up to 64 inputs, orientations, sizes, negations, output types, undefined values, and the `gateUndefined` option. The generator uses the fixed seed `40031`.

`GatesDesktopParityTest` compares the evaluator and registered geometry against the external values in `core/src/jvmTest/resources/gates-desktop.tsv`. `GatesLibraryTest` covers truth tables, control cases, and the compact and tabular PLA formats.

The PLA preserves the desktop's row priority. The desktop converts an undefined input to `-1` before looking it up, so all input bits are treated as 1.
