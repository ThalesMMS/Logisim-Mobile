# Retain an already observed Downloads location

This source preparation changes `Scenarios.choose_downloads` before it requests **Show roots**.
The helper returns without a navigation tap when one native breadcrumb and one native file list identify Downloads together, with no roots drawer.
The breadcrumb and file list must belong to the same DocumentsUI package.
Multiple breadcrumbs, multiple file lists, a drawer row, or an unrelated heading do not satisfy the new condition.
This condition admits the observed native presentation. The later nonce, file-byte, and imported-board checks still identify the actual input.

The observed Android API35 fixture is byte-identical to the first tree retained by FPGA attempt005.
That tree already identifies Downloads before the previous helper opens the drawer.
The fifth attempt remains failed in its separate, immutable archive.

The helper keeps the presentation deadline, the total deadline, and the existing navigation path for other states.
It checks the remaining time before returning and records the source hierarchy in the trace.
No board filename, import marker, mapping assertion, job deadline, or synthesis oracle changes.
The source outside `choose_downloads` and the ten recorded FPGA inputs are byte-identical to base `8e8f593d`.

The focused Python suites passed 74 tests without Android processes.
The new assertion against the actual retained tree fails on the previous helper because that helper requests two navigation taps.
Negative cases reject an isolated heading, a foreign breadcrumb, mismatched native packages, a subfolder, duplicate file lists, and ambiguous drawers.
The checks also retain late-query rejection and delayed picker presentation.

Native execution remains `NOT_RUN` for this preparation.
The next planned unit is one `--case all` invocation on emulator-5558 with the same app and helper producers.
That unit requires a new Root lease and must retain any failure without retry.
