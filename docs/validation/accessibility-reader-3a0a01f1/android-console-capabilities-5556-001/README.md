# Read-only console and touch capabilities

Result: `PASS_READ_ONLY_INVENTORY_ONLY`. The lane owner authorized 19 getters on emulator-5556 with the reader disabled. They all returned zero with empty stderr. No contact, focus move, input event, configuration change, credential read or process-argument read occurred. The lane was returned afterwards. This does not qualify a reader gesture.

The console identified AVD `ISIS-VALIDATE` and enumerated EV_ABS, EV_SYN, EV_KEY and the required multitouch code aliases. Its help describes the event subsystem as sending hardware events to the kernel. No `event send`, mouse, text or key command was issued.

`getevent -lp` and `dumpsys input` identify `virtio_input_multi_touch_1`, `/dev/input/event1`, as the active DIRECT touchscreen for display 0. Eleven devices share similar ranges, but only this device has an active display viewport. The main device advertises:

| Axis | Minimum | Maximum |
| --- | ---: | ---: |
| ABS_MT_POSITION_X | 0 | 32767 |
| ABS_MT_POSITION_Y | 0 | 32767 |
| ABS_MT_SLOT | 0 | 10 |
| ABS_MT_TRACKING_ID | 0 | 65535 |
| ABS_MT_PRESSURE | 0 | 1024 |

It does not advertise BTN_TOUCH. Current pressure is zero. Framebuffer and logical/physical viewport are 1080 by 2400, orientation 0, density 420. Before and after the getters, accessibility was disabled, enabled/bound/binding service sets were empty and touch exploration was false. These new disabled-state observations do not fill the earlier reader-on state gap.

`gets.tar.gz` preserves all raw responses, command vectors, timestamps and the parsed device summary. `gets-manifest.json` hashes every entry. [Source research](source-research.md) distinguishes the public QEMU virtual-hardware paths from guest framework injection. It does not claim a byte-identical match to the installed Emulator 37.1.11 binary. No gRPC endpoint has been identified or contacted.

The next reader attempt remains unapproved and unexecuted. The [one-gesture plan](next-transport-plan.md) prefers managed controller touch if an existing endpoint can be attributed and authenticated without exposing secrets. Raw console is a separate reviewed choice, not a runtime fallback. No installation, emulator restart or new fixture is needed by this inventory.
