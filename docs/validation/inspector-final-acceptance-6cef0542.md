# Typed Inspector final acceptance

The remaining phone check has completed. The evidence supports closing [issue #21](https://github.com/ThalesMMS/logisim-mobile-dev/issues/21) for production source `6cef05429cf66be23569aaebc3c72950cf3fe53a`. This addendum verifies the final Android Beta and iPhone receipts against the [earlier criterion review](inspector-acceptance-ab1b04cf.md). It ran no native scenario, build, ADB or Simulator command.

| Criterion | Final evidence | Review |
| --- | --- | --- |
| Typed controls, validation and specific messages | The65 retained behavioral cases include16 editor/model cases covering declared types/defaults, specific errors, localization, mixed schemas and readonly data. Both final native cases observed width0, its specific error and disabled Apply. | Satisfied. |
| One undoable change to drawing, terminals and simulation, preserving connections | The retained core/editor cases verify atomic batches, named-terminal routing, geometry/simulation updates and Undo. The actual final applications exported width4 as the sole project change and restored the complete baseline with one Undo. | Satisfied. |
| Phone Inspector, focus, invalid values and supported multiselection | Both final phone-sized platform cases passed with software keyboard and numeric-field focus. The earlier iPad A4ab case supplies tablet coverage. Supported multiselection is tested through real `EditorState`/schema validation: all selected components change together, invalid edits preserve the project, and Undo restores the whole selection. | Satisfied within these observations. Native multiselection gestures are not claimed. |

## Final native receipts

| Receipt | Artifact and result | Independently checked observations |
| --- | --- | --- |
| [Android Beta](android-inspector-beta-85da031e.json) | Producer `85da031e`, production-identical to6cef; API35 phone emulator; **PASS226.75s**. App/test/snapshot SHA prefixes `67e81d69`/`c439cef1`/`2f17d8af`. | All32 retained artifact hashes and eight frozen harness sources match. Before/after installed identities agree. Focused0, visible numeric keyboard, specific error and disabled clickable Apply are present in PNG/XML. The valid4 snapshot has a consistent nonce/PID, focused field and enabled Apply. Baseline/Cancel/Undo are byte-identical; valid XML changes only width1→4. |
| [iPhone Phone005](ios-inspector-phone005-pass.json) | Production6cef; iPhone17e Simulator,390×844pt; **NATIVE_PASS173.818s**,1 selected test/zero failures; suite qualification remains **PARTIAL**. Bundle-tree SHA`d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080`. | The portable offline verifier passed for25 retained raw files. Separate byte comparison confirms481-byte baseline/Cancel/Undo equality and one changed byte at offset387, width1→4. PNG/tree show0, error, disabled Apply and software keyboard. The exact same field is guarded through1→empty→0 and1→empty→4, using one actual software Delete per transition. Full48pt Apply targets end11.67/25.67pt above `inputView`. Archived installed identities agree before/after. |

The Android baseline/Cancel/Undo SHA is `a7538c0d619d18e700050dc52f56fc67af6b4f124ebd379deffc04c4c0160480`; the corresponding iPhone SHA is `2d0c873e42b8a636a73aec2859a069a036983ae33420de501f36cb82b4b1bc71`. Neither comparison ignores other project content.

## Source mapping and limits

The65 archived behavioral cases were executed atab1 and retain their original source identity. `PropertyEditorModel`, `TypedPropertyInspector`, `CircuitEditor`, and the `EditorState.updateSelectedAttributes`/`change` bodies are byte-identical fromab1 to6cef. The later simulation-request ownership/queue integration changed other `EditorState` and `App` code; this review does not describe those entire files as unchanged. The final native cases exercise the integrated6cef application. Between the Android producer85da and6cef, the only Git delta is a host-test thread-identity assertion; no production source differs.

Phone005's selected harness is `1b5f5e424a4dd7c28fbdfbc37567d7d3b952c11c`, SHA`ac8faea7e3deac9d2e55e773331190c200d61b766f92cf8401a6ad1ad30716dc`. Its Files Import Copy setup and private two-call single-digit policy preserve the original invalid/valid/XML/Undo assertions. They do not qualify Select All, Cmd+A or a general replacement helper. Root-Attributes focus text is a version-dependent diagnostic; visible keyboard, exact values, actual Delete actions and runtime postconditions provide separate observations.

[Phone001–004 remain failed receipts](artifacts/ios-inspector-setup-failures/README.md). Phone005 does not rewrite or retrospectively promote them. This issue-specific acceptance does not certify the whole native suite, an accessibility audit, physical hardware, performance or later application revisions. No remaining #21-specific gap was found in the reviewed source and evidence. Issue closure and publication remain separate actions.
