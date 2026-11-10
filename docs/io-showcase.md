# I/O showcase fixture

`examples/io-showcase.circ` is the shared input file for the Android and iOS user-flow checks of issue #44. Its main circuit is `IO_Showcase`. It contains 54 components, 64 orthogonal wires, all twelve I/O kinds, and nineteen named output probes. The file is a test fixture, not a record of a completed native UI run.

`examples/io-showcase.targets.json` lists labels, circuit anchors, body bounds, attributes and named ports. Its pointer points are circuit coordinates. A UI driver must use the observed viewport to convert them to screen coordinates. Locate components by label; imported IDs are not the test contract. Every input Pin has explicit type, facing, width, behavior and initial value. Every output Pin has `radix="2"`, so a global hexadecimal preference does not change the output oracle.

## Controls and probes

| Control or display | Inputs and probes | Literal initial result and interaction |
| --- | --- | --- |
| `IO_BUTTON`, `IO_LED_BUTTON` | `OUT_BUTTON` | Output 0, then 1 while Button is pressed, then 0 after release or Cancel. The coupled LED follows the button. |
| `IO_LED` | `IN_LED_LEVEL`, `OUT_LED_LEVEL` | Starts 0/OFF. One Pin activation leaves 1/ON stable for capture; the next returns to 0/OFF. |
| `IO_DIP` | `OUT_DIP0`, `OUT_DIP1` | Eight switches, `resetbehavior=last`, positions 0. Toggle switch 0 and then 1 to obtain outputs 10 and then 11 when listed as DIP0/DIP1. Only these two terminals are wired, permitting number 8 to 4 to 8 without removing a connected terminal. |
| `IO_JOYSTICK`, `IO_HEX` | `OUT_JOY_X`, `OUT_JOY_Y` | Four-bit center 1000/1000, Hex displays 8. Move to x=14,y=-14 for 1111/0001 and Hex F; release/Cancel recenters. |
| `IO_RGB` | `IN_RGB_R/G/B`, `OUT_RGB_R/G/B` | Initially 1/1/0, a yellow LED. Sequentially toggle R, G and B to obtain green, black/off and blue, with input tuples 010, 000 and 001. |
| `IO_LEDBAR` | `IN_BAR_VALUE`, `OUT_BAR_VALUE` | Initial decimal 165, binary 10100101. One activation toggles bit 0, as Evolution's PinPoker pokes a bit, and gives 164, binary 10100100. Display bit order is most significant bit on the left. |
| `IO_SEVEN_SEGMENT` | `IN_SEGMENTS`, `OUT_SEGMENTS` | Bus input, decimal point enabled, initial decimal 63, binary 00111111, displays 0. One activation toggles bit 0, as Evolution's PinPoker pokes a bit, and gives 62, binary 00111110: segments b to f are on, and a, g and the decimal point are off. |
| `IO_DOT_MATRIX` | `IN_MATRIX_ROW_0` through `_6`, `OUT_MATRIX_ROW0` | Seven rows by five columns, row inputs 14,17,17,31,17,17,17 form an A. Activating row 0 once changes its bits from 01110 to 01111. |
| `IO_KEYBOARD`, `IO_TTY` | `OUT_KEYBOARD_DATA`, `OUT_KEYBOARD_AVAILABLE`, `OUT_CLOCK`, `IN_READ_ENABLE`, `IN_WRITE_ENABLE` | Sixteen-character keyboard buffer, rising edges, 3-row/12-column TTY. Both enables start 1; both clear Pins start 0. See the clock sequence below. |
| `IO_KEYPAD` | `IN_SCAN_ROW_0` through `_3`, `OUT_KEYPAD_C0` through `C3` | Large PMOD keypad. Scan rows start 0,1,1,1, columns start 1,1,1,1. Holding row 0/column 2 gives 1,1,0,1; release returns all columns to 1. |

Changing a display's color or geometry must also be observed in the native app. The core checks cover LedBar height 3 to 4, which yields a 40-unit body height; DotMatrix rows 7 to 4 to 7; and TTY from 3x12 to 4x16, which yields a 124x72-unit body. They also check a `.circ` roundtrip after those changes. The DIP `positions` attribute in an exported editor document must be checked in the UI scenario; an in-memory state or reset check does not prove that persistence path.

## Keyboard clock sequence

Start from a freshly imported fixture with clock 0 and replace the Keyboard buffer with `AB`. `OUT_KEYBOARD_DATA` is 1000001, and available is 1. Step the circuit by half cycles:

| Total half cycles | Clock | Keyboard buffer | Data | Available | TTY current text |
| --- | --- | --- | --- | --- | --- |
| 0 | 0 | AB | 1000001 | 1 | empty |
| 1 | 1 | B | 1000010 | 1 | A |
| 2 | 0 | B | 1000010 | 1 | A |
| 3 | 1 | empty | 0000000 | 0 | AB |

This is two rising edges over three half cycles. The UI test must read the actual clock/data outputs and TTY content, rather than assume that any two Step taps are two rising edges.

Further literal core sequences are:

- Queue `AB\bC\nD` and consume six rising edges. The three TTY rows are `AC`, `D`, empty.
- Queue form feed followed by `Z`, and consume two rising edges. Rows become `Z`, empty, empty.
- Set `IN_TTY_CLEAR` to 1 to clear the TTY, then return it to 0.
- Queue `Aé` and consume two rising edges. The buffer retains `é`, but the seven-bit output transmits it as 0x69, so TTY rows are `Ai`, empty, empty.
- Queue `Q`, then set `IN_KEYBOARD_CLEAR` to 1. The buffer and available output clear without a clock edge.

The inspector exposes Home, End, ASCII Backspace and ASCII Form Feed controls. Backspace/Form Feed are transmitted characters, not desktop text-field editing commands. The TTY has no direct text editor; it receives the Keyboard's seven-bit data and the shared clock.

## Reproduce the fixture checks

The verifier uses an existing compiled core JAR and cached Kotlin compiler/JUnit dependencies. It does not invoke Gradle or download tools.

```sh
python3 scripts/verify-io-showcase.py \
  --core-jar /absolute/path/to/core-jvm.jar \
  --desktop-jar /absolute/path/to/logisim-evolution-all.jar
```

Use `--write` to regenerate the circuit and target manifest from `scripts/fixtures/IoShowcaseFixture.kt`. Without that option, the verifier checks that both files match the generator. The JVM test is also part of the normal core JVM suite.

On 2026-11-04, the seven fixture tests passed using Kotlin 2.4.21 and JUnit 4.13.2. The desktop reader reopened the mobile re-export and preserved all 54 component port geometries. The checked core JAR had SHA-256 `01073771c0c7643efa38d7b25d8c6905d31651f27538e9a6a8f4cd82a75720c4`; the desktop JAR had SHA-256 `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`. The verifier writes the input hashes and logs to `outputs/io-showcase/`.

Android and iOS UI execution remain `NOT_RUN` in that receipt. Desktop opening proves parsing/geometry here, not interaction or the I/O state sequence on either native host.
