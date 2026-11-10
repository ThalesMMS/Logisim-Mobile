# Compare the Arithmetic library

Build the core, then run the oracle with the desktop JAR.

```sh
./gradlew :core:jvmJar
./scripts/oracle/arithmetic/run.sh /path/to/logisim-evolution-all.jar
```

Pass an optional second argument to select a different core JAR. Set `KOTLIN_STDLIB_JAR` if the Kotlin standard library is outside the Gradle cache.

The runner calls `propagate` on all 13 desktop components and compares outputs and delays against `ArithmeticLogic`. It covers every width from 1 to 64, signed and unsigned modes, and known, unknown, and error values. Cases include division by zero, 128-bit operands, overflow, counts, rotations, and exponents that the desktop truncates. The fixed seed is `320064`.

The implementation preserves these observable desktop behaviors.

- Division by zero behaves like division by one.
- The Exponentiator converts undefined outputs to known words with every bit set to one.
- The Multiplier uses a 32-bit mask to extract the known prefix of a partial output.
- Absolute preserves the desktop's partial-value algorithm. Its behavior differs from Negator after the first set bit.

Operations with wide results use 128-bit arithmetic in common code. Exponentiation computes only the observable bits, avoiding intermediate numbers whose size would grow with the exponent.
