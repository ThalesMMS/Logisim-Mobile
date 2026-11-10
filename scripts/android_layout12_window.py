"""Strict, offline geometry checks for the Android layout acceptance runner.

The XML tree and WindowManager dump are separate observations. This module does
not link their identities or claim that their capture was atomic.
"""

import math
import re


class WindowObservationError(AssertionError):
    pass


_RECT = r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"
_SOURCES = {"statusBars", "navigationBars", "displayCutout", "ime", "captionBar"}
_REQUIRED_SOURCES = {"statusBars", "navigationBars", "displayCutout", "ime"}


def _one(values, message):
    if len(values) != 1:
        raise WindowObservationError(message)
    return values[0]


def _rect(values, *, allow_empty=False):
    if len(values) != 4 or any(isinstance(value, bool) or not re.fullmatch(r"-?\d+", str(value)) for value in values):
        raise WindowObservationError("Observed rectangle must contain four integer coordinates")
    frame = tuple(map(int, values))
    if len(frame) != 4 or frame[2] < frame[0] or frame[3] < frame[1] or (
            not allow_empty and (frame[2] == frame[0] or frame[3] == frame[1])):
        raise WindowObservationError("Invalid observed rectangle")
    return frame


def _inside(inner, outer):
    return outer[0] <= inner[0] < inner[2] <= outer[2] and outer[1] <= inner[1] < inner[3] <= outer[3]


def _overlap(first, second):
    return max(first[0], second[0]) < min(first[2], second[2]) and max(first[1], second[1]) < min(first[3], second[3])


_EMPTY_RECT = r"Rect\(0, 0 - 0, 0\)"
_NO_CUTOUT = re.compile(r"(?m)^\s*mDisplayCutout=DisplayCutout\{insets=" + _EMPTY_RECT +
                        r" waterfall=Insets\{left=0, top=0, right=0, bottom=0\} boundingRect=\{Bounds=\[" +
                        ", ".join([_EMPTY_RECT] * 4) + r"\]\}")


def _explicitly_no_cutout(body):
    """The InsetsState itself reports exactly one, entirely empty display cutout."""
    cutouts = re.findall(r"(?m)^\s*mDisplayCutout=", body)
    return len(cutouts) == 1 and _NO_CUTOUT.search(body) is not None


def _display_insets(text, display_id, display_frame):
    """Return None only when no usable, explicitly scoped InsetsState exists."""
    headers = list(re.finditer(r"(?m)^\s*Display:\s+mDisplayId=(\d+)\b[^\n]*$", text))
    blocks = []
    for index, header in enumerate(headers):
        if int(header.group(1)) != display_id:
            continue
        end = headers[index + 1].start() if index + 1 < len(headers) else len(text)
        block = text[header.end():end]
        controllers = list(re.finditer(r"(?m)^([ \t]*)WindowInsetsStateController[ \t]*$", block))
        for controller in controllers:
            tail = block[controller.end():]
            # The controller's sections end before the control map or the next
            # peer section. Do not borrow sources from another controller.
            end_match = re.search(r"(?m)^\s*Control map:\s*$|^" + re.escape(controller.group(1)) + r"\S", tail)
            body = tail[:end_match.start()] if end_match else tail
            blocks.append(body)
    if not blocks:
        return None
    body = _one(blocks, "Ambiguous InsetsState for the focused display")
    frame_matches = re.findall(r"(?m)^\s*mDisplayFrame=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)\s*$", body)
    observed_display = _rect(_one(frame_matches, "InsetsState has no unique display frame"))
    if observed_display != display_frame:
        raise WindowObservationError("InsetsState display frame disagrees with the focused window")

    seen_ids, types, occlusions = set(), set(), []
    for line in body.splitlines():
        if "InsetsSource " not in line:
            continue
        match = re.fullmatch(r"\s*InsetsSource id=([0-9A-Fa-f]+) type=(\w+) frame=" + _RECT +
                             r"(?: visibleFrame=" + _RECT + r")? visible=(true|false)(?:\s+.*)?", line)
        if match is None:
            raise WindowObservationError("Malformed observed InsetsSource")
        source_id, kind = match.group(1, 2)
        if source_id in seen_ids:
            raise WindowObservationError("Duplicate InsetsSource identity")
        seen_ids.add(source_id)
        frame = _rect(match.group(3, 4, 5, 6), allow_empty=True)
        visible_frame_values = match.group(7, 8, 9, 10)
        visible_frame = _rect(visible_frame_values, allow_empty=True) if visible_frame_values[0] is not None else None
        visible = match.group(11) == "true"
        if kind not in _SOURCES:
            continue
        types.add(kind)
        if not visible:
            continue
        frame = _rect(frame)
        if not _inside(frame, display_frame):
            raise WindowObservationError("Visible inset lies outside its display")
        if visible_frame is not None:
            visible_frame = _rect(visible_frame)
            if not _inside(visible_frame, frame):
                raise WindowObservationError("Inset visible frame lies outside its source frame")
            frame = visible_frame
        occlusions.append({"type": kind, "frame": list(frame), "sourceId": source_id})
    if "displayCutout" not in types and _explicitly_no_cutout(body):
        # A display without a cutout publishes no displayCutout source. Its
        # InsetsState still states the empty cutout; that is evidence, not inference.
        types.add("displayCutout")
    if not _REQUIRED_SOURCES.issubset(types):
        return {"status": "NOT_AVAILABLE", "reason": "Missing explicit inset types: " +
                ", ".join(sorted(_REQUIRED_SOURCES - types)), "occlusions": []}
    return {"status": "AVAILABLE", "reason": None, "occlusions": occlusions}


def _attached_sub_window(window_dump, headers, token, title, package):
    """The focused window when the app owns it and attaches it to its own activity window."""
    blocks = []
    for index, header in enumerate(headers):
        if header.group(1) == token:
            end = headers[index + 1].start() if index + 1 < len(headers) else len(window_dump)
            blocks.append(window_dump[header.end():end])
    if len(blocks) != 1:
        return None
    owners = re.findall(r"(?m)^\s*mOwnerUid=\d+\s+[^\n]*\bpackage=([^\s]+)", blocks[0])
    parents = re.findall(r"(?m)^\s*mParentWindow=Window\{(\S+) u\d+ ([^{}\n]+)\}", blocks[0])
    types = re.findall(r"\bty=(APPLICATION_(?:SUB_)?PANEL|APPLICATION_ATTACHED_DIALOG|APPLICATION_MEDIA)\b", blocks[0])
    if owners != [package] or len(parents) != 1 or len(types) != 1:
        return None
    parent_token, parent_title = parents[0]
    if not parent_title.startswith(package + "/"):
        return None
    return {"token": token, "title": title, "parentToken": parent_token, "parentTitle": parent_title}


def parse_window_observation(window_dump, package, display_size, activity_dump=None):
    """Read one current app window, with explicit or unavailable inset evidence.

    Pass `dumpsys window -a` in full. An independently captured `dumpsys activity
    activities` may supply the display's InsetsState only when window has none.
    Windowing mode remains the observed literal. `multi-window` alone does not
    prove a split-screen gesture, another app, or a divider.
    """
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", package):
        raise ValueError("Invalid app package")
    if len(display_size) != 2 or any(isinstance(value, bool) or not isinstance(value, int) or value <= 0 for value in display_size):
        raise ValueError("Display size must be two positive screenshot pixel dimensions")
    screenshot_frame = (0, 0, *display_size)
    focuses = re.findall(r"(?m)^\s*mCurrentFocus=Window\{(\S+) u\d+ ([^{}\n]+)\}\s*$", window_dump)
    token, title = _one(focuses, "No unique current WindowManager focus")
    headers = list(re.finditer(r"(?m)^\s*Window #\d+ Window\{(\S+) u\d+ ([^{}\n]+)\}:\s*$", window_dump))
    sub_window = None
    if not title.startswith(package + "/"):
        # An open menu or popup of the app is a sub-window attached to its activity
        # window. The activity window still supplies the frame and configuration.
        sub_window = _attached_sub_window(window_dump, headers, token, title, package)
        if sub_window is None:
            raise WindowObservationError("WindowManager focus belongs to another application")
        token, title = sub_window["parentToken"], sub_window["parentTitle"]
    selected = []
    for index, header in enumerate(headers):
        if header.group(1) != token:
            continue
        end = headers[index + 1].start() if index + 1 < len(headers) else len(window_dump)
        selected.append((header, window_dump[header.end():end]))
    header, block = _one(selected, "Focused WindowManager token has no unique window block")
    if header.group(2) != title:
        raise WindowObservationError("Focused window title changed within its dump")
    owners = re.findall(r"(?m)^\s*mOwnerUid=\d+\s+[^\n]*\bpackage=([^\s]+)", block)
    if _one(owners, "Focused window has no unique owner") != package:
        raise WindowObservationError("Focused window owner disagrees with its title")
    for pattern in (r"\bmHasSurface=(true|false)\b", r"(?m)^\s*isOnScreen=(true|false)\s*$", r"(?m)^\s*isVisible=(true|false)\s*$"):
        if _one(re.findall(pattern, block), "Focused window has no unique visibility state") != "true":
            raise WindowObservationError("Focused app window is stale or not visible")
    display_ids = re.findall(r"(?m)^\s*mDisplayId=(\d+)\b", block)
    display_id = int(_one(display_ids, "Focused window has no unique display ID"))
    if display_id != 0:
        raise WindowObservationError("Default screencap cannot identify a non-primary focused display")
    frames = re.findall(r"(?m)^\s*Frames:\s+[^\n]*?\bdisplay=" + _RECT + r"\s+frame=" + _RECT + r"(?:\s|$)", block)
    values = _one(frames, "Focused window has no unique frame observation")
    display_frame, app_frame = _rect(values[:4]), _rect(values[4:])
    if display_frame != screenshot_frame or not _inside(app_frame, screenshot_frame):
        raise WindowObservationError("Focused app frame is clipped or its display differs from the screenshot")
    configurations = re.findall(r"(?m)^\s*mFullConfiguration=[^\n]*\bmWindowingMode=([^\s}]+)[^\n]*$", block)
    mode = _one(configurations, "Focused window has no unique current windowing mode")
    observation = {"package": package, "windowToken": token, "displayId": display_id,
                   "appFrame": list(app_frame), "displayFrame": list(display_frame), "windowingMode": mode}
    if sub_window is not None:
        observation["focusedSubWindow"] = {"token": sub_window["token"], "title": sub_window["title"]}
    insets = _display_insets(window_dump, display_id, display_frame)
    source = "window"
    if insets is None and activity_dump is not None:
        insets = _display_insets(activity_dump, display_id, display_frame)
        source = "activity-activities"
    insets = insets or {"status": "NOT_AVAILABLE", "reason": "No explicitly scoped InsetsState for the focused display", "occlusions": []}
    return {**observation, "insetsStatus": insets["status"], "insetsReason": insets["reason"],
            "insetsSource": source if insets["status"] == "AVAILABLE" else None,
            "occlusions": insets["occlusions"]}


def assert_control_rect(frame, observation, density, minimum_dp=48):
    """Require a complete observed action rectangle outside native occlusions.

    The caller must separately validate the current XML control's identity,
    enabled/clickable state, and containment in its scrolling ancestors.
    """
    if isinstance(density, bool) or not isinstance(density, (int, float)) or not math.isfinite(density) or density <= 0:
        raise ValueError("Density must be positive DPI")
    if isinstance(minimum_dp, bool) or not isinstance(minimum_dp, (int, float)) or not math.isfinite(minimum_dp) or minimum_dp <= 0:
        raise ValueError("Minimum target must be positive dp")
    if observation.get("insetsStatus") != "AVAILABLE":
        raise WindowObservationError("Cannot qualify safe geometry without available inset evidence")
    frame = _rect(frame)
    if not _inside(frame, observation["appFrame"]):
        raise WindowObservationError("The entire control must fit its observed app window")
    size_dp = [(frame[2] - frame[0]) * 160 / density, (frame[3] - frame[1]) * 160 / density]
    if any(size < minimum_dp for size in size_dp):
        raise WindowObservationError("Observed action is smaller than the complete dp target")
    for source in observation["occlusions"]:
        if _overlap(frame, source["frame"]):
            raise WindowObservationError("Observed action overlaps " + source["type"])
    return {"frame": list(frame), "sizeDp": size_dp, "minimumDp": minimum_dp}
