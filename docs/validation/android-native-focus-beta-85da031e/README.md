# Android native focus on Beta 85da031e

The single `focus` case passed on API 35 emulator `emulator-5558`. The native case took 23.281 seconds and the wrapper took 28.006 seconds. The result qualifies this selected case only; the mixed-input suite remains partial.

The installed editor was Beta producer `85da031e`, production source `6cef0542`, with APK SHA256 `67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c`. Only the test APK was replaced, using producer `f4f72c6d`, original test patch `4a1658d7`, SHA256 `04a4fdecd9dfdae6577ce040a7595e8c96bb62ac821080ee6faa0f1de7cde52f`. The snapshot APK remained `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387`. All three installed hashes were measured after the case and remained equal to their preflight values. No app reinstall or data reset was performed.

The full XML exports establish these transitions:

- The initial fixture contains three components and two wires.
- After the focused text-field shortcuts and Apply, only the original NOT label is empty. All component IDs, geometry, positions and wires remain equal to the fixture.
- After focusing the canvas and sending Ctrl+D, the only added element is NOT `c1` at `(220,120)`. The original `mi-not` remains at `(200,100)`, both labels are empty, and both original wires remain unchanged.

The complete frame proof retained window `10673` and canvas `[0,647,1080,2119]`. Both 126-by-126 anchor rectangles moved by `(0,-27)`. The focus-only observation therefore derived `(697,1294)` from the original observed NOT point `(697,1321)`, after the model guard had verified zero world displacement and the exact empty label. CANCEL and mixed retain their stricter viewport guards. No input event, deadline or document oracle was changed. The record contains one session record, seven successful foreground guards and 20 injected events. All 20 injected events returned accepted.

The ANR dump remained byte for byte equal. Android stopped the instrumented process at completion; exit info explicitly records `finished inst` for PID 9610. No post-run stable PID is claimed, and no recovery launch was performed.

The first read-only preflight guard searched for `mCurrentFocus` in `dumpsys window windows`, which omitted that field. It stopped before any install or native attempt. A fresh activity dump then confirmed both the exact resumed MainActivity and its current focus. This correction is retained in the preflight receipt.

Dimensions was executing concurrently on emulator 5556. This receipt makes no performance or physical-device claim. The emulator 5558 lease was explicitly returned after the one native run and read-only identity checks. No second native attempt was made.

`receipt.json` maps retained raw files and their SHA256 hashes. `raw/run` includes the complete native result, protocol, XMLs, screenshots, bounded readiness trace, source files and post-run exit info. `raw/preflight` retains compiled manifests and install evidence. `raw/build` retains the test-only assembly and local gates. No APK or JAR is committed.

The captured activity dump retains its original trailing whitespace. A local Git attribute disables whitespace lint only for that raw file, so its bytes and readable diff remain preserved.
