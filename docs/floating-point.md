# Floating point components

`FloatingPointLibrary.definitions()` registers the 18 desktop FPArithmetic file identities. A registry containing these definitions supplies their typed attributes, ports, bounds, evaluation, and propagation delays.

| File identity | Operation |
| --- | --- |
| `FPConstant` | Configurable floating point constant |
| `FPAdder`, `FPSubtractor` | Addition and subtraction |
| `FPMultiplier` | Multiplication or fused multiply-add |
| `FPDivider` | Division and IEEE remainder |
| `FPNegator`, `FPAbsolute` | Negation and absolute value |
| `FPExponentiator` | Arbitrary power, exponential, or exponential minus one |
| `FPLogarithm` | Arbitrary-base, natural, base-10, or log-one-plus |
| `FPSquareRoot` | Square root |
| `FPComparator`, `FPMinMax` | Comparison, minimum, and maximum |
| `FPRound` | Ceiling, floor, Java round, ties-to-even, or truncation |
| `FPTrigonometry` | Trigonometric, inverse, or hyperbolic functions |
| `FPClassificator` | Sign, zero, subnormal, normal, infinity, and NaN |
| `FPToFP`, `FPToInt`, `IntToFP` | Format and integer conversion |

The supported formats are FP8 E4M3 with bias 7, IEEE binary16, binary32, and binary64. The serialized attribute is `fpwidth`. Format conversion uses `fpwidthin` and `fpwidthout`; integer conversion also uses `width` between 1 and 64. Defaults match the desktop, including the 32-bit floating point format and 8-bit integer format.

The arithmetic path follows the desktop: decode the selected format to Double, perform the operation, then encode the result. Output to FP8, binary16, or binary32 first converts the Double result to Float. Small formats use round-to-nearest, ties-to-even. Fused multiply-add uses exact integer significands before its single binary64 rounding, including cancellation, overflow, and subnormal cases.

The error pin reports NaN conditions, not every loss of precision. Overflow into an infinity is not by itself an error. `FPToInt` saturates to a signed 64-bit integer before masking to the selected integer width. Its `round` mode resolves halfway cases toward positive infinity; `rint` resolves them to even. These are different operations.

Inputs containing undefined or error bits decode as NaN. `FPClassificator` preserves the desktop's special rule for such inputs: the sign and NaN outputs are true. The other classification outputs are false.

## Verify against the desktop

Build the core and run its focused tests, then run the independent factory comparison:

```sh
./gradlew :core:jvmTest --tests '*FloatingPointLibraryTest'
./scripts/oracle/floating-point/run.sh /path/to/logisim-evolution-all.jar
```

The oracle invokes the actual Java factories through `InstanceState`, including their attributes, ports, bounds, and delays. It covers the four formats, every registered component, every operation mode, integer widths, undefined inputs, and finite boundaries. Values, terminal geometry, and delays are compared exactly. Floating point NaNs are compared by class and the separate error output because the JDK's FMA and float16 intrinsics may choose different payload bits from the JDK fallback implementation. Integer conversion outputs remain bit-exact. The runner also loads and executes all four circuits in the example file with the desktop simulator.

The common tests include literal format encodings, signed zero, minimum subnormals, rounding ties, overflow, FMA cancellation, invalid operands, classification, and conversion limits. `examples/floating-point.circ` contains one addition circuit for each format.
