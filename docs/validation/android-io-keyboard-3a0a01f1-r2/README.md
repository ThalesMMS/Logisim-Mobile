# Android keyboard and TTY case passed

The selected `keyboard-tty` case passed in the mounted Android editor in 223.79 seconds on emulator `emulator-5556`, API 35, with the English UI. This receipt qualifies that case only. Issue #44 remains partial.

| Artifact | Retained identity |
| --- | --- |
| App producer | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Equivalent production source and helper source | `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c` |
| Test driver | `b56aa75e776477833cc038f8d6cb5ea28d1f598d` |
| App SHA256 | `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| Test APK SHA256 | `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Helper APK SHA256 | `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |

The local APKs matched their recorded hashes, and the original preflight checked all three packages and instrumentation targets before UI operations. The installed hashes match before and after the case. The retained source and production-equivalence record distinguish app source 3a from helper source 5c; the helper is not attributed to the app build. No APK, JAR or TAR is included.

The fixture, contract, oracle and targets preserve the original I/O expectations. Nineteen baseline probes passed. The real keyboard field retained `AB`, visible in SDK captures 5 and 6. Connected data then exposed A, B and an empty buffer as the clock advanced. The two control actions exposed ASCII 8 and 12 with availability 1. Visual review of the original PNGs confirms TTY rows A, AB, A after backspace and an empty screen after form feed, at Steps 2, 4, 6 and 8. This is human review of these four images, not a pixel classifier or whole-UI parity claim.

The actual control records contain one Backspace tap and no Backspace navigation. Form feed first had its label clipped at the right boundary. Exactly one horizontal navigation was recorded, followed by a different retained tree with an interior label and one tap. The points were derived from observed label rectangles while retaining the enabled clickable owner separately. The saved geometry and action trees allow offline checking of scope, ownership and containment. `commandCompleted` means the command returned; the connected probes establish the resulting codes, rather than an assertion that a MotionEvent was observed inside the app.

The dedicated selector uses one absolute 20-second budget per control, including observation, optional navigation and tap, with another check before dispatch. That is the retained source policy and its test coverage. Per-action start/end timestamps were not retained, so this receipt does not claim measured 20-second control latency or infer an unrecorded interaction timeline.

After configuring `buflen=4`, the retained runner sent `ABCDE`; captures 24 and 25 show exactly `ABCD`. The complete capacity export has 54 components and 64 wire segments. The archived oracle requires the only expected edit to be IO_KEYBOARD buffer length 16→4, preserving all other component attributes, positions and electrical connections. The export normalizes the from/to ordering of 18 wire segments without changing their undirected geometry or electrical connections. The last exported document is byte-identical to that capacity export. Capacity restoration to 16 and the Home/End controls are not part of this case.

All 26 SDK captures were complete, with unique nonces and app PID 25265 observed before and after each capture. No typed ROOT_UNAVAILABLE or INCOMPLETE_TREE result occurred, so this run does not qualify recovery of those paths. These sampled identities do not establish continuous process lifetime. The CLI group did not retain a pre-run ANR baseline; unchanged ANR status is not asserted. The isolated app was force-stopped only after preserving the final state and readback, explaining the empty post-cleanup PID.

The earlier R1 remains failed. Its captured selector obstruction and this successful case do not retroactively reconstruct every R1 poll. No physical input ergonomics, cold persistence, performance budget or complete issue qualification is inferred.

## Offline replay

The bundle contains 149 raw files and five historical oracle inputs under `oracle-snapshot`, plus their pinned hash manifest. These snapshots are historical proof inputs, not replacements for current repository sources. The verifier authenticates the pin, manifest and bytes before loading the archived oracle. It requires no other checkout, Git object, APK, Android process or network service.

```sh
python3 -B docs/validation/android-io-keyboard-3a0a01f1-r2/verifier.py
python3 -B -m unittest discover -s docs/validation/android-io-keyboard-3a0a01f1-r2 -p test_verify.py -v
```

`inventory.json` covers the 155 raw and snapshot files. `receipt.json` records the bounded claim and links to the evidence. The verifier and negative controls check the actual documents, probe descriptions, control geometry, field text, protocols and identities. They do not replace the visual review of the retained TTY images.

The original activity dump contains one trailing space. The local `.gitattributes` exception applies only to that raw dump, preserving its bytes without disabling whitespace checks for source or other evidence.
