# Android Inspector Beta validation

The dedicated #21 Inspector case passed once on Android API35 emulator `emulator-5558` with English UI. The numeric width field containing 0 held input focus with the software keyboard shown. Width 0 disabled Apply and showed its specific validation message. Cancel preserved the complete exported XML byte-for-byte. Width 4 changed only the known input Pin width, and one Undo restored the original width 1 XML byte-for-byte.

The exact APK producer is `85da031e4ed11474c2e763976392729810ef142d`. Its producer manifest declares production sources identical to `6cef05429cf66be23569aaebc3c72950cf3fe53a`. The harness was frozen at `5cb2fead2743cbba84f0efbad51e8bca822c7f05`, with Inspector source SHA-256 `74e686a0448e4e6c29729f19cbf04c67922368bab65ebb69a7d238004582f40c`. The retained harness copies and contract hashes are listed in the [receipt](android-inspector-beta-85da031e.json).

All three installed APK hashes matched before and after the case:

| APK | SHA-256 before and after |
| --- | --- |
| App | `67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c` |
| Test | `c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882` |
| Snapshot helper | `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387` |

The [width 4 SDK snapshot](artifacts/android-inspector-beta-85da031e/focus-snapshot-34d6ffb3e13d482cab496c5a387c3124-25.xml) records numeric value 4 and Apply enabled, with its original nonce and instrumentation receipt retained. The evidence bundle also contains the manifest, result, observations, verification, four exported XML documents, focused and rejected PNG/XML captures, and final case capture.

This qualification applies to this dedicated case and exact APK triplet on the API35 emulator. Physical hardware, multiselection, later main builds, other cases and performance are outside its scope. The device was returned after terminal PASS. This receipt commit copies only existing evidence; it performs no new ADB, GUI, build, source change or retry.
