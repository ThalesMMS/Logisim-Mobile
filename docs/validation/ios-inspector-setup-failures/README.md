# Inspector phone setup receipts

This archive preserves four terminal XCTest failures. All original output directories remain intact. It contains verbatim result/configuration JSON, captured Inspector/shared harness sources, final native trees and PNGs, selected test-log ranges, and input evidence. It excludes raw xcresults, full simulator logs, and app/framework executables.

| Case | Declared app source | Duration | Last observed stage | Native result |
| --- | --- | --- | --- | --- |
| Phone001 | A `4ab8aa93` | 29.364 s | Setup XML selection menu unavailable | FAIL |
| Phone002 | A `4ab8aa93` | 64.391 s | Imported-file status exposed as Other; query requested StaticText | FAIL |
| Phone003 | FINAL `6cef0542` | 116.174 s | Explorer header offered Hide keyboard while Close was requested | FAIL |
| Phone004 | FINAL `6cef0542` | 122.972 s | Width 1 focused; native key label Delete did not match query delete | FAIL |

`NUMERIC_NOT_RUN` means no numeric Delete, no entry of `0` or `4`, and no invalid/valid/Undo numeric oracles. Phone004 entered the numeric policy's prechecks and captured the focused width field; its first Delete was never sent. The earlier cases stopped during setup. These receipts establish the listed observations, without a product or operating-system cause finding or qualification pass.

Phone001 has **TEMPLATE_ONLY** evidence. Its exact lexical Swift multiline XML template retains `\(label)`; the runtime UUID label and interpolated input bytes were not captured. The template hash is not an imported-input hash. Phone002–004 include their actual staged `input.circ` bytes, per-run circuit/label, and hashes checked against the preserved Run configuration.

The final PNGs come from XCTest's `final-screen` attachments. Phone004 additionally retains its failure PNG/tree and the field's own root Attributes attachment. Shared harness files are deduplicated by content hash. `identical_inspector_file_at_commits` identifies matching captured Inspector files, not a reconstruction of every repository input. Full app source declarations, executable/bundle hashes, captured harness hashes, attachment identities, and original output-relative paths are in `manifest.json` and the original JSON files.

Local Git attributes preserve archived bytes across line-ending configurations. The log excerpts retain original trailing spaces; whitespace lint is disabled only for those excerpts.

Related source changes remain separate from execution results:

- `90d17025`: staged Inspector fixture and the local one-Delete policy.
- `2a4f9634`: Files Import Copy setup.
- `94d36310`: exact opened-file status across native roles.
- `98eca248`: Explorer keyboard/header close sequence.

Run from any directory with Python 3.9 or later:

```sh
python3 /path/to/ios-inspector-setup-failures/verify_archive.py
```

The verifier reads only this archive, checks file hashes and receipt/configuration cross-links, and reports **INTEGRITY_PASS** while retaining all native outcomes as **FAIL**. Original full-log hashes are provenance pointers; without those original logs, only the archived excerpts and their range hashes can be recomputed. App binary hashes are recorded from the original result JSON; executables are not included or rehashed here. This verification does not run the app.
