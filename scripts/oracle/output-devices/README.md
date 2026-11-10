# Output-device oracle

`run.sh` drives Evolution's classes and the Mobile core with the same inputs in one JVM and compares the result. It covers the five entries of #317:

| Section | Evolution side | Mobile side | Compared |
| --- | --- | --- | --- |
| `buzzer` | `Buzzer.propagate` through an `InstanceState` proxy; its own sound thread builds the clip | `BuzzerTone.fromInputs` and `BuzzerTone.pcm` | Every field of the tone (enable, Hz, volume, waveform, duty, channels, smoothing) for 20,000 random input states, including unknown and error inputs, both units and volume widths 1–32. The PCM clips of 238 tones, sample by sample, and their sample rate |
| `oscilloscope` | `DigitalOscilloscope.propagate` and its `DiagramState` | `ExtraOscilloscopeState.sample` | Trace length, every captured cell and the clock counter after each of 36,000 steps (1–32 inputs, 4–35 states, unknown and error clock, enable and clear) |
| `video` | A real `.circ` with an `RGB Video` and one Pin per terminal, simulated by Evolution | The same file read by `CircCodec` and simulated by `CircuitSimulator` | Every pixel and the cursor registers after each of 160 random steps, for the 10 color formats, both reset modes and 4 sizes |
| `rtc` | A real `.circ` with a `RealTimeClock` | The same file in `CircuitSimulator` | Output width, and each reading against the wall-clock window around both reads, in milliseconds and seconds |
| `vga` | A real `.circ` with `SocBus`, `Socmem` and `SocVga`; transactions go through Evolution's `SocSimulationManager` | `SocTransactionEvent` on the same bus | Display mode, size and every displayed pixel after random word, half-word and byte accesses, mode-register writes and accesses with no responder |

```sh
./gradlew :core:jvmJar
scripts/oracle/output-devices/run.sh /path/to/logisim-evolution-all.jar [core-jvm.jar] [buzzer oscilloscope video rtc vga]
```

`run.sh` compiles the oracle and registers `OracleMixerProvider` as a Java Sound service, so Evolution's Buzzer thread hands its clip to the oracle instead of an audio device. The SoC bus builds a Swing dialog, so the JVM is not headless. The seed is fixed; each section prints a `PASS` line with its counts and the run ends with `PASS <n> output-device desktop/mobile comparisons`.

## What is not compared

- Noise tones: both programs draw random samples. Their tone fields are compared.
- Audio playback and timing on a device. The oracle compares the buffer that would be looped.
- The blinking cursor of RGB Video: both programs blink with the wall clock.
- Unwritten SoC memory: Evolution returns a new unseeded random word for every read, so the oracle writes the whole frame buffer first. The display then reloads the same words in both programs.
- When Evolution repaints the VGA display: it reloads a pending frame from the bus at paint time. The oracle paints after every transaction, standing for the canvas refresh. Mobile reloads in the transaction that changes the mode.
- RealTimeClock against an injected clock: Evolution reads `ZonedDateTime.now()` directly. The oracle checks that both readings fall inside the same wall-clock window.

## Known differences

- With a smoothed non-sine waveform, Evolution's sound thread throws `ArrayIndexOutOfBoundsException` in `System.arraycopy` when `4 × cycle < 2 × smooth width`, where `cycle = ceil(44100 / Hz)`. That happens only at high frequencies, and the desktop stays silent. Mobile plays the unsmoothed wave. The oracle checks that the desktop is silent exactly in those cases.
- A zero-volume tone: Evolution loops a clip of zeros; Mobile plays nothing. Both are silent.

## Fixed while building the oracle

- RGB Video, Atari 2600 palette, undefined data: Java's `IndexColorModel` masks a 7-bit pixel with `0xFF`, so `-1` reaches an empty table entry and Evolution paints black. Mobile wrapped to Atari color 127.
- SocVga: Evolution redraws the whole frame from the bus when the display starts, after a reset and after a mode change. Mobile cleared the frame to black. `SocVgaDevice` now marks the reload, and `SocAddressSpace` performs it at the next transaction.
