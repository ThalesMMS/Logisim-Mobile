#!/usr/bin/env python3
"""Summarize saved Android readiness probes without contacting a device."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import statistics


IO_KEYS = {"rchar", "wchar", "syscr", "syscw", "read_bytes", "write_bytes", "cancelled_write_bytes"}


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def proc_stat(line):
    """comm can contain spaces and parentheses; the last ')' ends field 2."""
    match = re.fullmatch(r"(\d+) \((.*)\) (.+)", line)
    if match is None:
        raise ValueError("Invalid /proc stat line")
    fields = match[3].split()
    if len(fields) < 22:
        raise ValueError("Truncated /proc stat line")
    return {"id": int(match[1]), "name": match[2], "state": fields[0],
            "utimeTicks": int(fields[11]), "stimeTicks": int(fields[12]),
            "startTicks": int(fields[19]), "threadCount": int(fields[17])}


def parse_probe(text):
    processes, formats = {}, set()
    current = None
    mode = None
    last_thread = None
    for line in text.splitlines():
        if line.startswith("PROCESS:"):
            current = int(line.split(":", 1)[1])
            processes[current] = {"threads": [], "io": {}, "status": {}}
            mode = "process-stat"
        elif line.startswith("THREAD:/proc/"):
            marker = re.fullmatch(r"THREAD:/proc/(\d+)/task/(\d+)", line)
            if marker is None or int(marker[1]) != current:
                raise ValueError("Thread marker does not match its process")
            mode, last_thread = "thread-stat", None
            formats.add("legacy-per-thread")
        elif line.startswith("THREADS:"):
            if int(line.split(":", 1)[1]) != current:
                raise ValueError("Thread vector does not match its process")
            mode, last_thread = "thread-vector", None
            formats.add("vector")
        elif re.match(r"\d+ \(", line):
            stat = proc_stat(line)
            if current is None:
                raise ValueError("Stat without process marker")
            if mode == "process-stat":
                if stat["id"] != current:
                    raise ValueError("Process stat does not match its marker")
                processes[current]["stat"] = stat
                mode = "process-fields"
            elif mode in {"thread-stat", "thread-vector"}:
                processes[current]["threads"].append(stat)
                last_thread = stat
                if mode == "thread-stat":
                    mode = "thread-wchan"
            else:
                raise ValueError("Unexpected stat line")
        elif mode == "thread-wchan" and line:
            last_thread["wchan"] = line
            mode = "after-thread"
        elif current is not None and mode == "process-fields":
            key, separator, value = line.partition(":")
            if separator and key in IO_KEYS:
                processes[current]["io"][key] = int(value.strip())
            elif separator and key in {"VmRSS", "VmHWM", "VmSwap"}:
                processes[current]["status"][key + "KiB"] = int(value.split()[0])
    incomplete = [pid for pid, process in processes.items() if "stat" not in process]
    if incomplete:
        raise ValueError(f"Missing process stat: {incomplete}")
    return processes, formats


def distribution(values):
    return {"minimum": min(values), "median": statistics.median(values), "maximum": max(values)} if values else None


def cpu_window(points, hz):
    first, last = points[0], points[-1]
    user = last["stat"]["utimeTicks"] - first["stat"]["utimeTicks"]
    system = last["stat"]["stimeTicks"] - first["stat"]["stimeTicks"]
    for a, b in zip(points, points[1:]):
        if any(b["stat"][key] < a["stat"][key] for key in ("utimeTicks", "stimeTicks")):
            raise ValueError("CPU counter decreased within one PID/TID and startTicks identity")
    low = max(0, last["beforeNs"] - first["afterNs"]) / 1e9
    high = (last["afterNs"] - first["beforeNs"]) / 1e9
    midpoint = ((last["beforeNs"] + last["afterNs"]) - (first["beforeNs"] + first["afterNs"])) / 2e9
    total = user + system
    return {"observations": len(points), "firstFile": first["file"], "lastFile": last["file"],
            "firstReadEnvelopeNs": [first["beforeNs"], first["afterNs"]],
            "lastReadEnvelopeNs": [last["beforeNs"], last["afterNs"]],
            "firstCounters": {k: first["stat"][k] for k in ("utimeTicks", "stimeTicks")},
            "lastCounters": {k: last["stat"][k] for k in ("utimeTicks", "stimeTicks")},
            "deltaTicks": {"user": user, "system": system, "total": total},
            "hz": hz, "cpuSeconds": total / hz,
            "wallSeconds": {"lowerBound": low, "midpointEstimate": midpoint, "upperBound": high},
            "quantizationAllowanceTicks": 2,
            "oneCorePercent": {"midpointEstimate": total / hz / midpoint * 100 if midpoint > 0 else None,
                "lowerBound": max(0, total - 2) / hz / high * 100 if high > 0 and len(points) > 1 else None,
                "upperBound": (total + 2) / hz / low * 100 if low > 0 and len(points) > 1 else None},
            "upperRateUnbounded": low == 0,
            "singleObservation": len(points) == 1}


def hash_list(path):
    return {line.split(None, 1)[1]: line.split(None, 1)[0]
            for line in path.read_text().splitlines() if line.strip()}


def summarize(root, pid, hz):
    root = root.resolve()
    receipt = json.loads((root / "passive/receipt.json").read_text())
    samples = json.loads((root / "passive/samples.json").read_text())
    events = json.loads((root / "events.json").read_text())
    groups, formats, durations, centers, empty = defaultdict(list), set(), [], [], []
    for sample in samples:
        relative = Path(sample["file"])
        if relative.name != sample["file"]:
            raise ValueError("Unsafe sample filename")
        before, after = sample["beforeMonotonicNs"], sample["afterMonotonicNs"]
        if after < before:
            raise ValueError("Probe end precedes its start")
        durations.append((after - before) / 1e9)
        centers.append((after + before) / 2e9)
        processes, found_formats = parse_probe((root / "passive" / relative).read_text())
        formats.update(found_formats)
        if not processes:
            empty.append(sample["file"])
        for process_pid, data in processes.items():
            groups[(process_pid, data["stat"]["startTicks"])].append(dict(
                data, file=sample["file"], beforeNs=before, afterNs=after))
    if not samples or not any(key[0] == pid for key in groups):
        raise ValueError("No samples for the requested PID")
    results = []
    for (process_pid, start), points in sorted(groups.items()):
        process = {"pid": process_pid, "startTicks": start, "selected": process_pid == pid,
                   "cpu": cpu_window(points, hz), "threads": []}
        first, last = points[0], points[-1]
        process["ioDelta"] = {k: last["io"][k] - first["io"][k] for k in sorted(first["io"].keys() & last["io"].keys())}
        rss = [point["status"]["VmRSSKiB"] for point in points if "VmRSSKiB" in point["status"]]
        process["rssKiB"] = dict(distribution(rss), first=rss[0], last=rss[-1]) if rss else None
        process["probeThreadCounts"] = [{"file": point["file"], "processStat": point["stat"]["threadCount"],
                                          "threadStatsRead": len(point["threads"])} for point in points]
        threads = defaultdict(list)
        for point in points:
            for thread in point["threads"]:
                threads[(thread["id"], thread["startTicks"])].append(dict(
                    stat=thread, file=point["file"], beforeNs=point["beforeNs"], afterNs=point["afterNs"]))
        for (tid, thread_start), observations in threads.items():
            process["threads"].append({"tid": tid, "startTicks": thread_start, "name": observations[-1]["stat"]["name"],
                "cpu": cpu_window(observations, hz),
                "stateCounts": dict(Counter(point["stat"]["state"] for point in observations)),
                "wchanCounts": dict(Counter(point["stat"]["wchan"] for point in observations if "wchan" in point["stat"]))})
        process["threads"].sort(key=lambda thread: thread["cpu"]["deltaTicks"]["total"], reverse=True)
        results.append(process)
    launch = next((e["monotonicNs"] for e in events if e["event"] == "launch-request"), None)
    returned = next((e["monotonicNs"] for e in events if e["event"] == "am-start-returned"), None)
    timeline = []
    for event in events:
        if event["event"] == "command":
            continue
        point = dict(event)
        point["sinceLaunchSeconds"] = (event["monotonicNs"] - launch) / 1e9 if launch is not None else None
        point["sinceAmStartReturnSeconds"] = (event["monotonicNs"] - returned) / 1e9 if returned is not None else None
        if "beforeMonotonicNs" in event:
            point["observationEnvelopeNs"] = [event["beforeMonotonicNs"], event["monotonicNs"]]
        timeline.append(point)
    metadata = {}
    for name in ("guest-hz.txt", "guest-vcpus.txt", "guest-load.txt", "host-uptime.txt", "host-sysctl.txt", "host-qemu-mapping.txt"):
        path = root / name
        if path.is_file():
            metadata[name] = {"text": path.read_text().split("\n\nSTDERR", 1)[0].strip(),
                              "fileModifiedUtc": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()}
    hz_evidence = int(metadata["guest-hz.txt"]["text"]) if "guest-hz.txt" in metadata else None
    if hz_evidence is not None and hz_evidence != hz:
        raise ValueError("--hz differs from saved guest-hz.txt")
    observer = root / "observer-source-at-capture.py"
    captured_observer = {"receiptSha256": receipt.get("observerSha256"), "rawThreadFormats": sorted(formats)}
    if observer.is_file():
        captured_observer.update(savedSourceSha256=sha(observer), sourceMatchesReceipt=sha(observer) == receipt.get("observerSha256"))
    apk_before, apk_after = receipt.get("apkBefore", {}).get("sha256"), receipt.get("apkAfter", {}).get("sha256")
    expected = receipt.get("arguments", {}).get("expected_apk_sha256")
    files = None
    if (root / "private-file-hashes-before.txt").is_file() and (root / "private-file-hashes-after.txt").is_file():
        a, b = hash_list(root / "private-file-hashes-before.txt"), hash_list(root / "private-file-hashes-after.txt")
        files = {"unchanged": sorted(k for k in a if a[k] == b.get(k)), "changed": sorted(k for k in a.keys() & b.keys() if a[k] != b[k]),
                 "added": sorted(b.keys() - a.keys()), "removed": sorted(a.keys() - b.keys())}
    span = (samples[-1]["afterMonotonicNs"] - samples[0]["beforeMonotonicNs"]) / 1e9
    limitations = ["One captured launch, not a baseline, energy measurement or physical-device budget.",
        "All stat, I/O and thread reads are non-atomic and can occur anywhere within each probe envelope.",
        "Midpoint rates are estimates; rate bounds include probe duration and a two-tick quantization allowance.",
        "Thread windows differ; their deltas must not be summed as an exact decomposition of process CPU.",
        "State and wchan are point observations at different instants, not CPU duty cycles or function attribution.",
        "Supplemental metadata file mtimes are not measurement timestamps; post-capture host data cannot replace capture host load.",
        "SIGQUIT, hierarchy polling, screenshots and near-continuous probes can perturb the observed launch."]
    if "legacy-per-thread" in formats:
        limitations.append("This capture used the older collector with per-thread cat calls; later vector-collector improvements were not measured here.")
    if hz_evidence is None:
        limitations.append("The selected HZ is operator supplied; no guest-hz.txt evidence is present.")
    return {"schemaVersion": 1, "status": "summarized", "input": str(root), "requestedPid": pid,
        "sourceRevision": receipt.get("sourceRevision"), "sourceAttestation": receipt.get("sourceAttestation"),
        "toolSha256": sha(__file__), "inputSha256": {path.relative_to(root).as_posix(): sha(path) for path in sorted(root.rglob("*")) if path.is_file()},
        "apk": {"beforeSha256": apk_before, "afterSha256": apk_after, "expectedSha256": expected,
                "verifiedBeforeAndAfter": bool(expected and apk_before == apk_after == expected)},
        "observer": captured_observer, "processes": results, "selectedIdentities": [dict(pid=k[0], startTicks=k[1]) for k in sorted(groups) if k[0] == pid],
        "probes": {"count": len(samples), "empty": empty, "errors": [s for s in samples if s.get("error")],
                   "durationSeconds": distribution(durations), "sumDurationSeconds": sum(durations), "spanSeconds": span,
                   "commandOccupancyFraction": sum(durations) / span if span > 0 else None,
                   "midpointSpacingSeconds": distribution([b - a for a, b in zip(centers, centers[1:])])},
        "timeline": timeline, "readiness": {"readyEventRecorded": any(e["event"] == "editor-ready" for e in events),
                   "timeoutEventRecorded": any(e["event"] == "editor-timeout" for e in events),
                   "freshLoadingElapsedSeconds": [e.get("elapsedSeconds") for e in events if e["event"] == "hierarchy" and e.get("loading")]},
        "hz": {"used": hz, "savedGuestValue": hz_evidence, "matchesSavedGuest": hz_evidence == hz if hz_evidence is not None else None},
        "guestVcpus": int(metadata["guest-vcpus.txt"]["text"]) if "guest-vcpus.txt" in metadata else None,
        "hostLoadAtCapture": receipt.get("hostLoad"), "supplementalMetadata": metadata,
        "privateFiles": files, "limitations": limitations}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--hz", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.pid <= 0 or args.hz <= 0:
        parser.error("--pid and --hz must be positive")
    output, root = args.output.resolve(), args.input.resolve()
    if output.is_relative_to(root) or output.exists():
        parser.error("Output must be a new path outside the raw input directory")
    try:
        summary = summarize(root, args.pid, args.hz)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"readiness summary: {error}\n")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
