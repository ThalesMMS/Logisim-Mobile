# Pure Type B trace checker

Status: `PREPARED_OFFLINE`. No getevent, adb, event, device query, GUI, Xcode or build command was executed by this unit. The only executed verification is the local Python test suite over literal text fixtures.

`check_typeb_trace.py` exports `InitialState`, `parse_trace`, `check_trace` and `TraceError`. Both parser/checker functions use only their arguments and return data or raise an error. They perform no process, device, network or file I/O. `test_check_typeb_trace.py` contains a complete literal numeric trace and positive/negative variants.

## Frozen capture profile

This checker accepts the stdout profile of `getevent -t -v6 /dev/input/event1`. The bounded timeout wrapper belongs to the external driver. The two readiness lines must identify `add device 1: /dev/input/event1` and `virtio_input_multi_touch_1` before the events. The first number is getevent's private descriptor-list index, not the guest input-device ID. The supplied AOSP implementation starts that list at 1 for a single explicitly opened path.

Only the known header pair, blank lines and timestamped numeric events are accepted. Other device/name headers, path-prefixed events, labels, diagnostics, truncated lines and unexpected events fail. This is a deliberately narrow parser, not a general getevent format detector. The actual command, collector lifecycle and device attribution remain external evidence.

The local AOSP15 source is identified in `source-evidence.json`. It prints timestamps as integer seconds and six microsecond digits, and numeric events as four-digit type/code plus eight-digit value. Single-device mode omits the event-line device prefix. Its clock setup failure is nonfatal and goes to stderr. The caller must preserve and assess stderr and cannot infer a successful monotonic-clock setup from stdout alone.

## Reconstructed state

Call `check_trace(text, InitialState(selected_slot=0))` only after retaining a fresh independent getter that explicitly says the CURRENT selected slot is 0. The checker does not invent that default and cannot authenticate freshness. If the first X, Y or pressure is omitted, pass its independently observed initial value as `x`, `y` or `pressure`. Unknown fields fail. Planned coordinates are never substituted for missing observations.

An explicit raw tracking ID 1 must be observed. Logical gRPC identifier 28 is not the raw tracking ID. Within this fixed contract, any selection of another slot or any other tracking ID fails. Unchanged X/Y/pressure and slot-zero selections can be omitted. Their state carries only from the supplied initial values or earlier observed events. A SYN commits and validates the current state. No initial tracking/contact state is invented. An observed tracking-ID start does not independently prove that slot 0 was inactive before the capture. A complete neutral-start claim requires external evidence of initial slot-0 inactivity, in addition to the selected-slot/axis getter. Selected slot 0, pressure zero or silence is not that evidence.

The expected active X values are 9101, 10618, 12135, 13652, 15169, 16686, 18203, 19720 and 21237. Y is 10922. These are `floor(pixel * 32767 / dimension)` for physical X 300 through 700 in increments of 50 and Y 800 on 1080 by 2400. Active pressure is 64. ABS_MT_TOOL_TYPE may be observed and is accepted only with MT_TOOL_FINGER value zero; other tool values fail. Its omission is not assumed from a historical getter. The parser requires exactly nine active frames, then pressure zero and signed tracking ID minus one committed by a final SYN. Missing/merged/extra frames, another contact, out-of-range axes, SYN_DROPPED, Type A synchronization and incomplete release fail.

All event timestamps must be nondecreasing. The ten SYN timestamps must be strictly increasing, and the down-to-UP SYN duration must be between 120000 and 300000 microseconds inclusive. The report preserves each SYN interval and its offset from the nominal 20 ms schedule with nominal UP at 180 ms. These are this validation unit's limits, not a TalkBack gesture rule. The checker does not prove the driver's separate 50 ms host-lateness abort policy from kernel timestamps.

## Result and limits

Success returns `PASS_KERNEL_SEQUENCE_ONLY`, ten reconstructed frames, timing and inherited-initial-axis provenance. `TraceError` retains a failure code and source line when applicable. This checker does not depend on video or speech.

A PASS supports the specific contact sequence visible in this retained stream. It does not prove global initial absence of contacts, inactivity on other input devices, one finger anywhere in the system, capture coverage beyond the retained text, delivery to TalkBack, gesture recognition, reader focus, speech or an app workflow. Silence before the contact is never used as proof that all slots were inactive. Header text and caller arguments are not authentication of the external capture process.

Run the offline suite with `python3 run_offline_tests.py` from this directory. It blocks process/network boundaries and writes the full unittest log and receipt. A direct `python3 -m unittest -v test_check_typeb_trace` also runs the literal-text tests. The final guarded run passed 31 tests with no failures, errors, skips or attempted external process/network operation. No trace collector or event sender is supplied here.
