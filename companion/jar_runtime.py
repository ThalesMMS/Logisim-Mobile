"""Execute a circuit with one uploaded Evolution library under the companion sandbox."""
import base64
import hashlib
import os
from pathlib import Path
import re
import shutil
import threading
import xml.etree.ElementTree as ET

from companion.api import JobFailure, Operation


def _stage(context, name, *, source=None, text=None):
    directory = context.working_directory / "jar-runtime"
    directory.mkdir(exist_ok=True)
    destination = directory / name
    if source is not None:
        shutil.copyfile(source, destination)
    else:
        destination.write_text(text, encoding="utf-8")
    # The existing OS sandbox grants reads to these specific regular staged files.
    context.input_files[f"runtime:{name}"] = destination
    return destination


def _encoded(value):
    return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")


def _load_runtime():
    configured = os.environ.get("LOGISIM_COMPANION_EVOLUTION_JAR")
    if not configured:
        raise JobFailure("missing-evolution-runtime", "Set LOGISIM_COMPANION_EVOLUTION_JAR on the host.")
    try:
        path = Path(configured)
        if not path.is_absolute():
            raise ValueError("The runtime path must be absolute.")
        path = path.resolve(strict=True)
        if not path.is_file() or not 0 < path.stat().st_size <= 128 * 1024 * 1024:
            raise ValueError("The runtime must be a regular JAR within the host size limit.")
        return path
    except (OSError, ValueError) as error:
        raise JobFailure("invalid-evolution-runtime", str(error)) from error


def _descriptor(xml, class_name):
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise JobFailure("invalid-jar-project", f"The circuit XML could not be read: {error}") from error
    if root.tag != "project":
        raise JobFailure("invalid-jar-project", "A Logisim project document is required.")
    if root.findall("vhdl"):
        raise JobFailure("unsupported-jar-project", "This operation does not run external HDL.")
    descriptors = [library.get("desc", "") for library in root.findall("lib")]
    external = [item for item in descriptors if not item.startswith("#")]
    unique = set(external)
    if len(unique) != 1 or not external[0].startswith("jar#") or external[0].rsplit("#", 1)[-1] != class_name:
        raise JobFailure("unsupported-jar-project", "The project must reference only the approved JAR library class.")
    return external[0]


def _frames(frames):
    if not isinstance(frames, list) or not 1 <= len(frames) <= 256:
        raise JobFailure("invalid-parameters", "Supply 1–256 input frames.")
    lines = []
    for frame in frames:
        if not isinstance(frame, dict) or len(frame) > 64:
            raise JobFailure("invalid-parameters", "Each frame may assign at most 64 labeled inputs.")
        assignments = []
        for label, bits in frame.items():
            if (not isinstance(label, str) or not 1 <= len(label) <= 128
                    or not isinstance(bits, str) or not re.fullmatch(r"[01?E]{1,64}", bits)):
                raise JobFailure("invalid-parameters", "Each labeled input needs 1–64 logic bits.")
            assignments.append(f"{_encoded(label)}={bits}")
        lines.append("\t".join(sorted(assignments)))
    return "\n".join(lines) + "\n"


def _point_path(path):
    if not re.fullmatch(r"(?:/-?[0-9]{1,10},-?[0-9]{1,10}){0,24}", path):
        raise ValueError("Invalid circuit instance path.")
    return [list(map(int, pair.split(","))) for pair in path.split("/")[1:]]


def _trace_instance(instances, values):
    identity, path, circuit, factory, x, y, left, top, width, height = values
    origin = [int(x), int(y)]
    bounds = [int(value) for value in (left, top, width, height)]
    if (identity in instances or len(instances) >= 128 or identity != f"{path}/{origin[0]},{origin[1]}"
            or not circuit or not factory or bounds[2] < 0 or bounds[3] < 0):
        raise ValueError("Invalid JAR instance geometry.")
    instances[identity] = {"id": identity, "path": _point_path(path), "circuit": circuit,
                           "factory": factory, "location": origin, "bounds": bounds, "terminals": []}


def _trace_terminal(instances, values):
    identity, index, x, y, direction, width, exclusive = values
    terminals = instances[identity]["terminals"]
    width = int(width)
    if (int(index) != len(terminals) or direction not in {"input", "output", "inout"}
            or not 1 <= width <= 64 or exclusive not in {"true", "false"}):
        raise ValueError("Invalid JAR terminal geometry.")
    terminals.append({"position": [int(x), int(y)], "direction": direction, "width": width,
                      "exclusive": exclusive == "true"})


def _trace_value(instances, samples, values, driver=False):
    frame, identity, index, bits = values
    frame, index = int(frame), int(index)
    terminals = instances[identity]["terminals"]
    if not 0 <= frame < len(samples) or not 0 <= index < len(terminals):
        raise ValueError("Invalid JAR terminal sample identity.")
    terminal = terminals[index]
    sample = samples[frame].setdefault(identity, {})
    if index in sample or (driver and terminal["direction"] == "input"):
        raise ValueError("Unexpected JAR terminal sample.")
    if driver and bits == "Z":
        sample[index] = bits
        return
    if len(bits) != terminal["width"] or not re.fullmatch(r"[01xEX?]+", bits):
        raise ValueError("Invalid JAR terminal sample.")
    sample[index] = bits.translate(str.maketrans({"x": "?", "X": "?"}))


def _records(stdout, class_name, frame_count, instance_trace=False, driver_trace=False):
    ports, frames, complete = {}, [dict() for _ in range(frame_count)], None
    instances, samples = {}, [dict() for _ in range(frame_count)]
    drivers = [dict() for _ in range(frame_count)]
    try:
        for line in stdout.splitlines():
            if not line.startswith("LOGISIM_JAR_V1\t"):
                continue
            _, kind, *items = line.split("\t")
            values = [base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode() for value in items]
            if kind == "error" and len(values) == 1:
                raise JobFailure("incompatible-jar", values[0][:500])
            if kind == "driver-error" and len(values) == 1:
                raise JobFailure("unsupported-jar-driver-trace", values[0][:500])
            if kind == "port" and len(values) == 3:
                name, direction, width = values
                if name in ports or direction not in {"input", "output"} or not 1 <= int(width) <= 64:
                    raise ValueError("Invalid port.")
                ports[name] = {"name": name, "direction": direction, "width": int(width)}
            elif kind == "output" and len(values) == 3:
                index, name, bits = values
                index = int(index)
                port = ports[name]
                if not 0 <= index < frame_count or name in frames[index] or port["direction"] != "output":
                    raise ValueError("Invalid output observation.")
                if len(bits) != port["width"] or not re.fullmatch(r"[01xEX?]+", bits):
                    raise ValueError("Invalid output logic values.")
                frames[index][name] = bits.translate(str.maketrans({"x": "?", "X": "?"}))
            elif instance_trace and kind == "instance" and len(values) == 10:
                _trace_instance(instances, values)
            elif instance_trace and kind == "terminal" and len(values) == 7:
                _trace_terminal(instances, values)
            elif instance_trace and kind == "terminal-value" and len(values) == 4:
                _trace_value(instances, samples, values)
            elif instance_trace and driver_trace and kind == "driver-value" and len(values) == 4:
                _trace_value(instances, drivers, values, driver=True)
            elif kind == "complete" and len(values) == 4 and complete is None:
                complete = values
            else:
                raise ValueError("Unexpected runner record.")
        outputs = {name for name, port in ports.items() if port["direction"] == "output"}
        if (complete is None or complete[0] != class_name or int(complete[3]) != frame_count
                or not outputs or any(frame.keys() != outputs for frame in frames)):
            raise ValueError("The runner did not return a complete result.")
        if instance_trace:
            indexes = {identity: set(range(len(item["terminals"]))) for identity, item in instances.items()}
            if (not indexes or sum(map(len, indexes.values())) * frame_count > 4096
                    or any(any(set(frame.get(identity, {})) != expected for identity, expected in indexes.items())
                           for frame in samples)):
                raise ValueError("The runner did not return a complete JAR instance trace.")
        if driver_trace:
            driven = {identity: {index for index, terminal in enumerate(item["terminals"]) if terminal["direction"] != "input"}
                      for identity, item in instances.items()}
            if any(any(set(frame.get(identity, {})) != expected for identity, expected in driven.items()) for frame in drivers):
                raise ValueError("The runner did not return a complete JAR driver trace.")
    except (ValueError, KeyError) as error:
        raise JobFailure("invalid-jar-result", "The isolated runner returned an incomplete or incompatible reply.") from error
    result = {"libraryId": complete[1], "circuit": complete[2], "ports": list(ports.values()), "frames": frames}
    if instance_trace:
        result["instanceTrace"] = {
            "instances": list(instances.values()),
            "frames": [{identity: [frame[identity][index] for index in range(len(item["terminals"]))]
                        for identity, item in instances.items()} for frame in samples],
        }
    if driver_trace:
        result["instanceTrace"]["drives"] = [
            {identity: [frame.get(identity, {}).get(index) for index in range(len(item["terminals"]))]
             for identity, item in instances.items()} for frame in drivers
        ]
    return result


class JarLiveStream:
    """One ordered stream to one approved, supervised JVM."""

    def __init__(self, context, class_name, max_frames, instance_trace, driver_trace=False):
        self.context = context
        self.class_name = class_name
        self.max_frames = max_frames
        self.instance_trace = instance_trace
        self.driver_trace = driver_trace
        self.lock = threading.Lock()
        self.ready = None
        self.ports = {}
        self.receipts = {}
        self.sent = -1
        self.completed = -1
        self.last_revision = -1
        self.pending = None
        self.finishing = False
        self.records = []
        self.offset = 0
        self.partial = b""
        self.final_result = None

    def accept(self, request):
        if not isinstance(request, dict):
            raise JobFailure("invalid-live-input", "Provide a live input object.")
        sequence = request.get("sequence")
        if type(sequence) is not int or not 0 <= sequence <= self.max_frames:
            raise JobFailure("invalid-live-input", "The live sequence is outside the approved limit.")
        finish = set(request) == {"sequence", "finish"} and request.get("finish") is True
        if finish:
            revision, data = None, b"!finish\n"
        else:
            revision = request.get("revision")
            if (set(request) != {"sequence", "revision", "inputs"} or type(revision) is not int
                    or not 0 <= revision <= 2**53 - 1 or sequence == self.max_frames):
                raise JobFailure("invalid-live-input", "Provide a sequence, revision and pin values.")
            data = _frames([request.get("inputs")]).encode()
        with self.lock:
            self.context.check_cancelled()
            if self.ready is None:
                raise JobFailure("live-input-not-ready", "The JAR worker is still opening the circuit.")
            if sequence in self.receipts:
                if self.receipts[sequence] != (revision, data):
                    raise JobFailure("live-input-conflict", "The sequence was already accepted with different input.")
                return {"sequence": sequence, "accepted": True}
            if self.finishing or sequence != self.completed + 1 or self.pending is not None or self.sent > self.completed:
                raise JobFailure("live-input-order", "Wait for the previous sequence before sending the next input.")
            if finish:
                if self.completed < 0:
                    raise JobFailure("invalid-live-input", "Run at least one frame before finishing.")
                self.finishing = True
            else:
                widths = {name: port["width"] for name, port in self.ports.items() if port["direction"] == "input"}
                inputs = request["inputs"]
                if (inputs.keys() != widths.keys() or any(len(bits) != widths[name] for name, bits in inputs.items())
                        or revision <= self.last_revision):
                    raise JobFailure("invalid-live-input", "Use every named input pin, its width and an increasing revision.")
                self.last_revision = revision
            self.receipts[sequence] = revision, data
            self.pending = sequence, data
            return {"sequence": sequence, "accepted": True}

    def pump(self, stdin, stdout_path):
        with stdout_path.open("rb") as stdout:
            stdout.seek(self.offset)
            chunk = stdout.read()
            self.offset += len(chunk)
        lines = (self.partial + chunk).split(b"\n")
        self.partial = lines.pop()
        with self.lock:
            for raw in lines:
                try:
                    self._record(raw.decode("utf-8", errors="replace").rstrip("\r"))
                except (ValueError, KeyError) as error:
                    raise JobFailure("invalid-jar-result", "The isolated live runner returned an invalid receipt.") from error
            if stdin is None or self.pending is None:
                return
            sequence, data = self.pending
            try:
                count = os.write(stdin.fileno(), data)
            except BlockingIOError:
                return
            except BrokenPipeError as error:
                raise JobFailure("invalid-jar-result", "The isolated live worker closed its input.") from error
            self.sent = sequence
            self.pending = (sequence, data[count:]) if count < len(data) else None

    def _record(self, line):
        if not line.startswith("LOGISIM_JAR_V1\t"):
            return
        _, kind, *items = line.split("\t")
        values = [base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode() for value in items]
        if kind == "error" and len(values) == 1:
            raise JobFailure("incompatible-jar", values[0][:500])
        if kind == "driver-error" and len(values) == 1:
            raise JobFailure("unsupported-jar-driver-trace", values[0][:500])
        if kind == "ready":
            if self.ready is not None or len(values) != 3 or values[0] != self.class_name or not all(values):
                raise ValueError("Unexpected ready receipt.")
            self.ready = values
            self.context.publish_live({"ports": list(self.ports.values())})
            return
        if kind == "frame-complete":
            if self.ready is None or len(values) != 1 or self.pending is not None:
                raise ValueError("Unexpected frame receipt.")
            index = int(values[0])
            if index != self.completed + 1 or index != self.sent:
                raise ValueError("Frame receipt is out of order.")
            complete = "\t".join(["LOGISIM_JAR_V1", "complete", *(_encoded(value) for value in (*self.ready, str(index + 1)))])
            result = _records("\n".join([*self.records, complete]), self.class_name, index + 1,
                              self.instance_trace, self.driver_trace)
            revision, _ = self.receipts[index]
            self.completed = index
            live = {"ports": result["ports"], "sequence": index, "revision": revision, "outputs": result["frames"][-1]}
            if self.instance_trace:
                trace = result["instanceTrace"]
                live["instanceTrace"] = {"instances": trace["instances"], "frames": [trace["frames"][-1]]}
                if self.driver_trace:
                    live["instanceTrace"]["drives"] = [trace["drives"][-1]]
            self.context.publish_live(live)
            return
        if kind == "complete":
            if self.ready is None or self.final_result is not None or not self.finishing or self.sent != self.completed + 1:
                raise ValueError("Unexpected completion receipt.")
            self.final_result = _records("\n".join([*self.records, line]), self.class_name, self.completed + 1,
                                         self.instance_trace, self.driver_trace)
            return
        if kind == "port" and len(values) == 3 and self.ready is None:
            name, direction, width = values
            if not name or name in self.ports or direction not in {"input", "output"} or not 1 <= int(width) <= 64:
                raise ValueError("Invalid live pin.")
            self.ports[name] = {"name": name, "direction": direction, "width": int(width)}
        elif self.ready is None or self.final_result is not None or kind not in {"instance", "terminal", "terminal-value", "driver-value", "output"}:
            raise ValueError("Unexpected live runner record.")
        self.records.append(line)


def _run_circuit(context, parameters, live):
    frame_option = "maxFrames" if live else "frames"
    if (set(parameters) - {"libraryClass", "circuit", frame_option, "instanceTrace", "driverTrace"}
            or set(context.input_files) != {"project.circ", "library.jar"}):
        raise JobFailure("invalid-parameters", f"Provide project.circ, library.jar, libraryClass and {frame_option}.")
    class_name = parameters.get("libraryClass")
    circuit = parameters.get("circuit", "")
    instance_trace = parameters.get("instanceTrace", False)
    driver_trace = parameters.get("driverTrace", False)
    if (not isinstance(class_name, str) or len(class_name) > 256
            or not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", class_name, re.ASCII)
            or not isinstance(circuit, str) or len(circuit) > 128 or "\0" in circuit
            or not isinstance(instance_trace, bool) or not isinstance(driver_trace, bool)
            or (driver_trace and not instance_trace)):
        raise JobFailure("invalid-parameters", "Use a valid library class, circuit name and trace flags.")
    project = context.input_path("project.circ")
    library = context.input_path("library.jar")
    descriptor = _descriptor(project.read_bytes(), class_name)
    if live:
        max_frames = parameters.get("maxFrames")
        if type(max_frames) is not int or not 1 <= max_frames <= 256:
            raise JobFailure("invalid-parameters", "Approve between 1 and 256 live input frames.")
        stream = JarLiveStream(context, class_name, max_frames, instance_trace, driver_trace)
        context.bind_live_input(stream.accept)
        frame_argument = "-"
    else:
        frame_text = _frames(parameters.get("frames"))
        max_frames = len(parameters["frames"])
        frame_argument = str(_stage(context, "frames.tsv", text=frame_text))
    context.check_cancelled()
    runtime = _stage(context, "evolution.jar", source=_load_runtime())
    source = _stage(context, "JarCircuitRunner.java", source=Path(__file__).with_name("JarCircuitRunner.java"))
    java = Path(context.tool("jar-java"))
    command = [str(java), "-Djava.awt.headless=true", "-XX:ActiveProcessorCount=2", "-Xmx256m",
               "--class-path", str(runtime), str(source), str(project), str(library), class_name, descriptor,
               circuit, frame_argument, "true" if instance_trace else "false", str(max_frames),
               "true" if driver_trace else "false"]
    if live:
        context.run(command, java_home=java.parent.parent, pump=stream.pump)
        reply = stream.final_result
        if reply is None:
            raise JobFailure("invalid-jar-result", "The isolated live worker exited without completing.")
    else:
        result = context.run(command, timeout_seconds=35, java_home=java.parent.parent)
        reply = _records(result.stdout, class_name, max_frames, instance_trace, driver_trace)
    return {
        **reply,
        "libraryClass": class_name,
        "librarySha256": hashlib.sha256(library.read_bytes()).hexdigest(),
        "project": context.artifact(project),
    }


def run_circuit(context, parameters):
    return _run_circuit(context, parameters, live=False)


def run_live(context, parameters):
    return _run_circuit(context, parameters, live=True)


def register(registry):
    registry.register(Operation("jar-run", run_circuit, ("jar-java",),
                                "Run a circuit with an approved Evolution JAR library.",
                                requires_confirmation=True, isolated=True))
    registry.register(Operation("jar-live", run_live, ("jar-java",),
                                "Apply ordered live inputs to an approved Evolution JAR circuit.",
                                requires_confirmation=True, isolated=True, accepts_input=True))
