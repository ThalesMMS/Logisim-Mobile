# iOS missing-document boundary: Native validation

The seven `IosDocumentErrorBoundaryTest` cases passed on the iOS Simulator ARM64 device `20A4D87D-A2A6-46BD-A889-C4888CE1E152` on 2026-11-05 UTC (2026-11-04 local). The executed source was production commit `8406a0b5dec3834f530cf0ba1b8b5028988d7957` plus test-only correction `d282e16580fb7577b960d68ce1e62d81ae4ec855`. Their integration commits are `fad71c55c290ba894d19b2f2bcbe4b69e4ab4f8c` and `c519385aa334141c23c4ebc26d6a5e0e8f619f92`, respectively; the execution pins above identify the tested source.

| Run | Passed | Failures | Errors | Skipped | Gradle exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| `8406a0b5` | 6 | 1 | 0 | 0 | 1 |
| `d282e165` | 7 | 0 | 0 | 0 | 0 |

The first run is preserved unchanged. Its only failure expected the composed filename `ausente {name} ação.circ`; Foundation returned the decomposed form `ausente {name} ac\u0327a\u0303o.circ`. The typed missing-document key was already correct. Commit `d282e165` changes only that assertion to the literal Foundation filename; production behavior is unchanged. The passing rerun completed in 3m55s; its XML records 0.015s of test execution.

Coverage includes synthetic Foundation missing-file codes 4/260, unrelated domains, permission errors, literal filename substitution, a real absent-file read, and saving a deleted bookmarked file without recreating it or reporting a conflict. This gate does not establish Save As or Files picker UI behavior, and does not identify the NSError code of any earlier UI receipt.

Artifacts are retained locally in `outputs/native-ios-document-missing-<pin>-17e/`, including `gradle.log`, `result.json`, and `test-results/TEST-iosSimulatorArm64Test.dev.logisim.mobile.IosDocumentErrorBoundaryTest.xml`. SHA-256:

| Run | Artifact | SHA-256 |
| --- | --- | --- |
| `8406a0b5` | `gradle.log` | `67c180ba215a0bb93663026de461c21a242a27ff1bc4270f047ce74271b903f9` |
| `8406a0b5` | test XML | `baa98dc7ad7854da42fa27eaaa14c061490424dc492cb649fd6e181f534bfdfb` |
| `d282e165` | `gradle.log` | `2c176ad345511d537fd6d2e15c9cc01507c938eee19906ef090a70c0061b52f2` |
| `d282e165` | test XML | `a46b6c08125d16b9f40f2ab7aae69884ca7b8a64468a8ccace24b8b6c615d704` |

To reproduce from `d282e165`, configure JDK 21 and the Android SDK environment, reserve the simulator, and run this equivalent of the executed `/tmp/logisim-gradle-serial.py` wrapper. It uses the same global Gradle lock and exact task/filter/device:

```sh
python3 - <<'PYTHON'
import fcntl
import subprocess

with open('/tmp/logisim-gradle.lock', 'w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    subprocess.run([
        './gradlew', ':composeApp:iosSimulatorArm64Test',
        '--tests', '*IosDocumentErrorBoundaryTest*',
        '-PiosTestDevice=20A4D87D-A2A6-46BD-A889-C4888CE1E152',
        '--console=plain',
    ], check=True)
PYTHON
```

The simulator lease was explicitly released after XML verification. No production app build or application UI run was part of this gate.
