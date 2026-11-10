# BFH and I/O-Extra components

`ExtraIoComponents.definitions()` registers the two active BFH entries and the eight active I/O-Extra entries. `ProgrammableGenerator` remains excluded because its entry is commented out in the desktop catalog.

| Library | Name in the `.circ` file | Behavior |
| --- | --- | --- |
| `#BFH-Praktika` | `Binary_to_BCD_converter` | Converts a 4- to 13-bit input to 4-bit BCD digits. `digit0`, `digit1`, and subsequent names follow increasing powers of ten. |
| `#BFH-Praktika` | `BCD_to_7_Segment_decoder` | Decodes decimal digits 0 to 9 into seven outputs. Codes 10 to 15 and undefined inputs leave the segments undefined. |
| `#Input/Output-Extra` | `Switch` | Interactive contact from 1 to 64 bits. Open leaves the output undriven. Closed passes the input through. |
| `#Input/Output-Extra` | `TwoWaySwitch` | Switches a bus between two contacts in `1in2out` and `2in1out` modes. |
| `#Input/Output-Extra` | `Slider` | Physical position from 0 to 255 with a quantized 1- to 8-bit output and configurable direction. |
| `#Input/Output-Extra` | `TWOPINLED` | Indicates forward logic current, or reverse current in bipolar mode. Undefined inputs leave the LED off. |
| `#Input/Output-Extra` | `PlaRom` | Programmable array with 1 to 32 inputs, terms, and outputs and active-high or active-low chip select. |
| `#Input/Output-Extra` | `Digital Oscilloscope` | Captures both edges and corrects current cell data after gate propagation. |
| `#Input/Output-Extra` | `NE555` | Astable or monostable circuit with RC integration over active simulation time. |
| `#Input/Output-Extra` | `Buzzer` | Generates stereo PCM with configurable frequency, volume, duty cycle, waveform, channel, and smoothing. |

## State and interaction

The events `ExtraIoEvent.Toggle`, `SetSwitch`, `MoveSlider`, and `ClearTrace` change only the simulated instance. The PLA editor emits a `Contents` change that must go through document history. Each instance has separate state.

`ExtraIoControls` provides buttons and a slider with touch targets of at least 48 dp. The PLA matrix preserves desktop visual order for inputs and outputs and scrolls horizontally. `drawExtraIoComponent` draws module symbols and state while preserving terminal geometry.

The oscilloscope stores at most `2 × nState` columns. `frontlines` and `showclock` affect presentation. Both edges are captured regardless of the vertical-line option. The oscilloscope captures simulated signals only.

## Time and audio

The caller injects monotonic time through `elapsedTimeMillis` and provides `autoPropagating`. The NE555 holds its state while automatic propagation is paused. Each update caps integration at 100 ms, matching desktop, and requests another evaluation in 10 ms through `nextWakeupMillis`. That deadline schedules reevaluation independently of the logical clock.

`BuzzerTone` stores the instance's audio parameters. `ExtraIoAudioEffects` delivers PCM data to `AudioTrack` on Android and `AVAudioPlayer` on iOS. Removing the component, disposing the session, or disabling the effect stops the player. Device failures go to the `onFailure` callback for the host to display.

The module handles audio output only. It performs no microphone, socket, external process, or capture-device access. The caller controls whether audio output is enabled for the active session.

## Verification

`ExtraIoLogicTest` and `ExtraIoComponentsTest` check literal values, states, schemas, geometry, and events. The oracle runs the desktop JAR components and mobile core functions with identical inputs.

```sh
./gradlew :core:jvmJar
./scripts/oracle/extra/run.sh /path/to/logisim-evolution-all.jar
```

The oracle performed 71,316 comparisons of the BFH converters, Switch, TwoWaySwitch, Slider, TwoPinLed, and PlaRom with no differences. It includes every binary value at widths 4 to 13 and undefined values. NE555 timing tests use injected time. Audio tests check PCM and the WAV container. The audio adapters and controls require separate testing in the integrated apps.

The module run passed 29 core tests on JVM, 11 presentation and audio lifecycle tests on the Android host, and Compose compilation for Android and iOS Simulator ARM64. `examples/extra-converters.circ` connects the two BFH converters and produces the decimal digits `3`, `2`, and `1`, along with the segments for digit `1`.

The host must integrate the production registry, controls, renderer, and audio effect. Playback on a physical device requires separate evidence from adapter compilation.

The references are `BfhLibrary.java`, `ExtraIoLibrary.java`, and the classes registered in those catalogs in desktop revision `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01`.
