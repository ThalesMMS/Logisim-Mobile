# Emulator touch transport candidates

Status: source research and a proposed next read-only unit. No local VM query, console/RPC request, GUI, build, executable download, credential read or gesture was performed. Emulator implementation findings below are pinned to public QEMU commit `686efa16baf59d776cadc3f975d12570fe44bbb9`; they do not identify the exact installed Emulator 37.1.11 binary.

## Virtual hardware paths in the public implementation

`EmulatorController.sendTouch` schedules `TouchEventSender.sendOnThisThread` on the emulator main looper. The RPC returns OK after scheduling. That response is not a guest-delivery or reader-recognition receipt. [EmulatorService.cpp, sendTouch](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/EmulatorService.cpp#533).

`TouchEventSender` reads display dimensions, scales physical coordinates to its EVDEV axis range, manages slots/tracking IDs, builds Protocol B ABS events, releases tracking when pressure becomes zero, appends SYN_REPORT and calls `sendGenericEvents`. [TouchEventSender.cpp, doSend](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/android/emulation/control/keyboard/TouchEventSender.cpp#71).

Console `event send` parses each `type:code:value`, sets display ID 0 and calls the same user-event agent through `sendGenericEvent`. It does not construct a gesture, scale pixels, assign or release tracking IDs, or append SYN_REPORT automatically. Each tuple is forwarded while parsing, so an error on a later tuple can follow earlier side effects. [console.cpp, do_event_send](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-emu/android/console.cpp#2145).

The user-event agent sends these events to `android_virtio_input_send` when that backend accepts them, otherwise to `goldfish_event_send`. Its generic-events function loops over the same generic-event function. This is a host virtual-input-device path, not guest `InputManager.injectInputEvent`. [qemu-user-event-agent-impl.c](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android-qemu2-glue/qemu-user-event-agent-impl.c#79). The virtio implementation advertises absolute axes, slots, tracking IDs and pressure and passes events to the virtio input queue. [virtio-input-multi-touch.c](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android-qemu2-glue/emulation/virtio-input-multi-touch.c#173).

Google documents both the console `event` facility as hardware simulation and `adb -s SERIAL emu` as the console shortcut. The same guide maps EV_ABS console input to sendTouch. [Official console guide](https://developer.android.com/studio/run/emulator-console).

## Contract differences

| Candidate | Existing advantage | Work still needed before a gesture |
| --- | --- | --- |
| gRPC sendTouch | Physical pixel coordinates and managed slot/tracking/pressure lifecycle | Correlate an existing endpoint with the reserved AVD, prepare a compatible installed client/proto and existing authentication, verify dimensions/orientation |
| `adb -s emulator-5556 emu event send` | Existing serial/console route; no application-level token handling or new gRPC endpoint needed | Confirm supported console commands, identify the guest touch device and exact ABS ranges, construct the entire Protocol B sequence and guaranteed release |

The protobuf requires a stable nonnegative identifier during a contact, nonzero pressure while touching and an explicit pressure-zero close. Its TouchEvent uses physical coordinates and Protocol B. It is experimental; the installed SDK definition must control the eventual client. [emulator_controller.proto](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/emulator_controller.proto#554).

Console values are raw EVDEV units. The inspected gRPC sender uses 0..32767 internally, but that is not an observation of the current guest device's capabilities. Do not send pixel coordinates such as 300,800 as if they were already absolute-axis values. Slot/tracking/pressure ranges must be read from the specific guest device before preparing raw events. Each motion sample needs a SYN_REPORT; release must close the active tracking ID and be synchronized. Advertised device capabilities must determine whether other legacy button fields are required.

## Recommended next unit: read-only console and device capability inventory

This is the smaller next unit for the existing headless instance, before selecting a gesture transport. It does not enable a reader or send an input event.

1. Obtain a fresh explicit lease or read-only authorization for the known serial. Correlate the process/AVD/console identity using the coordinator's sanitized inventory.
2. Check only documented console status/help/type/code queries through the installed `adb -s emulator-5556 emu` route. Source declares `event types` and `event codes <type>` as enumeration commands. Do not call `event send`, mouse, text or key input.
3. Read the guest input-device descriptions and capabilities with bounded read-only commands. Retain touch-device name/identity, display association and ABS_MT_SLOT, ABS_MT_TRACKING_ID, ABS_MT_POSITION_X/Y and pressure ranges. Do not start a continuous getevent observer or change device configuration.
4. Preserve tool/source versions, serial, command vectors, time limits, exit status and sanitized responses. A command/authentication failure ends this unit; do not read credentials or switch transport automatically.
5. Prepare exactly one transport from that evidence. Console is a reasonable lower-setup candidate if its capability and range preconditions pass. If they do not, report those missing facts; preparing gRPC is a separate unit, not a fallback during a reader run.

## If a later unit chooses gRPC

Use only an existing endpoint correlated to the reserved AVD and its installed SDK proto/client. Do not start/restart the emulator or alter authentication merely to obtain an endpoint. Public emulator documentation describes token and JWT authentication with bearer metadata. Plan to obtain any already-authorized credential inside the client process and keep it in memory, never in command arguments, stdout, logs or receipts. Do not read one during this research. If the existing authentication is unavailable, stop and report the concrete dependency. [Public gRPC security documentation](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-grpc/docs/README.md).

## Later single-gesture qualification, not authorized by this report

Before enabling the reader, pin the chosen route, artifact hashes, orientation, window dimensions and gesture payload. Have a verified cleanup path for releasing the active contact and restoring the reader settings. For gRPC, use one point per request with measured host intervals; one repeated-touch list ending in a single SYN_REPORT does not establish the intended swipe duration. For raw console, build and validate the complete down/move/up Protocol B sequence offline first.

When explicitly authorized later, retain bound-service and touch-exploration state while ON, a non-suppressing SDK focus baseline and speech-display video. Send only one planned gesture. Always close the contact on success/error. Retain the subsequent focus identity, actual new reader caption/utterance and viewport geometry. Stop if it pans the app, fails to provide the expected reader transition, loses the service or cannot prove cleanup. RPC/console OK alone cannot pass this unit. No second gesture, double-tap or dynamic transport fallback is implied.

## Relationship to the previous AOSP report

The previous conclusion remains valid for the inspected AOSP `android-15.0.0_r1`: shell `input touchscreen swipe` uses guest framework injection and bypasses InputFilter. Its application to the Google AVD is an inference, not a binary trace. Later video inspection found actual Canvas speech-display text in frames 013–023. That corrects the preliminary absence-of-caption observation but does not establish that the shell swipe was recognized as a TalkBack navigation gesture. The missing live bound-service/touch-exploration observations remain missing.
