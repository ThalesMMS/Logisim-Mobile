# Headless execution on the JVM core

`dev.logisim.mobile.core.HeadlessCircuitCli` reads a local `.circ` file using the same codec and simulator as the app. Execution uses components supported by the core. Import errors, components without simulation support, and propagation failures stop the command with a diagnostic on `stderr`.

## Input

With JDK 21 and the project's Gradle environment configured, use the `:core:runHeadless` task to run the JVM class.

```sh
./gradlew --quiet :core:runHeadless --args='temporal /path/to/clock.circ --ticks 6'
./gradlew --quiet :core:runHeadless --args='truth-table /path/to/and.circ'
./gradlew --quiet :core:runHeadless --args='stats /path/to/project.circ --circuit main'
```

You can also invoke the class directly with `java -cp`, placing `core/build/libs/core-jvm.jar` and its JVM dependencies on the classpath. The input takes this form.

```text
HeadlessCircuitCli temporal|truth-table|stats file.circ [options]
```

| Option | Contract |
| --- | --- |
| `--circuit NAME` | Selects a circuit by name. Defaults to the main circuit declared in the file. |
| `--ticks COUNT` | Limits temporal mode to `COUNT` clock half-cycles. Accepts integers from zero to `Long.MAX_VALUE`. Defaults to 10,000. Zero captures the initial state. |
| `--load FILE [LABEL]` | Initializes a RAM or ROM by label. Without a label, initializes every RAM without a specific load. |
| `--save FILE` | Writes the final contents of the last reachable RAM in `v3.0 hex words plain`. |
| `--sub OLD NEW` | Resolves a `.circ` library reference using an explicit file. Repeatable, with `-sub` as an alias. |
| `--cancel-file FILE` | Stops when the path starts to exist. An existing file cancels before import. |
| `--terminal` | Uses `stdin` for Keyboard and `stdout` for TTY in place of CSV. |
| `--help` | As the sole argument, prints syntax and exits successfully. |

Unknown options, missing values, and repeated options other than `--sub` and `--load` return an error. Each labeled `--load` needs a different label, and at most one load can omit it. `--ticks`, `--load`, `--save`, and `--terminal` require temporal simulation.

## External libraries

For a project at `/path/to/tests/main.circ` referencing `file#library/adder.circ`, compare the original implementation with another library that exports circuits with the same names and terminals.

```sh
./gradlew --quiet :core:runHeadless --args='truth-table /path/to/tests/main.circ --sub library/adder.circ /path/to/tests/library/adder.circ'
./gradlew --quiet :core:runHeadless --args='truth-table /path/to/tests/main.circ --sub library/adder.circ /path/to/submissions/adder.circ'
```

OLD paths are relative to the input project's real directory and can also be absolute. NEW paths are relative to the CLI working directory. References inside a loaded library are resolved from that library's real directory. Supply another `--sub` for each transitive dependency, with OLD matching the resolved reference path.

The CLI reads only explicitly mapped destinations. It rejects unmapped references, duplicate keys, and unused mappings. The codec validates the project and dependencies before emitting output and leaves the input files intact.

## Temporal mode

The command propagates the initial state, emits a row, and advances the clocks in half-cycles. Each half-cycle uses the project's propagation limit, which defaults to 256 rounds. Input pins use the initial values defined in the circuit.

Temporal and truth-table modes read project simulation options through `SimulationProjectOptions`. `simlimit` or `propagationLimit`, the `gateUndefined` policy, and `memoryStartupUnknown` also apply to headless execution. Ticks are logical, and the configured frequency adds no real-time wait.

The command accepts projects that request random delays through `simrand`, uses deterministic delays, and reports this difference on `stderr`.

After the initial state, the command emits a row whenever an output pin changes, after the batch of events at the same propagation time. The CSV includes every output pin in the selected circuit in file order. Names come from pin labels, falling back to the component identifier for empty labels. Duplicate names or the reserved names `tick` and `propagation_time` return an error.

```csv
tick,propagation_time,clk
0,1,0
1,2,1
2,3,0
```

`tick` counts half-cycles from initialization. `propagation_time` is the simulator's logical delay counter. Bus values are binary strings from most significant bit to least significant bit. Undefined and error values keep the representation from `LogicVector.display`. Fields with commas, quotes, or line breaks are escaped as CSV.

A single one-bit output pin labeled exactly `halt` stops execution if its value is `1` after initial propagation or at the end of a tick. CSV includes all transitions in the final tick and keeps `halt` among the columns. Multiple `halt` pins or a `halt` pin wider than one bit return an error.

Without `halt`, execution continues to the tick limit or cancellation even for circuits with constant outputs. Reaching the limit exits successfully with diagnostic `status=tick-limit ticks=N`. A stop caused by the pin emits `status=halt ticks=N`.

## Logisim 2.7.1 `-tty` compatibility

Use `-tty` with a comma-separated list of modes. The option can come before or after the file.

```sh
./gradlew --quiet :core:runHeadless --args='/path/to/clock.circ -tty table,halt,speed,stats --ticks 6'
```

| Mode | Output on `stdout` |
| --- | --- |
| `table` | TSV trace without a header or time columns. Sorts output pins by position, top to bottom and left to right. Emits the initial sample, then a row at the end of each tick if values changed. Omits the `halt` pin. |
| `halt` | Stop reason, such as `halted due to halt pin`. The `halt` pin also stops execution when this mode is omitted. |
| `speed` | Executed ticks, time in milliseconds, and observed rate in Hz. |
| `stats` | Unique and recursive counts before the trace. Used alone, counts components and exits without simulation. |
| `tty` | Bytes emitted by TTY. Keyboard receives ASCII bytes while the simulation is running, including before EOF. Input and output each accept up to 64 KiB. |

Simulation with `-tty` continues until `halt`, oscillation, or cancellation. Use `--ticks COUNT` to limit circuits that do not stop, as in the example. Oscillation prints the reason and returns code `1`. For external libraries, retain explicit mapping with `-sub OLD NEW`. `-load FILE [LABEL]` is also accepted with `-tty`. Put the circuit before load options to distinguish its path from the optional label.

## Logisim Evolution `-tty` table

Use `--tty-evolution` with `-tty table` for Evolution formatting.

```sh
./gradlew --quiet :core:runHeadless --args='/path/to/inverter.circ --tty-evolution -tty table,csv,hex'
./gradlew --quiet :core:runHeadless --args='/path/to/inverter.circ --tty-evolution -tty table,tabs,binary'
```

Without an output pin named `halt`, the command propagates a fresh initial state for each input combination up to 20 bits. The header lists inputs first, followed by outputs, with each group ordered by pin position. Buses occupy one column, with the width in the header, as in `data[8]`. Empty labels and labels removed as duplicates receive default names. Floating signals use `U` and errors use `E`.

The default format aligns columns with spaces and shows buses wider than six bits in hexadecimal with the `0x` prefix. `csv` separates fields with commas and `tabs` uses tab characters. `hex` shows hexadecimal digits without a prefix and `binary` groups binary digits in fours. If combined, `tabs` takes precedence over `csv`, and `binary` over `hex`.

When the circuit has a `halt` output pin, the command uses a temporal TSV trace without a header. `--ticks`, `--load`, and `--save` apply to the temporal trace.

The command accepts Evolution spellings. `-t MODES` and `--tty MODES` are equivalent to `--tty-evolution -tty MODES` without requiring `table`. `-l` aliases `--load`, `-s` and `--substitute` alias `--sub`, and `--toplevel-circuit` aliases `--circuit`. In the Evolution profile, a trace with a `halt` pin shows floating bits as `U`, the stop reason goes to `stderr`, and `stats` uses the labels `TOTAL (without project’s sub circuits)` and `TOTAL (with sub circuits)`. An unlabeled `-l` in this profile also loads ROMs without a specific load, matching Evolution. Literal `-tty` retains the Logisim 2.7.1 profile.

## Truth table

`truth-table` accepts combinational circuits with up to 20 input bits and 256 output bits. The analyzer rejects clocks and stateful elements, including in subcircuits. It writes CSV in blocks of up to 4,096 rows. Cancellation or an error after the first block can leave partial output.

CSV contains the input columns followed by the outputs. Each bus bit gets its own column, such as `data[7]`. Rows follow ascending binary input order. A completed analysis emits `status=truth-table rows=N` on `stderr`.

## Evolution test vectors

```sh
./gradlew --quiet :core:runHeadless --args='--test-vector main vectors.txt /path/to/inverter.circ'
```

`--test-vector CIRCUIT VECTOR_FILE` accepts the `-w` alias and can come before or after the `.circ`. The command uses the app's vector format and executor, including project simulation options. Combinational rows begin at the initial state. Consecutive rows of the same `<set>` preserve state and advance according to `<seq>`. `--sub` and `--cancel-file` also apply.

CSV records the vector number, physical file row, set, sequence, `PASS` or `FAIL`, expected and observed values, and differences. The command evaluates every row and emits `status=test-vector passed=N failed=M` on `stderr` when finished. It returns `1` if any vector differs.

## Component statistics

`stats` counts components without simulating the circuit. CSV has the columns `kind,library,component,local,unique,recursive`.

- `local` counts components in the selected circuit.
- `unique` counts each reachable definition once, even when it has multiple instances.
- `recursive` counts components again for each instance that contains them.

Input and output pins share the `Pin` row. The counts exclude wires. Each subcircuit appears under its definition name with `kind=subcircuit` and an empty library. The total rows include or exclude these symbols while retaining internal components in the counts. Completion emits `status=stats` on `stderr`.

## Memory images and terminal

`--load` accepts text images with headers such as `v2.0 raw` and the `v3.0` hexadecimal formats. Each image is limited to 16 MiB and 1,048,576 nonzero words. A label selects a single reachable RAM, DualRAM, or ROM, including instances in subcircuits. Matching is case-sensitive. A label that matches no component or multiple instances returns an error before simulation.

Without a label, the image applies to every reachable RAM and DualRAM without a specific load. Each component decodes the image using its own width and capacity. ROMs without a specific load retain the contents declared in the `.circ`. Loads affect only the execution state.

```sh
./gradlew --quiet :core:runHeadless --args='temporal project.circ --load data.hex working --load code.hex firmware --save ram-final.hex'
```

`--save` writes a file after `halt` or the tick limit. The file includes all addresses of the last RAM or DualRAM in circuit order, visiting local memories first and subcircuits afterward. Cancellation or failure leaves the file unwritten. If the project has no RAM or DualRAM, the command returns an error before simulation.

Use `--terminal` to check circuits with Keyboard or TTY, including in subcircuits. This mode accepts circuits without output pins and retains the optional `halt` pin, tick limit, and cancellation.

```sh
printf 'OK\n' | ./gradlew --quiet :core:runHeadless --args='temporal /path/to/echo.circ --terminal --ticks 20'
```

With Keyboard, the command reads finite 7-bit ASCII input up to EOF before the first tick. Each instance receives input in order as space becomes available in its buffer. Use a pipe, redirect a file, or signal EOF after typing input. The command checks cancellation between reads. A blocked read waits for more input or EOF. A TTY-only circuit leaves `stdin` unread.

Output contains the bytes arriving at TTY on an enabled edge, including control characters. At normal termination, trailing text without a newline gets an LF, as in Logisim 2.7.1. Input and output are limited to 64 KiB each. Input beyond the limit or outside ASCII exits with code 2. Output beyond the limit returns 3 and retains bytes already emitted. EOF ends input. Simulation continues until `halt`, the tick limit, or cancellation.

## Output and exit codes

`stdout` contains CSV, the `-tty` report, TTY bytes with `--terminal`, or help with `--help`. `stderr` contains the termination reason for CSV modes or an error. In temporal mode, the command flushes each CSV row immediately and retains only the previous sample to detect changes.

| JVM class exit code | Meaning |
| --- | --- |
| `0` | Statistics or truth table completed, vectors passed, `halt` triggered, or tick limit reached. |
| `1` | Oscillation detected in `-tty` mode or test vector mismatch. |
| `2` | Invalid syntax, unreadable file, import error, invalid selection, truth-table analysis failure, invalid test vector file or memory image, or memory file write failure. |
| `3` | Temporal simulation failure, propagation limit exceeded, temporal simulator diagnostic, or output failure. Output may contain data from before the failure. |
| `130` | Cancellation observed via the file, thread interruption, or API callback. Output may contain data from before cancellation. |

These codes belong to the Java process. When it fails, the Gradle task returns Gradle's own failure code. Tools that consume the exact codes can call the class directly. The `HeadlessCircuitCli.run(arguments, stdout, stderr, cancelled)` API returns the same code without exiting the JVM. The overload with `stdin` takes an explicit input stream.

The command checks cancellation before import, between ticks or vectors, during propagation rounds, and during analysis. Signals that terminate the JVM follow operating system behavior.

## Startup options

`--version` shows the version from `app.properties` recorded in the JVM JAR. Startup options can precede the command or accompany circuit options.

The JVM launcher uses `~/.logisim-mobile/cli.properties`. Set `LOGISIM_MOBILE_CLI_PROFILE` to select another file, for example when testing a reset. The profile stores gate style (`--gates ansi|iec`), the language of startup help and notices (`--locale en|pt`), and the template (`--user-template FILE|empty|plain`). Regional variants such as `pt-BR` are accepted. Inspect settings with `--startup-settings` and restore defaults with `--clear-prefs`.

```sh
export LOGISIM_MOBILE_CLI_PROFILE=/path/to/isolated/cli.properties
./gradlew --quiet :core:runHeadless --args='--gates iec --locale pt-BR --user-template template.circ --startup-settings'
./gradlew --quiet :core:runHeadless --args='--new-project' > new.circ
./gradlew --quiet :core:runHeadless --args='--clear-prefs --startup-settings'
```

`--new-project` writes the effective template XML to `stdout`. For a custom template, the profile stores the validated absolute path and the launcher reads it when generating a project. `empty` generates an empty circuit and `plain` includes default simulation options. Gate style is recorded in the profile and leaves headless report data intact.

The JVM launcher runs without windows. `--geometry WxH` returns code 2 with a diagnostic. `--no-splash` confirms on `stderr` that the host already starts without a splash screen. Android and iOS use their system's geometry and splash screen. Both apps expose preferences and templates in the editor and run independently of JVM launcher arguments.

## Built-in test benches and Evolution file conversion

```sh
./gradlew --quiet :core:runHeadless --args='--test-circuit bench.circ --ticks 10000'
./gradlew --quiet :core:runHeadless --args='--new-file-format input.circ output.circ'
```

`--test-circuit`, or `-b`, runs the `logisim_test_verif` circuit. It must contain a one-bit output pin for each label, `test_bench_done_o` and `test_bench_ok_o`. The command checks the initial state and each clock half-cycle. On first completion it prints `Test bench pass` if the result pin is high, otherwise `Test bench fail`. The exit code is 0 for success and 1 for failure, including when the tick limit is reached without completion. The default is 10,000 logical ticks. The command accepts `--sub` and `--cancel-file` and uses the components and simulation options supported by the JVM core.

`--new-file-format`, or `-n`, reads a project with the mobile codec and writes normalized `.circ` in UTF-8, preserving the file's compatibility profile. The destination must differ from the input. An existing destination is replaced only after a complete, atomic write. The destination directory must exist and its file system must support atomic replacement. The command accepts `--cancel-file`. Input or write errors return 2 and leave the previous destination intact.

## Generate an FPGA project

Use `--test-fpga` or `-f` with a project path, circuit name, and board XML. `HDLONLY` generates a ZIP with HDL, a pin wrapper, constraints, and build files for the board's flow. `--hdl-language vhdl` selects VHDL for the ZIP. Verilog is the default. The `--output` destination must be in an existing directory.

```sh
./gradlew --quiet :core:runHeadless --args='--test-fpga project.circ main BASYS3.xml HDLONLY --output fpga.zip'
./gradlew --quiet :core:runHeadless --args='-f project.circ main BASYS3.xml HDLONLY --mapping mapping.circ --hdl-language vhdl --output fpga.zip'
```

Without `--mapping`, the command uses the circuit's saved map for the selected board. The option also accepts a project containing that map or standalone Evolution mapping XML. The same map can be reused. The command applies the editor's FPGA circuit rules and verifies pins, directions, and clocks declared in the map. The output file must differ from every input file.

To generate a bitstream, specify a companion with the board adapter enabled and authorize the build with `--approve-build`. Pass the token in `LOGISIM_MOBILE_COMPANION_TOKEN`. Use HTTPS outside loopback. The command sends HDL and constraints to the host adapter and writes a ZIP with the verified artifacts after success.

```sh
./gradlew --quiet :core:runHeadless --args='--test-fpga project.circ main BASYS3.xml --companion https://companion.example --approve-build --output bitstream.zip'
```

`stderr` shows the request ID before submission and the job ID after the host accepts it. If the response is lost, repeat the command with the same `--request-id ID`, files, and host. The companion session can reuse the result or report that it expired.

On failure, `stderr` includes `error=fpga stage=...` and the command returns code 2. `--cancel-file FILE` exits with code 130 before publishing the ZIP and requests cancellation of a known active job. Builds and board support follow the companion's capabilities. Programming the board uses a separate operation.
