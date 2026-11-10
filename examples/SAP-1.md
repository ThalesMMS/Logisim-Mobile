# SAP-1 reference

`sap1.circ` is an exact copy of the [SAP-1-Logisim circuit](https://github.com/ThalesMMS/SAP-1-Logisim/blob/ddb9dd86d601ac217157beedeb88464ee0128c87/circuits/sap1.circ) at commit `ddb9dd86d601ac217157beedeb88464ee0128c87`. The original MIT license is in `SAP-1-LICENSE.txt`. The circuit's SHA-256 is `2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac`.

The file originated in Logisim Evolution 3.8.0. It contains seven circuits, 156 components, and 318 wire segments. The program stored in ROM computes `2 + 3 + 4 - 1 = 8`.

The JVM test `Sap1IntegrationTest` imports the original file, pulses the `Reset` input, and advances the clock. The output register is at `(600,340)` in the `main` circuit. The `Valor` bus floats between transfers, so read the final result from the output register.

An independent oracle uses the Logisim Evolution Java simulator. With the `5.1.0dev-all` desktop JAR, the output register starts at `00`, changes to `08` at step 56, and holds `08` through step 160. Each step toggles the clock once. The oracle leaves the circuit intact.

The `scripts/verify-sap1.sh` script runs the Mobile test. Pass the path to a desktop JAR to run both engines and compare their traces in `outputs/sap1-validation/`. The comparison matched 4,991 pin values and 161 output register values.

```sh
./scripts/verify-sap1.sh /path/to/logisim-evolution-5.1.0dev-all.jar
```

The script looks for the Kotlin standard library in the Gradle cache. Set `KOTLIN_STDLIB_JAR` to specify a different path. The reference run's `comparison.json` and `manifest.json` files record the results and artifact hashes.
