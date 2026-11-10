# Integrated portable workspace validation — 2026-11-04

Integrated revision: `affe74e43fde0de45d875f32b0acdb76771c88fa`.

| Execution | Tests | Failures / errors / skips |
| --- | ---: | --- |
| Core JVM | 963 | 0 / 0 / 0 |
| Core iOS Simulator ARM64 | 876 | 0 / 0 / 0 |
| Editor Android host | 427 | 0 / 0 / 0 |
| Editor iOS Simulator ARM64 | 430 | 0 / 0 / 0 |

These totals combine successful task receipts. The first combined command could not run the Native editor suite because its default Simulator was shut down; the later explicit-device execution completed successfully.

The core and Android checks ran at `04a0b34a38689932a3f3aaed7f4ea5cb6b63c8db`. Later production changes affect only the iOS directory-opening flags; all editor Native tests were rerun after that change. The core production tree is unchanged from the artifact producer `2e20e5f84213657f0e2daa493abd3b30b5cb3671`.

- Core JVM JAR SHA-256: `01073771c0c7643efa38d7b25d8c6905d31651f27538e9a6a8f4cd82a75720c4`.
- Android debug APK SHA-256: `80e6e3669237a160a99add5379b5afc6be58f785192b89ecc73404a8287fefc8`.
- The Native editor suite ran on the booted iPhone 17e Simulator `20A4D87D-A2A6-46BD-A889-C4888CE1E152`. Raster PNG/PDF, SoC rendering, document actions and directory access executed successfully.
- Directory tests prove real filesystem access in the Simulator Documents directory. They do not replace a user-facing Files picker test.

Coverage added in this integration includes persistent component IDs, reserved instance separators, framed viewport keys, V1/V2/V3 to V4 migration with source attachments, safe stored-selection restoration, retention of recovery notices, prepared-copy acknowledgement, directory adapters, and export guards for preserved raw XML versus parsed read-only projects.

The directory test exposed an invalid Darwin flag combination. Removing the redundant `O_NOFOLLOW` while retaining `O_NOFOLLOW_ANY` fixed the root open; descendant checks, alias rejection and identity validation remain in place. Fifteen focused directory tests and the complete Native editor suite passed.

The final desktop replay and its hashes are preserved in [oracle-replay-2e20e5f](../parity-evidence/oracle-replay-2e20e5f/README.md): seven fixtures, 222 observations, no differences, plus three temporal fixtures with 132 records per mode. The inventory maps 200 desktop components to 201 mobile definitions and five additional tools, with 95 aliases. All 205 parity states remain partial; mapped implementation and test references do not establish full UI or physical-device parity.

The complete platform UI suites, Files picker flows, accessibility matrix and performance/hardware gates remain separate work. Releases #62/#63 are not planned. The hosted CI receipt for the earlier validated source remains in [ci-2026-11-04-cefaaf83.md](ci-2026-11-04-cefaaf83.md); the updated ARM gate now also boots an explicit Simulator and runs the Native editor tests.

Local ignored logs: `outputs/implementation/portable-v4-native-rerun.log`, `outputs/implementation/complete-compose-native-validation.log`, and the four modules' JUnit reports under `build/test-results/`.

## Completed acceptance reviews

Issues #2, #3, #10, #11, #13, #20, #23, #37, #38, #49, #54, #55, #56, #60 and #64 have implementation, tests and evidence for their three acceptance criteria. Reviews checked dependency order: codec before extension policy, annotations before custom symbols, workspace/run control before preferences/recovery, and SoC before CPU tooling.

This scope includes the inventory and differential runners, viewport framing, versioned storage, hosted CI, full-project codec, embedded annotations, static extension policy, run/lifecycle control, Tcl, custom symbols, preferences, recovery, SoC and the supported CPU instruction subsets. It does not close the remaining platform interaction, accessibility, portable-project UI reopening, performance or physical FPGA gates. The remaining HDL scroll issue is tracked separately; the backend tests do not establish compact-screen reachability.
