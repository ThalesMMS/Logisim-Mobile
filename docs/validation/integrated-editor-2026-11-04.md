# Integrated editor validation — 2026-11-04

Source: `f6e172040a7b1b7c173432bc9e51fd42e17e9cb6`.

- Core JVM: **926 tests, zero failures, errors or skips**. Produced at `87ab3ac58ed9135cdcf810a7c7e51a1d05e92025`; the core tree is unchanged through the source revision above.
- Editor Android host: **385 tests, zero failures, errors or skips**.
- Android debug APK assembly and Compose iOS Simulator ARM64 compilation passed.
- APK SHA-256: `7bd7caf5ef0f4e6a759c7cd3e8f2e3b4ff91f25e877c378db32bb36f51050f43`.
- Core JVM JAR SHA-256: `ad9fbcc1df1e5491804979e33731d2aa66fb18605a84b6a0b416aa69dfba62ad`.
- Python checks: 31 script tests, 27 tools tests and 33 companion server/API tests passed. The four legal/source-archive tests passed again after recording the distribution scope decision.

The editor checks include workspace actor ownership and late acknowledgements, asynchronous imports, read-only command availability, input cleanup during transactions, symbol hit geometry, preference isolation, toolbar overflow layout and FPGA programming session state.

The full integrated companion suite also completed: **151 cases, zero failures, two optional synthesis skips** (ECP5 toolchain and GHDL synthesis are not installed). Authentication, TLS, subprocess cleanup, Tcl, HDL co-simulation and Telnet replay cases executed. Receipt: `outputs/implementation/integrated-companion-full-green.log`.

## Acceptance scope

Issues #8, #9, #14, #15, #17, #18, #22, #24, #30, #47, #50, #51, #52 and #57 have completed their three acceptance criteria and dependencies in this revision. The supporting checks cover wire topology/atomic history, fragment identity and boundary connections, translation validation, executable examples, license/source inventory, companion trust boundaries, live circuit navigation, indexed command availability, navigable diagnostics, vector replay, memory formats/session editing, combinational equivalence, bounded trace retention and HDL synthesis/equivalence fixtures.

Durable instance identity/restoration remains tracked in #10/#56. Physical memory/energy measurements remain tracked in #48; the trace tests prove retention bounds, not physical-device RSS. Full platform interaction suites, accessibility and portable-project mounting remain open.

## Runtime evidence and limits

The isolated Android application built from `aaa1f62bb3a576d7b45404c111491dd685f33dcb` reopened the same Library/Load SAP-1 scenario that previously caused an application ANR. The process remained PID 8614 and no new ANR was recorded. Exported XML contained exactly seven circuits, 156 components and 318 wires. Its installed APK SHA-256 was `39103ec1ab40d88b153442680c30c54ad59dc64e3146529ea666e8435ede9f26`. This proves that regression on that revision; the complete platform scenario suites remain separate gates.

The desktop compatibility replay reopened the six compatibility fixtures and SAP-1, preserving 184 terminal geometries. The vector replay compared 14 desktop/mobile rows byte for byte, including two intentionally failing vectors with equal results.

The latest complete Native test attempt was not green: nine fixture failures were found and corrected, then verified on JVM. A complete Native rerun and execution of the newly built iOS host remain pending. Successful compilation is not an iOS runtime or physical-device validation. Releases #62/#63 are not planned by user decision.

## Local receipts

Ignored execution artifacts are under `outputs/implementation/`: `portable-layout-integration.log`, `editor-native-integration-rerun.log`, `integrated-scripts-tests.log`, `integrated-release-tools-tests.log`, `integrated-companion-api-tests.log`, `circ-compatibility-current-receipt.json` and `legal-scope-tests.log`. Vector artifacts are under `outputs/vector-oracle/2026-11-04-67f91efe/`. Android runtime evidence is retained in the `android-e2e-final` worktree under `outputs/android-e2e/aaa1f62-sap1-anr-regression/`.
