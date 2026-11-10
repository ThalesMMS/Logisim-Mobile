"""Pure checker for the frozen single-device console gesture contract."""
from dataclasses import dataclass
import re

DEVICE = "/dev/input/event1"
DEVICE_NAME = "virtio_input_multi_touch_1"
EV_SYN, EV_ABS = 0, 3
SYN_REPORT, SYN_DROPPED = 0, 3
SLOT, X, Y, TOOL_TYPE, TRACKING, PRESSURE = 0x2F, 0x35, 0x36, 0x37, 0x39, 0x3A
EXPECTED_X = tuple(x * 32767 // 1080 for x in range(300, 701, 50))
EXPECTED_Y = 800 * 32767 // 2400
_EVENT = re.compile(r"\[\s*(\d+)\.(\d{6})\]\s+([0-9a-fA-F]{4})\s+([0-9a-fA-F]{4})\s+([0-9a-fA-F]{8})\s*")


class TraceError(ValueError):
    def __init__(self, code, message, line=None):
        super().__init__(message)
        self.code, self.line = code, line


@dataclass(frozen=True)
class InitialState:
    # All supplied values require a fresh independent getter. No trajectory defaults.
    selected_slot: int | None
    x: int | None = None
    y: int | None = None
    pressure: int | None = None


@dataclass(frozen=True)
class Event:
    timestamp_us: int
    event_type: int
    code: int
    value: int
    line: int


def parse_trace(text):
    """Parse only the -t -v6 /dev/input/event1 profile, including its readiness pair."""
    events = []
    headers = 0
    previous_time = None
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        if line == "add device 1: " + DEVICE:
            if headers or events:
                raise TraceError("unexpected_header", "Duplicate or late device header", number)
            headers = 1
            continue
        if line == '  name:     "' + DEVICE_NAME + '"':
            if headers != 1 or events:
                raise TraceError("unexpected_header", "Name must immediately follow device header", number)
            headers = 2
            continue
        match = _EVENT.fullmatch(line)
        if match is None:
            raise TraceError("unrecognized_line", "Not a numeric event or the frozen readiness header", number)
        if headers != 2:
            raise TraceError("missing_readiness", "Expected the complete -v6 device/name header before events", number)
        seconds, micros, event_type, code, value = match.groups()
        timestamp = int(seconds) * 1_000_000 + int(micros)
        if previous_time is not None and timestamp < previous_time:
            raise TraceError("timestamp_regression", "Event timestamps went backwards", number)
        previous_time = timestamp
        events.append(Event(timestamp, int(event_type, 16), int(code, 16), int(value, 16), number))
    if headers != 2:
        raise TraceError("missing_readiness", "Missing the frozen -v6 readiness pair")
    if not events:
        raise TraceError("empty_trace", "No input events were retained")
    return events


def check_trace(text, initial):
    """Return a kernel-sequence report, or raise TraceError. No I/O or device actions."""
    if type(initial.selected_slot) is not int or initial.selected_slot != 0:
        raise TraceError("initial_slot_unknown_or_wrong", "Fresh initial selected slot must explicitly be 0")
    for name, value, maximum in [("x", initial.x, 32767), ("y", initial.y, 32767), ("pressure", initial.pressure, 1024)]:
        if value is not None and (type(value) is not int or not 0 <= value <= maximum):
            raise TraceError("invalid_initial_axis", "Invalid initial " + name)
    events = parse_trace(text)
    state = {"x": initial.x, "y": initial.y, "pressure": initial.pressure, "tracking": None}
    frames = []
    pending = 0
    frame_started = False
    frame_released = False
    complete = False
    observed_axes = set()
    inherited_initial_axes = set()

    def reject(code, message, event):
        raise TraceError(code, message, event.line)

    for event in events:
        if event.event_type == EV_SYN and event.code == SYN_DROPPED:
            reject("syn_dropped", "Kernel reported lost events; reconstruction is invalid", event)
        if complete:
            reject("events_after_release", "Events continue after the one completed contact", event)
        if event.event_type == EV_SYN:
            if event.code != SYN_REPORT or event.value != 0:
                reject("unexpected_syn", "Only SYN_REPORT value 0 is allowed", event)
            if frames and event.timestamp_us <= frames[-1]["timestamp_us"]:
                reject("non_increasing_syn", "SYN_REPORT timestamps must be strictly increasing", event)
            if state["tracking"] == 1:
                index = len(frames)
                if index >= 9:
                    reject("extra_active_frame", "More than nine active contact frames", event)
                if index == 0 and not frame_started:
                    reject("missing_down", "First active frame needs an observed tracking ID 1 start", event)
                for axis in ("x", "y", "pressure"):
                    if state[axis] is None:
                        reject("unknown_axis", "No event or independent initial value for " + axis, event)
                    if axis not in observed_axes:
                        inherited_initial_axes.add(axis)
                if (state["x"], state["y"]) != (EXPECTED_X[index], EXPECTED_Y):
                    reject("wrong_position", "Frame does not match the fixed floor-scaled point " + str(index), event)
                if state["pressure"] != 64:
                    reject("wrong_pressure", "Active contact pressure must remain 64", event)
                kind = "down" if index == 0 else "move"
            elif state["tracking"] == -1:
                if not frame_released or frame_started or len(frames) != 9:
                    reject("early_or_invalid_release", "Release must follow exactly nine active frames", event)
                if state["pressure"] != 0:
                    reject("release_pressure_nonzero", "Release requires reconstructed pressure zero", event)
                if (state["x"], state["y"]) != (EXPECTED_X[-1], EXPECTED_Y):
                    reject("wrong_release_position", "Release must remain at the final point", event)
                kind = "up"
                complete = True
            else:
                reject("missing_down", "No observed contact start before SYN_REPORT", event)
            frames.append({"kind": kind, "slot": 0, "tracking": state["tracking"], "pressure": state["pressure"], "x": state["x"], "y": state["y"], "timestamp_us": event.timestamp_us, "syn_line": event.line})
            pending = 0
            frame_started = frame_released = False
            continue
        if event.event_type != EV_ABS:
            reject("unexpected_event_type", "Only the frozen ABS and SYN events are allowed", event)
        pending += 1
        if event.code == SLOT:
            if event.value != 0:
                reject("other_slot", "Another slot was selected", event)
        elif event.code == TRACKING:
            value = event.value if event.value < 0x80000000 else event.value - 0x100000000
            if value == 1:
                if state["tracking"] == -1:
                    reject("second_contact", "Tracking restarted after a release", event)
                if state["tracking"] is None:
                    frame_started = True
                state["tracking"] = 1
            elif value == -1:
                if state["tracking"] != 1:
                    reject("release_without_contact", "Release lacks an observed active tracking ID 1", event)
                state["tracking"] = -1
                frame_released = True
            else:
                reject("wrong_tracking_id", "Only raw tracking ID 1 and release -1 are allowed", event)
        elif event.code == TOOL_TYPE:
            if event.value != 0:
                reject("wrong_tool_type", "Only MT_TOOL_FINGER value 0 is allowed", event)
        elif event.code in (X, Y, PRESSURE):
            name, maximum = {X: ("x", 32767), Y: ("y", 32767), PRESSURE: ("pressure", 1024)}[event.code]
            if not 0 <= event.value <= maximum:
                reject("axis_out_of_range", "Axis exceeds its recorded device range", event)
            state[name] = event.value
            observed_axes.add(name)
        else:
            reject("unexpected_abs", "ABS code is outside the frozen gesture payload", event)
    if pending:
        raise TraceError("missing_final_syn", "Trace ends with an uncommitted ABS frame", events[-1].line)
    if not complete:
        raise TraceError("missing_release", "No complete pressure-zero/tracking-minus-one release frame")
    times = [frame["timestamp_us"] for frame in frames]
    elapsed = times[-1] - times[0]
    if not 120_000 <= elapsed <= 300_000:
        raise TraceError("duration_out_of_contract", "Down-to-UP SYN duration must be 120000..300000 microseconds")
    return {
        "result": "PASS_KERNEL_SEQUENCE_ONLY",
        "device_header": DEVICE,
        "device_name_header": DEVICE_NAME,
        "frames": frames,
        "duration_us": elapsed,
        "duration_basis": "First down SYN_REPORT to final release SYN_REPORT",
        "syn_intervals_us": [b - a for a, b in zip(times, times[1:])],
        "offset_from_nominal_us": [t - times[0] - index * 20_000 for index, t in enumerate(times)],
        "inherited_initial_axes": sorted(inherited_initial_axes),
        "external_evidence_required": ["Exact capture command/process and device attribution", "Fresh independent getter for supplied initial selected slot and axes", "Independent initial slot-0 inactivity evidence if a neutral starting state is claimed", "Capture start/end coverage, stderr and monotonic-clock setup"],
        "not_proven": ["Global initial absence of contacts", "Activity on other input devices", "TalkBack delivery, gesture recognition, focus or speech", "Driver host scheduling or its 50ms lateness-abort policy"],
    }
