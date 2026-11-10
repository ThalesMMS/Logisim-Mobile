# Output devices against Evolution d58a61e0 (2026-11-07)

Issue #317. Runs on macOS (Apple silicon) with Homebrew OpenJDK 21.0.13, from a clean worktree at `77da354e7f0812248fc14d4b92d8d11cc9adc5d0`.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar` at `77da354e`) | `5376545481b40008dd7b889cfb5af9cedba241845a68d7b3416b9a1558e4d898` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

Command: `scripts/oracle/output-devices/run.sh EVOLUTION_JAR` (seed 317072026, 25 s). [Log](logs/output-devices.txt), with local paths replaced by `EVOLUTION/`, `MOBILE/` and `TMP`. The stack traces in the log are printed by Evolution's sound thread in the 9 cases described below.

| Entry | Result |
| --- | --- |
| `Input/Output-Extra/Buzzer` | PASS: 160,000 tone fields over 20,000 random input states, and 19.2 M PCM samples in 238 runs of Evolution's sound thread. Difference: in 9 runs the desktop thread throws in `System.arraycopy` (smoothed non-sine wave, `4 × cycle < 2 × smooth width`) and stays silent, while Mobile plays the unsmoothed wave. |
| `Input/Output-Extra/DigitalOscilloscope` | PASS: 5.95 M comparisons of trace length, captured cells and clock counter over 36,000 steps. |
| `I/O/Video.factory` (RGB Video) | PASS: 2.37 M pixel and cursor-register comparisons, 80 circuits (10 color formats, 2 reset modes, 4 sizes), 12,800 steps. Mobile accepts pins at Evolution's terminal points. |
| `I/O/RealTimeClock` | PASS: 400 paired readings, milliseconds and seconds, inside the same wall-clock window; 64-bit output at Evolution's terminal point. |
| `Soc/SocVga` | PASS: 902,423 comparisons of mode, size and every displayed pixel at 23 checkpoints over 78,131 bus transactions. |

The two mismatches found on the way were fixed in `77da354e`: Atari colors for undefined RGB Video data, and the SocVga reload from the bus on start, reset and mode change. The scope and the parts that cannot be compared (Noise samples, audio playback, cursor blink, unwritten SoC memory, Evolution's paint timing, injected clock) are listed in [`scripts/oracle/output-devices/README.md`](../../../scripts/oracle/output-devices/README.md).

These runs prove headless JVM behaviour only. They do not cover Android or iOS rendering, sound output or device timing.
