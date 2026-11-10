# Agent guidelines

## Issue acceptance

- Before editing, identify the expected behavior, outstanding acceptance criteria,
  focused tests, and evidence needed to complete the work. Define prerequisites
  and time limits for verification.
- Work toward complete deliverables. If a round of work stops resolving
  outstanding criteria, revise the plan before continuing. Finish the
  deliverable once the current scope's criteria have been verified.
- Fix issues that block acceptance of the current work. For other problems,
  look for an existing issue and record the gap separately with reproduction
  steps and evidence. Prioritize data loss and security flaws.
- Isolate parallel changes and preserve other agents' work. Coordinate
  integration in shared files and use of resource-intensive builds, simulators,
  devices, boards, and companion ports.

## Proportional verification

- Run focused tests during implementation and reuse existing tools and fixtures.
  Batch integration checks. Outside required CI and established integration
  gates, run a full suite only when the maintainer requests that run.
- Reuse evidence when the scenario and artifact still apply. Record the SHA,
  environment, command or steps, and observed result. Keep historical evidence
  separate from new runs. Use the app UI when a criterion requires a user flow.
- Set a timeout for each run. Repeat a UI or infrastructure test only after a
  fix, a relevant change in the environment, or a new hypothesis the run can
  distinguish. If the same blocker persists, record what is missing and release
  the shared resource.
- For performance work, plan a focused campaign of about 30 minutes per issue
  that affects the measured behavior. Use the existing tools and repeat the
  campaign once after fixing a regression. Specify a different budget in the
  issue's criteria when needed.
- Distinguish product failures, timeouts, and missing prerequisites. If a board,
  cable, license, tool, or device is missing, record the configuration, the last
  verified step, and the condition for resuming. End the attempt and leave the
  required criterion open. Track compilation, simulation, on-device execution,
  and physical programming separately.
- When the task includes integration and issue closure, confirm the revision on
  `main` and record the results for the required criteria before closing it.

## Evolution parity

- Use the Evolution revision pinned in [Epic #255][epic] as the reference.
  Complete the [matrix #236][matrix] audit when each item in that reference has
  reproducible evidence or a tracking issue. Record differences from the
  original 2.7.1 edition in its profile.
- Keep confirmed gaps outside an issue's acceptance scope in the backlog. If a
  gap concerns the pinned reference, it remains a requirement for
  [final acceptance #243][gate]. Final acceptance covers Android, iOS, and
  integrations on the same revision.
- When asked to pause, preserve the state of the work and record where to resume.
  Wait for instructions before starting another issue.

[epic]: https://github.com/ThalesMMS/logisim-mobile-dev/issues/255
[matrix]: https://github.com/ThalesMMS/logisim-mobile-dev/issues/236
[gate]: https://github.com/ThalesMMS/logisim-mobile-dev/issues/243

## Out of scope

- GitHub workflows.
- Vendor toolchains, including Quartus, Vivado, ISE/iMPACT, and QuestaSim. HDL
  and constraint generation and the companion adapters remain covered by
  fixture-based tests.
- Physical FPGA boards and cables, including programming, flashing, and bench
  testing.
- Physical devices and peripherals, including keyboards connected to Android
  or iPad and on-device performance and energy measurements.
- VoiceOver on physical devices and in the simulator. TalkBack on Android
  remains in scope.
- Desktop-only items listed as out of scope in `parity/matrix.json`, including
  windows, Swing, Java2D, and desktop directory and tool paths.
- Windows hosts.
