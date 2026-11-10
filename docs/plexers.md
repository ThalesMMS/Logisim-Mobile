# Plexers

`PlexersLibrary.definitions` provides the five desktop component identities in `#Plexers`. Each definition owns typed attributes, terminal geometry, and combinational behavior. `PlexersLibrary.install(registry)` replaces the older Multiplexer and Decoder placeholders and adds the other entries.

| Component | Data width | Selector | Enable | Disabled output |
| --- | --- | --- | --- | --- |
| Multiplexer | 1–64 | 1–8 bits | Optional, default absent | Zero or floating |
| Demultiplexer | 1–64 | 1–8 bits | Optional, default absent | Zero or floating |
| Decoder | 1 | 1–8 bits | Optional, default present | Zero or floating |
| Priority Encoder | One bit per input | 1–8 output bits | Always present | Zero or floating |
| Bit Selector | 1–64 input and group bits | Derived from group count | None | Out-of-range groups are zero |

Multiplexer, Demultiplexer, and Decoder treat an undefined enable as enabled. A connected error-valued enable produces error outputs. Demultiplexer and Decoder can leave unselected outputs floating with `tristate=true`. Priority Encoder selects the highest input that is exactly high; unknown and error-valued data inputs do not assert a priority request. Bit Selector returns unknown for both unknown and error-valued selectors, preserving the desktop behavior.

`selloc=bl` and `selloc=tr` position selector pins. All four `facing` values preserve the desktop terminal ordering. Multiplexer supports `size=20` and `size=40`. The definitions use the desktop delay of three propagation units.

The version-default callback retains enable pins for Multiplexer and Demultiplexer through source version 3.6.1. Decoder enables its enable pin starting at source version 2.6.4. These defaults do not add attributes to the original file data.

## Verify the module

The 26 focused tests include importing all five component identities, running them through the event simulator, exporting and reopening the project, and running the reopened circuits. Historical enable defaults are checked without adding attributes to the saved XML.

Run the tests:

```sh
./gradlew :core:jvmTest --tests '*Plexers*Test'
```

The independent oracle invokes `propagate` on the actual desktop component factories, then compares the outputs and delays with `PlexersLogic`. It also compares every terminal and bounding box with the registered layouts:

```sh
./scripts/oracle/plexers/run.sh /path/to/logisim-evolution-5.1.0dev-all.jar
```

The oracle covers selectors, enables, tri-state outputs, 1/8/32/64-bit data, undefined values, all orientations, selector locations, sizes, and 1,920 geometry configurations. The seed is fixed to make mismatches reproducible.
