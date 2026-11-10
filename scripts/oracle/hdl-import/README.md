# HDL import differential oracle

This check runs the production import path and opens its unmodified `.circ` in
the original Logisim Evolution engine. It does not invoke Gradle or the GUI.

```sh
python3 scripts/oracle/hdl-import/run.py \
  --mobile-jar /path/core-jvm.jar --mobile-sha256 MOBILE_SHA256 \
  --desktop-jar /path/logisim-evolution-all.jar --desktop-sha256 DESKTOP_SHA256
```

Required tools: Java/javac 21, Yosys, Icarus Verilog and VVP. The script locates
the Kotlin standard library matching `build.gradle.kts` in the Gradle cache;
`--stdlib-jar` selects an existing copy explicitly. The supplied JAR hashes are
mandatory. Their identities are checked again at the end.

For each existing fixture, the script:

1. Generates the parameters with the app's actual `HdlImportOptions.request`
   boundary, then submits explicit files to the same `companion.hdl_import.synthesize` handler
   used by companion jobs, with its fixed Yosys script and file policy.
2. Passes that actual netlist and the original sources to `HdlNativeImporter`
   in the selected production JAR. It writes the resulting project with
   `CircCodec`, then repeats the import to require identical `.circ` bytes. It also verifies
   exact archived source bytes before and after a mobile round-trip.
3. Executes the original Verilog in Icarus for every binary input combination.
   BLIF uses a separate Yosys read/write path from the original BLIF file to
   obtain the Icarus reference; it does not use the imported `.circ` or core
   simulator to generate expected outputs.
4. Reopens the **same `.circ` bytes** in both the production core and the
   original desktop Loader/TestVectorEvaluator. Both must match every
   reference output. The desktop adapter requires the vector header to cover
   every top-level pin.
5. Compares circuit inventory, instance links, pin labels/directions/widths/
   positions/facing, and gate type/width/input count/size/facing. Module and pin
   inventories must also match the synthesized netlist.

The four fixtures cover a hierarchical XOR, multibit AND/XOR/multiplexer,
explicit include/macro inputs, and BLIF AND: 44 complete input combinations.
The recorder keeps original files, synthesis scripts/logs, JSON netlists,
`.circ` projects, testbenches, vectors and full stdout/stderr under
`outputs/hdl-import-desktop-oracle`. `receipt.json` includes hashes, tool
versions, commands and complete canonical observations.

Negative controls require the comparator to reject changed hierarchy, pin
width and a missing source archive. Another control changes an actual imported AND gate to NAND in a copy
and executes that copy in the desktop engine; its changed truth table must be
rejected by both structure and truth-table comparisons. The normal comparison never filters metadata or rewrites the
importer's output to make the desktop accept it.

This check samples the published binary, combinational import profile. It does
not establish complete HDL language coverage, sequential timing, analog
behavior, physical device interaction, or an operating-system sandbox. The
handler is invoked locally to exercise its exact transformation; authenticated
HTTP, cancellation and file-boundary tests remain separate integration tests.
Unknown custom source-archive options survive the mobile round-trip and are
accepted by the desktop, but the desktop discards those options when it saves
the project again.
