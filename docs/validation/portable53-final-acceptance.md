# Portable-project acceptance for issue 53

All three issue criteria are satisfied by the source, native flows and reference replays below.

| Criterion | Evidence |
| --- | --- |
| Stable external-library identity; missing sources, cycles and changed files | [CircLibraryCodecTest](../../core/src/commonTest/kotlin/dev/logisim/mobile/core/CircLibraryCodecTest.kt) and the [independent core probe and review](portable53-independent-acceptance/acceptance-review.md). The core suite includes nine passing library-codec cases. |
| Portable import and export with relative library references | Passed bundle and folder flows on Android and iOS. Each generated two actual app exports around a process restart. Storage and Desktop checks validate their relative references and persisted source archives. |
| Definitions, independent instances and reopening across platforms | Four snapshots per flow establish same-ID restart and fresh-ID reopen. Actual exports retain three definitions, two top-level instances and outputs 8 and 3 in the mobile app and Desktop reference. |

| Platform and mode | Native evidence | Duration |
| --- | --- | ---: |
| Android bundle | [Product 59d5606b](portable53-android-bundle-pass/README.md) | 204.06 s |
| Android folder | [Product 59d5606b](portable53-android-folder-pass/README.md) | 217.81 s |
| iOS bundle | [Production 6cef0542, validation 85da031e](portable53-ios-main6cef-pass/README.md) | 356.118 s |
| iOS folder | [Production 6cef0542, validation 85da031e](portable53-ios-main6cef-pass/README.md) | 318.815 s |

The final iOS artifact has bundle-tree SHA-256 `d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080`. The 301 production inputs are identical between its producer and validation commits. Installed fingerprints were checked before and after execution. Both final cases passed six new strict storage/Desktop commands and six replay commands on the copied artifacts.

The [independent acceptance package](portable53-independent-acceptance/acceptance-review.md) retains twelve earlier independent checks and the separate Android-bundle Desktop-before report. Its pending final-iOS attribution is superseded by the completed [6cef native package](portable53-ios-main6cef-pass/README.md). Original failed, partial and preparation-only records retain their original states and artifact identities.

Android evidence remains attributed to its recorded product, APKs and API 35 emulator. iOS evidence uses the recorded iPad simulator and iOS 27.0 runtime. The case durations provide no performance budget or physical-device qualification. JAR plugins retain their separate extension policy.
