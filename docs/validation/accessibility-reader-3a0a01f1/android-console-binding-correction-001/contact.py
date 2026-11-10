"""One fixed raw Type B contact. Importing this module performs no I/O."""

from dataclasses import dataclass
import time

SERIAL = "emulator-5556"
DEVICE = "/dev/input/event1"
DEVICE_NAME = "virtio_input_multi_touch_1"
ADB = "/Users/thales/Library/Android/sdk/platform-tools/adb"
SLOT = 0
TRACKING_ID = 1
PRESSURE = 64
FRAMEBUFFER = (1080, 2400)


@dataclass(frozen=True)
class Frame:
    name: str
    offset_ms: int
    events: tuple[tuple[int, int, int], ...]

    def argv(self):
        return [ADB, "-s", SERIAL, "emu", "event", "send",
                *(f"{kind}:{code}:{value}" for kind, code, value in self.events)]


def frames():
    result = []
    for index, pixel_x in enumerate(range(300, 701, 50)):
        x = pixel_x * 32767 // FRAMEBUFFER[0]
        y = 800 * 32767 // FRAMEBUFFER[1]
        events = [(3, 47, SLOT)]
        if index == 0:
            events += [(3, 57, TRACKING_ID), (3, 55, 0)]
        events += [(3, 53, x), (3, 54, y), (3, 58, PRESSURE), (0, 0, 0)]
        result.append(Frame("down" if index == 0 else f"move-{index}", index * 20, tuple(events)))
    result.append(Frame("up", 180, ((3, 47, SLOT), (3, 58, 0), (3, 57, -1), (0, 0, 0))))
    validate(result)
    return tuple(result)


def validate(sequence):
    if len(sequence) != 10 or sequence[0].name != "down" or sequence[-1].name != "up":
        raise ValueError("One down, eight moves and one up required")
    ranges = {47: (0, 10), 53: (0, 32767), 54: (0, 32767), 55: (0, 15),
              57: (0, 65535), 58: (0, 1024)}
    for frame in sequence:
        if frame.events[-1] != (0, 0, 0):
            raise ValueError("Every frame must terminate in SYN_REPORT")
        for kind, code, value in frame.events:
            if any(type(item) is not int for item in (kind, code, value)):
                raise ValueError("Only canonical integer triples are allowed")
            if (kind, code, value) == (0, 0, 0):
                continue
            if kind != 3 or code not in ranges:
                raise ValueError("Unsupported event")
            if code == 57 and frame.name == "up" and value == -1:
                continue
            lower, upper = ranges[code]
            if not lower <= value <= upper:
                raise ValueError("Out-of-range event")
    if sequence[-1].events != ((3, 47, 0), (3, 58, 0), (3, 57, -1), (0, 0, 0)):
        raise ValueError("The mandatory close cannot be replaced")


def console_ok(exit_code, stdout):
    return exit_code == 0 and stdout.strip() == b"OK"


def transmit(send, clock=time.monotonic, sleep=time.sleep):
    """Send at most one contact; always attempt its close after the first request.

    No acknowledgment qualifies delivery or reader behavior. The caller must
    inspect the independent kernel trace and restore the public reader settings.
    """
    sequence = frames()
    start = clock()
    operation_error = None
    release_error = None
    release_wait_error = None
    may_have_contact = False
    attempted = []
    acknowledged = []
    try:
        for frame in sequence[:-1]:
            target = start + frame.offset_ms / 1000
            sleep(max(0, target - clock()))
            if clock() - target > 0.050:
                raise TimeoutError("Contact sample missed its scheduling window")
            may_have_contact = True
            attempted.append(frame.name)
            send(frame)
            acknowledged.append(frame.name)
    except BaseException as error:
        operation_error = type(error).__name__
    finally:
        if may_have_contact:
            try:
                if operation_error is None:
                    sleep(max(0, start + 0.180 - clock()))
            except BaseException as error:
                release_wait_error = type(error).__name__
            finally:
                try:
                    attempted.append("up")
                    send(sequence[-1])
                    acknowledged.append("up")
                except BaseException as error:
                    release_error = type(error).__name__
    return {"status": "COMMAND_SEQUENCE_ONLY_REQUIRES_KERNEL_AND_READER_REVIEW",
            "attempted_frames": attempted, "acknowledged_frames": acknowledged,
            "operation_error": operation_error,
            "release_error": release_error, "release_wait_error": release_wait_error,
            "contact_possible": may_have_contact,
            "kernel_close_proven": False, "reader_qualified": False}
