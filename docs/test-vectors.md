# Run test vectors

The **Test vectors** panel runs text files against a circuit in the project. The header identifies pins by label and width.

```text
A[8] B[8] Y[8]
0x10 0x20 0x30
```

1. Select the circuit at the top of the panel.
2. Paste the file and choose **Load**. The reader validates names, widths, and structure before execution.
3. Use **Step** to execute one row or **Run** to go through the file.
4. Use **Pause** or **Cancel** to stop between rows. **Restart** clears results and simulated state.
5. Review each difference between expected and observed values in the list. **Export results** produces a CSV with the circuit, row, set, step, values, and differences.

The reader ignores blank lines and comments after `#`. Values accept `0x`, `0o`, and `0b` prefixes, signed decimal, and `_` separators. Without a prefix, a digit count equal to the width indicates binary. Other digit counts indicate signed decimal.

## Labels containing spaces

On mobile, use double quotes for a name containing spaces, `#`, brackets, quotes, or backslashes.

```text
"Data In"[8] "Data # Out"[8] # header comment
0xA5 0xAx
```

The width follows the quotes. In `"Data Out[8]"[4]`, the pin name is `Data Out[8]` and its width is four bits. Use `\"` for a literal quote and `\\` for a literal backslash inside a name. `#` starts a comment only outside quotes. Values in test rows remain unquoted.

The exporter applies quotes and escapes as needed. It preserves circuit names and lets the `exportReplay()` bundle run again with the same labels.

This is a mobile format extension. The audited desktop version at commit `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01` uses `StringTokenizer` in `data/TestVector.java`, which treats quotes and escapes literally. Files with quoted names require the mobile reader. The desktop oracle uses unquoted fixtures. The extension applies to test vectors only.

`x` marks don't-care bits in binary, octal, or hexadecimal values. Those bits accept known and unknown values and reject errors. `<DC>` skips the entire comparison for an output. `<float>` applies high impedance to an input or requires an entirely unknown output. `<DC>` is available for outputs only.

## Sequences and clocks

The optional `<set>` and `<seq>` columns follow the current desktop reader.

- Without these columns, each row resets the circuit.
- Set and step zero indicate a combinational test.
- Positive sets require positive, strictly increasing steps.
- Sets appear in increasing order.
- A new sequence resets the circuit. Subsequent rows preserve state.
- Before applying the next row of the same sequence, the runner toggles the circuit clocks. Clock columns check the observed value.

Use `<clk>` to identify an unlabeled clock. Duplicate pin names and mismatched widths produce binding errors.

`TestVectorRunSession.exportReplay()` bundles the `.circ`, normalized vector file, and results CSV for replay. The script `scripts/oracle/vectors/run.sh /path/to/logisim-evolution-all.jar` runs 14 combinational and sequential rows on mobile and desktop and compares the values and results.

The reader preserves `x` bits in 64-bit values. The audited desktop version clears that mask because of a 64-position shift. The mobile reader also rejects malformed cases with ambiguous column names before execution.
