# Compare TTL models with the desktop

Run `./scripts/oracle/ttl/run.sh /path/to/logisim-evolution.jar` from the repository. The script builds the mobile JVM core, extracts physical pin metadata from the desktop, and compares 56 combinational and 29 sequential models.

The oracle uses seed `45007483`. Combinational cases include binary combinations, random binary inputs, and unknown/error inputs. Sequential cases apply 512 consecutive input changes per model and feed driven output values back to the next propagation. The monostable 74123 uses its injectable desktop clock, advanced by one millisecond per case. Desktop Java preferences are isolated in a temporary directory.

Results and full traces are written to `outputs/ttl-validation/`. A mismatch fails the command and reports model, row, physical pin, expected value/delay, and actual value/delay. Use `--no-build` after building the exact mobile JAR you intend to compare.

`TtlDesktopOracle.java` reads the reference implementation directly. `TtlMobileCompare.java` invokes `TtlLogic` with the same physical pin values. `generate.py` regenerates `TtlCatalog.kt` and the per-model JVM fixtures from the three reference TSV files. The committed fixtures contain selected combinational cases and complete sequential traces.

The active desktop catalog uses model `74283`, not `7483`. The two part numbers are not treated as aliases because their physical pin arrangements differ.
