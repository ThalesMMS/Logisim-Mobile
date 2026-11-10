# BFH and I/O-Extra oracle

Run `run.sh /path/to/logisim-evolution-all.jar` after building `:core:jvmJar`. Pass an optional second argument to select a different Mobile JAR. Set `KOTLIN_STDLIB_JAR` to use a Kotlin standard library outside the Gradle cache.

The runner applies the same inputs to the desktop classes and the compiled core. It compares every bit, including unknown and error values. The reference run passed 71,316 comparisons and used seed 46 for the PLA matrix.

Coverage includes BinToBcd, BcdToSevenSegmentDisplay, Switch, TwoWaySwitch, Slider, TwoPinLed, and PlaRom. NE555 depends on an injected active time source. Buzzer depends on a native PCM sink. DigitalOscilloscope captures events and updates its current cell. Common tests cover those three behaviors. The runner's differential comparison covers only cases that can be exercised without real timing or hardware.
