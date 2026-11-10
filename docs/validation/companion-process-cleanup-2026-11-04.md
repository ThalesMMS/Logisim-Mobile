# Companion process-group cleanup on macOS

An integration run at `67f91efebba26e3633dbade5ec8696f390244c81` passed 29 of 30 companion server/API tests. The remaining test, `test_completion_cleans_up_children_after_the_leader_exits`, raised `termination-failed` after Darwin returned `EPERM` for `killpg(SIGKILL)` and the immediate presence probe.

The kernel can retain a group containing only zombies briefly after the leader exits. The [XNU implementation](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_sig.c#L1612-L1621) filters zombies from the group signal iteration and returns `EPERM` when no eligible member is found. A diagnostic run observed three such `EPERM` responses in 100 real child-process runs; the group was already absent by the subsequent `ps` snapshot.

Cleanup now waits at most 0.5 seconds after `EPERM`, and only after its own leader has been reaped, for an absence probe to return `ESRCH`. A live leader, a group that remains signalable, or persistent permission denial still raises `termination-failed`. The recovery path sends no additional terminating signals.

Validation on this Mac:

- `python3 -m unittest companion.tests.test_api companion.tests.test_server -v`: 33 tests passed, including three new cleanup boundary tests.
- The real orphan-child cleanup test passed 25 consecutive repetitions, checking that its delayed output file is never written.
- An independent source review found no blocking issue in the change.

Local logs: `/tmp/logisim-companion-cleanup-validation.log` and `/tmp/logisim-companion-cleanup-race-repeat.log`. Only processes created by these tests were signaled. These results do not qualify physical-device TLS, tool-specific adapters, or FPGA hardware.
