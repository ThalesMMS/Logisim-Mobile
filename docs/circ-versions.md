# .circ file versions and migrations

The codec uses the `source` attribute to interpret historical defaults. The XML container version `1.0` is separate from the version of the application that created the document.

Files without `source` use the current `5.1.0-dev` profile. When importing a file older than `2.6.3`, Mobile converts legacy library, tool, and label references and lists the conversion in the import report. The saved copy uses the `2.7.1` profile and writes historical values when they differ from that profile's defaults. The importer rejects files with components from the discontinued `#Legacy` library or malformed versions before replacing the open project.

| Rule | Interpretation |
| --- | --- |
| `Pin.output` without `Pin.type` | Determines input or output. An explicit `type` takes precedence over the legacy field. |
| `Pin.pull` and `Pin.tristate` without `behavior` | Pull-up or pull-down takes precedence over tri-state, matching the desktop reader. |
| Missing appearance for memories and flip-flops | Classic for a `-HC` source, Logisim Evolution for other supported sources. |
| Missing Enable for Multiplexer and Demultiplexer | Enabled through `3.6.1` and disabled in later versions. |
| Missing Enable for Decoder | Disabled before `2.6.4` and enabled from that version onward. |
| Missing appearance for Splitter | Legacy geometry before `2.6.4`. |
| `FP*` and `IntToFP` components in Arithmetic before `4.1.0` | Resolved through the FPArithmetic library when the corresponding definition is registered. |

`CircProjectImportResult.Success.migrations` lists the rules applied. The component keeps the derived defaults in `implicitAttributes`. Export omits a value when it matches the default of the version being written. The writer records any change made by the user.

Support for a file version depends on the component definitions available in the registry. Missing definitions still cause an import error.

## Verification

`CircFormatProfileTest` covers default selection, legacy pin precedence, and conversion of projects older than `2.6.3`. The corpus in `examples/compatibility/` includes sources `2.7.2`, `3.6.1`, `3.8.0-HC`, and `5.1.0dev`.

Run the desktop reader on the original files and the mobile exports.

```sh
./scripts/verify-circ-compatibility.sh /path/to/logisim-evolution-all.jar
```

The script also includes SAP-1. It compares definitions, component counts, and terminals. For passive connection components, it compares position and physical width. For other components, it also checks terminal direction. Library truth tables and timing require separate comparisons.
