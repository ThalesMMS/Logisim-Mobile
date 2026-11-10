# PLA table files compared with Evolution

- Mobile: `1a03cf669b165fad6606d2af95e7eeea706a899a`
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, JAR `logisim-evolution-5.1.0dev-all.jar`
  (SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS, OpenJDK 21 (Homebrew), JVM.

## Steps

1. `./scripts/oracle/gates/pla-table-file.sh EVOLUTION_JAR` runs Evolution’s `PlaTable.save` on
   `fixtures/pla/evolution-saved.txt`, then uses `PlaTable.parse(File)` to read the three files in
   `fixtures/pla` (the one Evolution saved, `mobile-saved.txt` in Mobile’s format, and
   `flexible.txt` in the `||` format). The output is in `fixtures/pla/evolution-read.tsv`.
2. `./gradlew :core:jvmTest --tests '*PlaTableFile*'` compares Mobile with these records.

## Result

- `PlaTableFile.write` produces a byte-for-byte match with the file saved by Evolution, including
  the header, bit order, and trimmed comment.
- Mobile reads the same widths and rows as Evolution in all three files. `PlaProgram.evaluate`
  matches `PlaTable.valueFor` on all 16 + 32 + 8 inputs.
- `PlaTableFileTest` covers messages for input and output widths, rows with too many or too few bits,
  invalid bits, and empty files.
- `PlaTableFileEvolutionTest` passed 3/3 tests and `PlaTableFileTest` passed 4/4.
