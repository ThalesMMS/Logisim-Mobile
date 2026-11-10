"""Pure source-derived AOSP15 text checks. No process, device or file I/O."""
import re

PACKAGE = "com.google.android.marvin.talkback"
FULL = PACKAGE + "/" + PACKAGE + ".TalkBackService"
SHORT = PACKAGE + "/.TalkBackService"
_HEX = r"[0-9a-fA-F]+"
_SERVICE = re.compile(r"  \* ServiceRecord\{(" + _HEX + r") u(\d+) (\S+)(?: c:\S+)?\}")
_BINDING = re.compile(r"    \* IntentBindRecord\{(" + _HEX + r")(?: CREATE)?\}:")
_CLIENT = re.compile(r"      \* Client AppBindRecord\{(" + _HEX + r") ProcessRecord\{" + _HEX + r" (\d+):([^/ ]+)/(\d+)\}\}")
_CONNECTION = re.compile(r"ConnectionRecord\{(" + _HEX + r") u(\d+) ((?:\S+ )*)(\S+):@(" + _HEX + r") flags=0x(" + _HEX + r")\}")
_PROCESS = re.compile(r"ProcessRecord\{" + _HEX + r" (\d+):([^/ ]+)/(\S+)\}")
_EVENT_NAMES_BY_BIT = (
    "TYPE_VIEW_CLICKED", "TYPE_VIEW_LONG_CLICKED", "TYPE_VIEW_SELECTED", "TYPE_VIEW_FOCUSED",
    "TYPE_VIEW_TEXT_CHANGED", "TYPE_WINDOW_STATE_CHANGED", "TYPE_NOTIFICATION_STATE_CHANGED",
    "TYPE_VIEW_HOVER_ENTER", "TYPE_VIEW_HOVER_EXIT", "TYPE_TOUCH_EXPLORATION_GESTURE_START",
    "TYPE_TOUCH_EXPLORATION_GESTURE_END", "TYPE_WINDOW_CONTENT_CHANGED", "TYPE_VIEW_SCROLLED",
    "TYPE_VIEW_TEXT_SELECTION_CHANGED", "TYPE_ANNOUNCEMENT", "TYPE_VIEW_ACCESSIBILITY_FOCUSED",
    "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED", "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY",
    "TYPE_GESTURE_DETECTION_START", "TYPE_GESTURE_DETECTION_END", "TYPE_TOUCH_INTERACTION_START",
    "TYPE_TOUCH_INTERACTION_END", "TYPE_WINDOWS_CHANGED", "TYPE_VIEW_CONTEXT_CLICKED",
    "TYPE_ASSIST_READING_CONTEXT", "TYPE_SPEECH_STATE_CHANGE", "TYPE_VIEW_TARGETED_BY_SCROLL",
)
_EVENT_ATOM_BITS = {name: bit for bit, name in enumerate(_EVENT_NAMES_BY_BIT)}
_EVENT_ATOM_BITS.update({format(1 << bit, "x"): bit for bit in range(27, 32)})


class BindingError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def canonical_component(value):
    return FULL if value in (FULL, SHORT) else None


def _event_type_text(value):
    """Recognize Android15 AccessibilityEvent.eventTypeToString, not ServiceInfo."""
    if value in ("", "TYPES_ALL_MASK"):
        return True
    if value.startswith("[") and value.endswith("]"):
        atoms = value[1:-1].split(", ")
        if not 2 <= len(atoms) <= 31:
            return False
    else:
        atoms = [value]
    bits = [_EVENT_ATOM_BITS.get(atom) for atom in atoms]
    return None not in bits and bits == sorted(set(bits))


def _indent(line):
    return len(line) - len(line.lstrip(" "))


def _following(lines, start, indent):
    end = start + 1
    while end < len(lines) and (not lines[end].strip() or _indent(lines[end]) > indent):
        end += 1
    return lines[start + 1:end], end


def _field(lines, indent, name):
    prefix = " " * indent + name + "="
    values = [line[len(prefix):] for line in lines if line.startswith(prefix)]
    if len(values) != 1:
        raise BindingError("missing_or_duplicate_field", "Expected exactly one " + name)
    return values[0]


def _intent_component(value):
    if not value.startswith("{") or not value.endswith("}"):
        return None
    matches = re.findall(r"(?:^|\s)cmp=([^\s}]+)", value[1:-1])
    return canonical_component(matches[0]) if len(matches) == 1 else None


def _connection(line):
    match = _CONNECTION.fullmatch(line.strip())
    if not match:
        return None
    token, user, labels, component, binder, flags = match.groups()
    if canonical_component(component) is None or "DEAD" in labels.split() or int(user) != 0:
        return None
    return (token.lower(), FULL, binder.lower(), int(flags, 16))


def check_activity_binding(text, *, system_server_pid, user_id=0):
    """Check one active exact service, one published binding and its system client."""
    if type(system_server_pid) is not int or system_server_pid <= 0:
        raise BindingError("system_pid_unknown", "A fresh external system_server PID is required")
    if type(user_id) is not int or user_id < 0:
        raise BindingError("invalid_user", "Explicit expected Android user required")
    lines = text.splitlines()
    if lines.count("ACTIVITY MANAGER SERVICES (dumpsys activity services)") != 1:
        raise BindingError("missing_header", "Missing or duplicate AMS services header")
    records = []
    active_user = None
    index = 0
    while index < len(lines):
        line = lines[index]
        user = re.fullmatch(r"  User (\d+) active services:", line)
        service = _SERVICE.fullmatch(line)
        if user:
            active_user = int(user.group(1))
        elif service and active_user is not None:
            body, end = _following(lines, index, 2)
            token, record_user, component = service.groups()
            if active_user == user_id:
                if canonical_component(component) is None:
                    raise BindingError("wrong_component", "Foreign active service in the component-filtered profile")
                if int(record_user) != user_id:
                    raise BindingError("wrong_user", "Record and active-user section disagree")
                records.append((token.lower(), body))
            index = end
            continue
        elif line.strip() and _indent(line) <= 2:
            active_user = None
        index += 1
    if len(records) != 1:
        raise BindingError("no_unique_active_service", "Expected exactly one active TalkBack ServiceRecord for the selected user")
    service_token, body = records[0]
    if _intent_component(_field(body, 4, "intent")) != FULL or _field(body, 4, "packageName") != PACKAGE:
        raise BindingError("wrong_service_identity", "Root service intent/package do not identify TalkBack")
    process_name = _field(body, 4, "processName")
    app = _PROCESS.fullmatch(_field(body, 4, "app"))
    if app is None or int(app.group(1)) <= 0 or app.group(2) != process_name:
        raise BindingError("no_service_process", "Service must retain a matching non-null process record")
    all_indexes = [i for i, line in enumerate(body) if line == "    All Connections:"]
    if len(all_indexes) != 1:
        raise BindingError("missing_all_connections", "Need the ServiceRecord connection list")
    all_body, _ = _following(body, all_indexes[0], 4)
    all_connections = {_connection(line) for line in all_body if line.strip()}
    all_connections.discard(None)
    binding_markers = [i for i, line in enumerate(body) if line == "    Bindings:"]
    if len(binding_markers) != 1 or binding_markers[0] >= all_indexes[0]:
        raise BindingError("missing_bindings", "No scoped Bindings section")
    candidates = []
    for offset in range(binding_markers[0] + 1, all_indexes[0]):
        marker = _BINDING.fullmatch(body[offset])
        if marker is None:
            continue
        binding, _ = _following(body, offset, 4)
        if _intent_component(_field(binding, 6, "intent")) != FULL:
            continue
        binder = _field(binding, 6, "binder")
        if re.fullmatch(r"android\.os\.BinderProxy@" + _HEX, binder) is None:
            continue
        states = [line for line in binding if line.startswith("      requested=")]
        if states != ["      requested=true received=true hasBound=true doRebind=false"]:
            continue
        for position, line in enumerate(binding):
            client = _CLIENT.fullmatch(line)
            if client is None:
                continue
            client_token, pid, process, uid = client.groups()
            if (int(pid), process, int(uid)) != (system_server_pid, "system", 1000):
                continue
            client_body, _ = _following(binding, position, 6)
            if client_body.count("        Per-process Connections:") != 1:
                continue
            connections = [_connection(item) for item in client_body if item.startswith("          ConnectionRecord{")]
            connections = [item for item in connections if item is not None and item in all_connections]
            if len(connections) == 1:
                candidates.append({"binding_token": marker.group(1).lower(), "service_binder": binder, "client_token": client_token.lower(), "connection": connections[0]})
    if len(candidates) != 1:
        raise BindingError("no_unique_published_system_binding", "Need one published binding with its matching system client and live connection record")
    return {"result": "PASS_AMS_PUBLISHED_SYSTEM_BINDING_ONLY", "component": FULL, "user_id": user_id, "service_record_token": service_token, "service_process_pid": int(app.group(1)), "service_process_name": process_name, "system_server_pid": system_server_pid, **candidates[0]}


def check_accessibility_state(text, *, user_id=0):
    """Corroborate one bound spoken/touch-capable record, without inventing its ID."""
    starts = [match.start() for match in re.finditer(r"(?m)^User state\[$", text)]
    selected = []
    for start, end in zip(starts, starts[1:] + [len(text)]):
        region = text[start:end]
        client_lists = list(re.finditer(r"(?m)^     Client list info:\{$", region))
        registered = list(re.finditer(r"(?m)^          Registered clients:\{$", region))
        if len(client_lists) != 1 or len(registered) != 1 or client_lists[0].end() >= registered[0].start():
            raise BindingError("truncated_user_state", "Missing scoped Client list / Registered clients footer")
        before_registered = region[:registered[0].start()]
        if re.search(r"(?m)^\]$|\}\]", before_registered):
            raise BindingError("malformed_user_state", "User state closes before its Registered clients footer")
        summary = region[client_lists[0].end():registered[0].start()]
        if any(line.strip() and not line.startswith("          Client list ") for line in summary.splitlines()):
            raise BindingError("malformed_user_state", "Unexpected data in Client list summary")
        package = r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*"
        array = r"\[(?:" + package + r"(?:, " + package + r")*)?\]"
        tail = region[registered[0].end():]
        closing = re.match(r"(?:\s*(?:" + array + r"|null))*\s*\}\](?=\n|$)", tail)
        if closing is None:
            raise BindingError("truncated_user_state", "Registered client arrays have no source-defined suffix close")
        block = region[:registered[0].end() + closing.end()]
        attributes = re.findall(r"(?m)^     attributes:\{id=(\d+), touchExplorationEnabled=(true|false),[^\n]*\}$", block)
        if len(attributes) == 1 and int(attributes[0][0]) == user_id:
            selected.append((attributes[0][1], block))
    if len(selected) != 1 or selected[0][0] != "true":
        raise BindingError("touch_exploration_not_confirmed", "Need the selected user's unique true touch-exploration state")
    blocks = re.findall(r"(?ms)^     Bound services:\{(.*?)\}\n     Enabled services:", selected[0][1])
    if len(blocks) != 1 or blocks[0].count("Service[") != 1 or "\n" in blocks[0]:
        raise BindingError("no_unique_bound_record", "Need one unambiguous bound record; its component is absent")
    bound = re.fullmatch(r"Service\[label=(.*), feedbackType\[([^\]]*)\], capabilities=(\d+), eventTypes=(.*), notificationTimeout=(\d+), requestA11yBtn=(true|false)\]", blocks[0])
    if bound is None:
        raise BindingError("unknown_bound_format", "Bound record differs from the source-derived profile")
    label, feedback, capabilities, event_types, timeout, button = bound.groups()
    if not _event_type_text(event_types):
        raise BindingError("unknown_event_type_format", "Bound event types differ from the Android15 formatter")
    if "FEEDBACK_SPOKEN" not in [item.strip() for item in feedback.split(",")] or not int(capabilities) & 2:
        raise BindingError("bound_record_not_spoken_touch_capable", "Need spoken feedback and touch-exploration capability in the actual bound record")
    return {"user_id": user_id, "touch_exploration_enabled": True, "bound_record_count": 1, "bound_label_observed_only": label, "bound_feedback": feedback, "bound_capabilities": int(capabilities), "component_in_bound_dump": None}


def check_cross_evidence(activity_dump, accessibility_dump, *, system_server_pid, user_id=0):
    ams = check_activity_binding(activity_dump, system_server_pid=system_server_pid, user_id=user_id)
    accessibility = check_accessibility_state(accessibility_dump, user_id=user_id)
    return {"result": "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY", "ams": ams, "accessibility": accessibility,
            "identity_join": "CORRELATION_ONLY; accessibility Bound record has no component field",
            "limits": ["Dumps are separate non-atomic observations", "AMS flags/binder do not prove the client callback completed or Binder liveness now", "The Bound record cannot be joined to TalkBack by an absent component field", "No reader events, gesture, focus, speech or workflow qualification", "Fresh command/serial/user/system PID attribution and controlled public setup remain external evidence"]}
