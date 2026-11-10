# Integrated core and editor validation

The integrated core passed **810 JVM tests and 754 iOS Simulator ARM64 tests**, with no failures, errors, or skips, at code revision `518840e1eb6491029958ab7d18c4fe96b5db08af`.

The editor then passed **257 Android host tests**, Android APK assembly, and Compose iOS Simulator ARM64 compilation at `c0fabbd8b811edf860b7b24d96a1524bebf656c5`. The later changes fix editor fixtures, preserve demo defaults, connect native catalog hosts, and bound grid drawing. They do not change the core or the temporal corpus tested above.

Commands:

```sh
./gradlew :core:jvmTest :core:iosSimulatorArm64Test -Pkotlin.incremental=false
./gradlew :composeApp:testAndroidHostTest \
  :composeApp:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug \
  -Pkotlin.incremental=false
python3 -m unittest discover -s scripts/tests -v
python3 -m unittest discover -s tools/tests -v
```

The script checks passed 27 tests; identity and release preflight checks passed 12 tests. Builds used the shared local Gradle lock. The Android APK has package `thalesmms.logisim.mobile`, version `0.2.0`, build `2`, and SHA-256 `a0951855582d4ba2ecd5ad1cd3f3c6cb08b9585fa0fcb9a671dce769eb6bd94e`. The separately built iOS simulator host confirms the same identifier, version, and build. That host's runtime smoke used the earlier `0beefbd` source with native host deltas; it is not claimed as a runtime test of `c0fabbd`.

## Acceptance evidence

| Issue | Implementation and behavioral evidence |
| --- | --- |
| [#6](https://github.com/ThalesMMS/logisim-mobile-dev/issues/6) | `CircuitProject` and `CircuitDocument` retain stable IDs, main circuit, metadata, and references. `CircuitProjectTest`, `CircProjectCodecTest`, `ProjectManagementTest`, and `ProjectEditorStateTest` cover create, rename, reorder, remove, undo/redo, legacy metadata, invalid names, missing references, and refusal to delete used definitions. IDs survive export and reimport. |
| [#16](https://github.com/ThalesMMS/logisim-mobile-dev/issues/16) | `app.properties` owns the identifier and version for both hosts. Generated Android resources and Apple asset catalogs contain the original icon, with provenance in `legal/provenance.json`. `tools/tests/test_app_identity.py` checks generated resources, RGB Apple dimensions, Android variants, and consistent metadata. APK badging and the built iOS host both confirm the configured identity. |
| [#19](https://github.com/ThalesMMS/logisim-mobile-dev/issues/19) | `CircuitNetworks`, `CircuitTopology`, and `CircuitDiagnostics` elaborate terminals, per-bit widths, junctions, crossings, splitters, tunnels, conflicts, and navigable diagnostic origins. `CircuitNetworksTest`, `CircuitDiagnosticsTest`, wire editing tests, and hierarchical feedback tests assert connectivity across edits, split/normalization, and instance boundaries. |
| [#29](https://github.com/ThalesMMS/logisim-mobile-dev/issues/29) | `CircuitSimulator` retains an ordered event queue, delays, simultaneous capture, reset, limits, and oscillation detection. `TemporalOracleTest` passed all four tests on JVM and Native: 132 desktop records across simultaneous registers, derived-clock feedback, and RAM feedback. Both settling and persistent event stepping compare actual times and probe bits. See `docs/temporal-oracle.md` and `examples/corpus/temporal/provenance.json`. UI publication batches observations through `SimulationRunController`. |
| [#39](https://github.com/ThalesMMS/logisim-mobile-dev/issues/39) | Subcircuit bindings retain port identities, widths, and instance paths. `ProjectSimulatorTest.twoSubcircuitInstancesKeepIndependentRegisterState` clocks one instance to `0x55` while the other remains zero, then clocks the second to `0xaa` without changing the first. Nested registered state, custom 8-bit ports, recursion rejection, hierarchy examples, and SAP-1 tests cover propagation through boundaries. |

These five issues passed a separate requirement-by-requirement source and assertion review. This report does not close unrelated UI, companion, release, or hardware gates. In particular, an additional companion run encountered four resource-monitor timeouts during host overload; its other 77 tests passed. Those failures remain tracked separately and are not represented as a passing companion suite.

Physical devices, FPGA programming, signed distribution, and final complete user-flow matrices remain separate acceptance work.
