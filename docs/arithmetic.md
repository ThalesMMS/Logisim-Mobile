# Arithmetic library

`ArithmeticComponents.definitions()` provides the 13 active entries in the Arithmetic library. The registry validates attributes, and the terminals use desktop geometry. The components have fixed orientation regardless of `facing`.

| Component | Inputs | Outputs | Options |
| --- | --- | --- | --- |
| Adder | A, B, 1-bit carry | Result, 1-bit carry | `width` |
| Subtractor | A, B, 1-bit borrow | Result, 1-bit borrow | `width` |
| Multiplier | A, B, full-width carry | Low half, high half | `width`, `mode` |
| Divider | Low dividend, divisor, high dividend | Quotient, remainder | `width`, `mode` |
| Negator | Value | Negated value | `width` |
| Exponentiator | Base, exponent | Low half, high half | `width`, `mode` |
| Square Root | Low radicand, high radicand | Integer root, remainder | `width` |
| Absolute | Value | Absolute value, 1-bit overflow | `width` |
| Comparator | A, B | Greater, equal, less | `width`, `mode` |
| MinMax | A, B | Minimum, maximum | `width`, `mode` |
| Shifter | Value, distance | Shifted value | `width`, `shift` |
| Bit Adder | 1 to 64 words | Count of set bits | `width`, `inputs` |
| Bit Finder | Value | Presence, index | `width`, `type` |

`width` accepts 1 to 64 bits. It defaults to 8, except for Exponentiator, which uses 32. `mode` accepts `unsigned` and `twosComplement`. Multiplier also accepts `signedUnsigned`, with signed A and unsigned B. Comparator defaults to `twosComplement`. The other components with `mode` default to `unsigned`.

Shifter accepts `ll`, `lr`, `ar`, `rl`, and `rr`. Bit Finder accepts `low1`, `high1`, `low0`, and `high0`. Bit Adder uses one input by default, and its output grows to 13 bits for 64 words of 64 bits each.

## Validation

`ArithmeticLogicTest` covers literal results, 64-bit extremes, division by zero, signs, overflow, shifts, and undefined bits. `ArithmeticComponentsTest` checks registration, attributes, and terminals. The [differential oracle](../scripts/oracle/arithmetic/README.md) calls the actual propagation methods of all 13 desktop classes and checks outputs and delays at every width from 1 to 64.

The reference is the catalog in desktop revision `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01` at `src/main/java/com/cburch/logisim/std/arith/ArithmeticLibrary.java`. The implementation preserves some unusual responses for compatibility, including division by zero and Exponentiator's handling of undefined values. The oracle README describes these cases.
