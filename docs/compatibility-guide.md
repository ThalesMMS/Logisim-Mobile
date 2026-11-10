# Check compatibility

Logisim Mobile shares the `.circ` format and some behavior with Logisim Evolution. Check the [parity matrix](parity.md) before relying on a library, option, or integration. Each row links the feature to tracking issues and to the corresponding sources and tests.

The [validation record](validation.md) distinguishes execution, compilation, and tests for each platform. A catalog definition allows you to create or import that component type. Individual attribute combinations and desktop projects require their own verification.

## Read the matrix

| Status | Meaning |
| --- | --- |
| Implemented | A support claim includes the commands, tests, and hashes required by the manifest. Check its scope and limitations. |
| Partial | The matrix records code or limited support. Check which operations and configurations were exercised. |
| Missing | The inventory has no record of a supported implementation. |
| Decision needed | Integration depends on an explicit architecture or product decision. |
| Not applicable | The feature falls outside the stated scope of the mobile product. |

The matrix is generated from the repository's records and manifests. When sources change, older evidence may cease to support an implementation claim. Use the matrix revision shipped with your installed version.

## Open desktop files

The reader validates the project before replacing the open circuit. An unsupported component, attribute, or dependency can prevent import. The error message identifies the failure. Keep the original file and check the feature in the matrix. Before removing any XML to make a file open, verify the effect on the circuit.

A project can contain several circuit definitions, metadata, and references between instances. Import, export, and reopen are separate operations from running the program. To verify a project, reopen the exported copy and compare the inputs, results, and behavior you use.

The [examples](../examples/README.md) cover combinational logic, sequential memory, buses, hierarchy, ROM, SAP-1, and driver conflict. Their expected results cover the recorded stimuli. The [differential corpus](corpus.md) documents the comparison with desktop.

## Convert a project older than 2.6.3

Open the file in Mobile and check the import report. Use **Save as** to create a `.circ` copy in `2.7.1` format and verify the main circuit, connections, and results. If conversion fails, open the file in desktop Logisim to review the warnings and use **File → Save As** to create a separate copy. [Original Logisim 2.7.1](https://sourceforge.net/projects/circuit/files/2.7.x/2.7.1/)

For a self-contained XML file, use JDK 21 to run the same conversion on the command line from the repository root.

```sh
java -Djava.awt.headless=true -cp /path/to/logisim-generic-2.7.1.jar \
  scripts/ConvertLegacyCircuit.java old.circ converted.circ
```

The converter verifies the official JAR hash and uses its reader and writer to rewrite the project. This applies format migrations such as remapping components from the old Base library to Wiring. Choose a new destination. If the project has external `.circ` libraries, JARs, an encoding the XML reader cannot accept, or warnings from the original Logisim, use the UI conversion to review those dependencies and warnings before saving.

## Values and time

Buses have explicit widths. A bit can be `0`, `1`, unknown, or error. Resolution happens per bit, so part of a bus may be valid while another part has a conflict. Check input and output widths before comparing numeric results.

The engine uses events with delays. The clock **Step** and propagation **Event** controls advance different parts of execution. An intermediate output can change before the network settles. To compare with desktop, use the same initialization, input sequence, and clock policy. See the timing evidence linked to [issue #29](https://github.com/ThalesMMS/logisim-mobile-dev/issues/29).

## Files, integrations, and distribution

Android imports and exports through the system picker and shares copies. iOS and iPadOS also support coordinated external editing. Providers can revoke access, move files, or report conflicts. Validation of a local file covers that scenario. Track provider tests in [#25](https://github.com/ThalesMMS/logisim-mobile-dev/issues/25) and [#26](https://github.com/ThalesMMS/logisim-mobile-dev/issues/26).

External tools for HDL synthesis, cosimulation, Tcl, and FPGA have their own contracts and permissions. Confirm tool installation and board programming separately from the presence of an app panel. See the [integration policy](interop-policy.md), [companion protocol](companion-protocol.md), and [HDL](hdl-export.md) and [FPGA](fpga.md) limits.

Screen reader accessibility, physical devices, power consumption, and signed distributions require separate evidence from the examples and automated tests. Track the full scope in [Epic #1](https://github.com/ThalesMMS/logisim-mobile-dev/issues/1).
