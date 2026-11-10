# I/O state descriptions

The canvas and circuit explorer use `accessibleComponentValue` with their existing captured simulation. It keeps the signal description for other libraries and adds a read-only description of the twelve I/O component states. It does not send component events, advance simulation, read a wall clock, or add a live announcement.

The descriptions include button pressure, numbered DIP positions, joystick position and quantized outputs, keyboard buffer and cursor, visible LED/RGB state, digits or named lit segments, matrix dimensions and visible cells, terminal text and insertion position, and pressed keypad labels with row/column positions. User-facing indices are one-based. LED polarity and the inclusive matrix persistence tick follow the drawing functions. Segment masks are interpreted as segments, not as numeric input values.

Long data is summarized. Keyboard and terminal previews inspect at most 96 UTF-16 units without cutting a surrogate pair, name at most eight control characters, and report omitted characters. Keypad summaries list at most eight pressed keys with labels limited to 12 units. Matrix summaries count every visible cell and preview three rows with up to eight lit columns per row. Omitted keys, rows and columns are counted explicitly.

The explorer retains its explicit refresh/optional automatic mode. The canvas retains its frozen value snapshot during Run. These descriptions use that same state and do not introduce announcements for every simulation tick.

`IoStateDescriptionsTest` evaluates real component behaviors and a complete I/O circuit, checks English and Portuguese text, and covers polarity, unknown values, hexadecimal segment recognition, persistence expiry, dimensions, cursor boundaries, long text and custom key labels. Those checks do not establish VoiceOver/TalkBack navigation or the full I/O interaction matrix in the native applications. Native UI validation remains a separate acceptance step.
