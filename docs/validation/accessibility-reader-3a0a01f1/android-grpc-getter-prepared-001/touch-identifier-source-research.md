# Logical touch identifier and Linux slots

Scope: public source research pinned to QEMU commit `686efa16baf59d776cadc3f975d12570fe44bbb9`. No local runtime, device, endpoint, installed SDK, process arguments, environment or credential was queried. The downloaded files are public text sources; `manifest.json` records their URLs, sizes and SHA-256 values.

## Conclusion

The planned API identifier 28 is valid under this public proto contract. It is a logical contact identifier, not a request for Linux slot 28. A guest that advertises ABS_MT_SLOT 0 through 10 does not require the API identifier to fall within that slot range. The sender maps the external identifier to an available internal slot.

| Source | Evidence |
| --- | --- |
| [emulator_controller.proto, 563–573](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/emulator_controller.proto#563) | The public field is an arbitrary nonnegative int32 identifier, stable for a contact and mapped to an internal identifier. The contract describes up to ten concurrent touches. |
| [TouchEventSender.h, 32–40](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.h#32) | `mUsedSlots` tracks internal slots; `mIdMap` maps external integer identifiers to those slots. There is no per-client namespace in this class. |
| [TouchEventSender.cpp, 60–68 and 113–148](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.cpp#60) | A new external identifier obtains the first unused slot; an existing one reuses its mapping. Full occupancy logs a warning and skips that touch. |
| [multitouch-screen.h, 38–48](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-emu/android/multitouch-screen.h#38) | This source sets `MTS_POINTERS_NUM=10`, pointer-up to -1 and pressure maximum to 1024. The sender's search therefore uses slots 0 through 9. A guest's advertised maximum 10 does not increase this sender's allocation limit. |

## What the kernel trace should show

The sender emits `ABS_MT_TRACKING_ID=slot` when registering a contact, then records external identifier to slot. It does not emit API identifier 28 as the Linux tracking ID. With an otherwise empty sender, identifier 28 would obtain slot 0 and tracking ID 0 in this implementation. A future passive trace must infer the actual slot/tracking association from the emitted sequence, rather than search for tracking ID 28. [TouchEventSender.cpp, 138–147](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.cpp#138).

The source scales coordinates from the requested physical display dimensions to 0 through 32767. It forwards the supplied pressure without clamping. Pressure 64 is positive and fits the separately reported guest range 0 through 1024, but this research did not query that guest. [TouchEventSender.cpp, 47–57 and 150–167](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.cpp#150).

## Required close and failure limits

The public proto requires pressure zero for the same logical identifier after a contact. In the sender, that emits tracking ID -1 and removes the used-slot, identifier-map and expiration entries. An unknown identifier with pressure zero is ignored as already closed. Every submitted batch ends with SYN_REPORT. [Proto, 575–609](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/emulator_controller.proto#575), [sender, 125–135 and 164–181](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.cpp#125).

The default 120-second expiration is not a guaranteed timer-driven release. The cleanup loop runs when `doSend` handles another request. The header explicitly describes closure on receipt of the next event after inactivity. `NEVER_EXPIRE` disables even that aging path. A client must send its explicit pressure-zero close in a bounded cleanup path; it must not assume that waiting 120 seconds releases the contact. [Header, 44–47](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.h#44), [sender, 84–111](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.cpp#84).

## Recommendations for the prepared single-contact unit

Use logical identifier 28 consistently and reserve the lane exclusively. Do not treat identifiers as client-isolated, reuse a currently active logical identifier, or select a Linux slot through this field. Keep pressure within the real guest range and explicit release in `finally`. Record the actual Linux slot/tracking close if a passive trace is authorized. A scheduled request or skipped full-slot request cannot qualify delivery or reader recognition. The exact installed Emulator 37.1.11 implementation still needs separate attribution; this source result does not establish a byte-identical binary or actual runtime state.
