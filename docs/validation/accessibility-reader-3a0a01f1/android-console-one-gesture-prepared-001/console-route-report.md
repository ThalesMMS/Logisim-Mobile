# Console numeric tuples and conditional virtio delivery

Scope: public QEMU commit `686efa16baf59d776cadc3f975d12570fe44bbb9` only. The two supplied backend files match newly fetched text from that exact public commit. No local emulator/device/getter/event/credential operation was performed. This does not establish the installed Emulator 37.1.11 implementation or live delivery.

## Parsing and partial effects

`do_event_send` splits its arguments into space/tab-delimited tokens and calls `android_event_from_str` on each token before forwarding that token. Negative parser returns emit KO and stop the loop. Each earlier successful token has already been sent through `sendGenericEvent`; there is no batch pre-validation or rollback. A later parse failure therefore does not imply zero side effects. The console fixes displayId to zero. [console.cpp2145–2196](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-emu/android/console.cpp#2145).

The function is declared in `android/hw-events.h` and implemented in `hw-events.c`. It looks up type/code aliases, falling back to `strtol(..., base0)` for numeric type/code. Value also uses signed `strtol`. Each conversion checks that its end pointer reached the chosen delimiter, returning -1/-2/-3 for type/code/value parse errors. Thus canonical `3:57:-1` produces EV_ABS, ABS_MT_TRACKING_ID and integer value -1. The header defines that tracking code as hexadecimal0x39, decimal57. [hw-events.c142–202](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-emu/android/hw-events.c#142), [hw-events.h500–569](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android/android-emu/android/hw-events.h#500).

This is not a complete grammar/range validator. Outputs start at zero and missing later fields can return success with defaults. An additional colon after value can leave an ignored suffix. The function does not check errno, numeric overflow or actual guest capabilities. The console parses through a 128-byte temporary buffer, so long tokens are not a suitable validation boundary. The driver must validate all complete canonical decimal triples, allowed codes and real ranges before its first mutating call. A KO after dispatch begins still requires its separately planned release/recovery.

## Conditional backend route

The agent's `sendGenericEvent` points to `user_event_generic`. Only when `kFeature_VirtioInput` is enabled does it try `android_virtio_input_send`; otherwise or when that helper returns false it falls back to goldfish. [qemu-user-event-agent-impl.c73–96 and152–166](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android-qemu2-glue/qemu-user-event-agent-impl.c#73).

For supported ABS/KEY/SYN/SW types, the virtio helper indexes `s_virtio_input_multi_touch[displayId]`. Display0 therefore selects array0. Instance/configuration ID1 initializes array(id-1) with the device named `virtio_input_multi_touch_1`. The code converts value to a 32-bit little-endian event field; numeric -1 retains the all-ones bit pattern. [virtio-input-multi-touch.c38–50,317–325 and398–414](https://android.googlesource.com/platform/external/qemu/+/686efa16baf59d776cadc3f975d12570fe44bbb9/android-qemu2-glue/emulation/virtio-input-multi-touch.c#398).

Critically, return1 is outside `if(vinput)`. A missing vinput can yield return1 without calling `virtio_input_send`, which also suppresses the agent's goldfish fallback. Console OK, a helper return, the source name mapping and a prior capability getter are not proof that this particular guest received a frame.

A separately authorized passive trace must retain the actual complete tracking/position/pressure/SYN/release sequence on the freshly attributed guest device. It must compare the observed slot and release, not assume successful kernel delivery from an acknowledgment. Even a matching kernel trace alone would not establish TalkBack recognition; reader state, focus, speech and viewport checks remain separate evidence.
