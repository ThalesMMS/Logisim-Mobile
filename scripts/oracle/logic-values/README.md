# Differential value check

Build the mobile core and the desktop fat JAR, then run with JDK 21 or newer:

```sh
./gradlew :core:jvmJar
./scripts/oracle/logic-values/run.sh /path/to/logisim-evolution-all.jar
```

The runner uses the compiled mobile JAR and finds Kotlin's standard library in the Gradle cache. Override either with the second argument and `KOTLIN_STDLIB_JAR`. It prints the JAR hashes to identify the compared builds.

The fixed seed runs 260,000 comparisons against desktop `Value`: identity, AND, OR, XOR, NOT, driver combination, control, and resize with each fill state. Inputs include 1, 8, 16, 32, and 64 bits, with LOW, HIGH, UNKNOWN, and ERROR independently generated per bit. Mixed-width gate operations are also checked.

This checks `Value.controls`, not the complete Controlled Buffer component, which has a separate undefined-enable policy. It does not validate gate delays or network topology.

Mixed-width driver combination is excluded. The audited desktop's `Value.combine` uses an `int` shift when constructing its width mask; a 32-bit operand can produce an incorrect mask. For example, combining HIGH at width 1 with LOW at width 32 loses the desktop's conflict and high-bit values. Mobile combination keeps bitwise conflicts, and circuit networks diagnose incompatible connected widths. Equal-width driver combination is covered by the differential run.
