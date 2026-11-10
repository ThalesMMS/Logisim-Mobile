# Compare board imports with the desktop

Build the mobile core and the desktop fat JAR, then run with JDK 21 or newer:

```sh
./gradlew :core:jvmJar
./scripts/oracle/fpga-boards/run.sh /path/to/logisim-evolution-all.jar
```

The runner reads the checked-in `GECKO5_EDUCATION.xml` and `BASYS3.xml` fixtures with desktop `BoardReaderClass` and mobile `FpgaBoardCodec`. It compares the board name, device and clock metadata, resource order, types, labels, rectangles, and each physical pin's location, direction, and electrical settings. It exits on the first differing field and prints the desktop and mobile values.

The second argument overrides `core/build/libs/core-jvm.jar`. Set `KOTLIN_STDLIB_JAR` to override the standard library found in the Gradle cache. The runner prints the hashes of both JARs and both XML files to identify the inputs.

Desktop scanning displays expand physical outputs into logical LED or segment pins. The oracle uses `getExternalPinCount()` for these resources and compares their physical locations as outputs. It does not equate that count with desktop `getNrOfPins()`.

Absent pull settings map to `FLOAT`. Desktop unknown or default I/O standards and drive strength map to `null`. An absent activity setting maps to `activeLow=false`.
Resource types compare without case, matching desktop lookup. BASYS3 spells
its LED XML elements `LED`, while desktop stores the enum name `Led`.

This check covers board import metadata. It does not compare board image decoding, malformed XML diagnostics, mapping, generated HDL, constraints, synthesis, programming, or hardware behavior. The mobile importer preserves the XML without decoding its picture.
