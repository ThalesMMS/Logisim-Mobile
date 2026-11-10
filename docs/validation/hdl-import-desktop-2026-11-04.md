# HDL import desktop acceptance — 2026-11-04

The production importer now emits projects that the original desktop loader
accepts directly. Two faults were reproduced before the change: root-level
`hdl-source` elements rejected the file, and custom symbol ports without `dir`
failed desktop port binding. Sources now use inert custom options, with escaped
line endings, and custom ports declare their direction.

Validation used the exact `.circ` emitted by the production importer, without
filtering metadata or changing geometry for the desktop. Four existing source
fixtures passed 44 complete binary input combinations against the original HDL
reference and both simulators: hierarchy 4, multibit gates 32, include/macro 4,
BLIF 4. The structural comparison covered 5 circuits, 17 pins, 15 gates and 1 hierarchical
instance. Five negative controls were rejected, including an actual desktop run
of an AND-to-NAND mutation that produced four failing vector rows.

The corrected JAR was built from `f72c8b138a2417b6beb8bb80ed3f4101171fbbbb`.
Fifteen focused JVM tests passed with no failures, errors or skips. Its SHA-256
is `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20`;
the desktop JAR SHA-256 is
`0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`.
Both artifact hashes remained unchanged throughout the oracle execution.

The [machine-readable receipt](hdl-import-desktop-2026-11-04.json) retains
commands, tool versions, source/netlist/project/output hashes, canonical
observations, producer identity and negative controls. The rerunnable tool is
[`scripts/oracle/hdl-import/run.py`](../../scripts/oracle/hdl-import/run.py).
Full synthesis logs, source files and `.circ` artifacts remain under
`outputs/hdl-import-desktop-oracle` in the validation worktree.

This proves the sampled binary combinational profile and listed structural
attributes. It does not establish sequential timing, X/Z equivalence, the whole
component catalog or mobile UI/device interaction. Authenticated HTTP and
cancellation remain covered by separate tests. The desktop discards unknown
source-archive options when saving again; the mobile round-trip preserves them.
Source names retain the existing plain-ASCII policy, while source text preserves
Unicode, CR/LF, tabs and XML delimiters.
