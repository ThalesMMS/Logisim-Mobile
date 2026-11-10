# Combinational analysis

The analysis panel can read a combinational circuit, edit its truth table, write expressions, minimize to sum of products or product of sums, and generate a new equivalent circuit. The simulator checks the generated circuit before handing it to the editor.

## Create a specification

Use **Read circuit** to evaluate the pins in the open circuit. Multibit pins expand from most significant bit to least significant bit. Duplicate labels, undefined outputs, clocks, memory, and recursive cycles prevent the circuit from being read.

To start a blank table, enter comma-separated input and output names. Tap an output cell to cycle through `0`, `1`, and `x`. A value of `x` lets minimization choose either result for that row.

In the **Expressions** tab, write one assignment per line.

```text
soma := A ^ B ^ C
carry := (A & B) | (A & C) | (B & C)
```

Use **Create table from expressions** to evaluate all input combinations. Names may contain Unicode letters and bus indices such as `A[3]` or `A:3`.

Use backticks around names containing spaces or reserved words, for example `` `Input A` & `OR` ``.

## Minimize and generate

Choose sum of products or product of sums and use **Minimize**. The minimizer finds the exact cover with the fewest terms, breaking ties by choosing fewer literals. Sum of products covers the `1` rows, while product of sums covers the `0` rows. Rows with `x` can expand groups. As on desktop, an output consisting entirely of `x` minimizes to `0` in sum of products and `1` in product of sums.

**Karnaugh** shows rows and columns in Gray code. Cells covered by the minimized terms are highlighted. Tap a cell to change the specification and invalidate the previous minimization.

**Generate circuit** creates pins, gates, and tunnels. The simulator checks every defined row of the table. The editor receives the document as a single operation so Undo restores the previous project.

## Limits

| Operation | Default limit |
| --- | --- |
| Truth table | 12 input bits, 4,096 rows, and 256 output bits |
| Exact minimization | 6 input bits, as in desktop automatic minimization |
| Karnaugh map | 6 input bits |
| Cover search | 1,000,000 operations per output |
| Generated circuit | 4,096 components |
| Expression | 16,384 characters, 2,048 nodes, and 128 grouping levels |

The mobile table uses a lower limit than the desktop's 20 input bits to avoid creating up to a million rows on a phone. Limits are configurable in the core through `AnalysisLimits`. An operation that reaches its limit reports an error.

Expensive operations run off the UI thread. **Cancel** stops evaluation or search and preserves the current specification.

## Expression syntax

The parser follows the desktop notation's precedence rules. For example, `A & B + C` means `A & (B + C)` because `+` has higher precedence than `&` in this notation. Use parentheses when mixing styles.

| Operation | Accepted forms |
| --- | --- |
| NOT | `!`, `~`, `-`, `¬`, `˜`, `NOT`, postfix apostrophe |
| AND | `&`, `&&`, `*`, `∧`, `⋀`, `⋅`, `∙`, `·`, `AND`, juxtaposition |
| OR | `|`, `||`, `+`, `∨`, `⋁`, `OR` |
| XOR | `^`, `⊕`, `!=`, `≠`, `≢`, `⊻`, `XOR` |
| XNOR | `=`, `==`, `≡`, `⇔`, `↔`, `⊙`, `EQUALS` |
| Constants | `0`, `1`, `⊥`, `⊤` |

The names `AND`, `OR`, `XOR`, `NOT`, and `EQUALS` can be variables when explicitly registered as inputs in the parser. Keywords are case-insensitive.

## Verification

`CombinationalAnalysisTest` covers all 256 three-input functions in SOP and POS, comparing the cover with an independent minimum-cost search. It also covers syntax, Gray code, don't-cares, limits, cancellation, export and execution of generated circuits, and recovery through Undo and Redo.

```sh
./gradlew :core:jvmTest --tests '*CombinationalAnalysisTest'
```
