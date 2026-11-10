#!/usr/bin/env python3
"""Parity matrix for Logisim Mobile against Logisim Evolution d58a61e0.

The matrix tracks how closely Mobile matches the pinned Evolution revision and where gaps remain.

Row statuses
============

Each row represents an Evolution entry or a product feature.

- ``igual`` means Matches Evolution. A comparison found no difference, or a native scenario
  reproduced the Evolution behavior.
- ``diferenca`` means Explained difference. A comparison found a documented and accepted difference.
- ``divergente`` means Unexplained difference. A comparison found a defect or a difference awaiting
  a decision.
- ``nao_comparado`` means Not compared. Mobile implements the feature and a comparison is pending.
- ``ausente`` means Missing. Mobile has no implementation for the feature.
- ``fora_de_escopo`` means Out of scope. The maintainer excludes these rows from the denominator.
  Examples include Quartus, Vivado, and ISE toolchains, physical boards and devices, VoiceOver on
  a device, Windows, and CI.

A compared row can also display Needs recheck. A file belonging to the component changed in a
commit after the Mobile revision recorded in the evidence. The matrix retains the evidence and
status and flags the row for another run.

Rules
-----

1. Libraries use the 205 Evolution entries frozen in ``parity/evolution-d58a61e0.json``.
   The registry exported from Mobile (``parity/registry/mobile.json``) maps libraries and file IDs
   to Mobile component kinds. An entry without a kind is ``ausente``. Base tools are declared in
   ``rows`` because they are outside the component registry. Each oracle TSV row has an ID in the
   form ``<lowercase-kind>[__attribute_value...]`` and a result for each Mobile mode.

   For each kind:
   - Discard rows marked ``excluded``. These cover invalid fixtures or Evolution failures unrelated
     to the component. If Evolution intentionally does not simulate the component, such as Reptar
     Local Bus or a VHDL entity without QuestaSim, the comparison can document the difference as
     ``accepted``.
   - With no remaining rows, use ``nao_comparado``.
   - If a row differs from ``SAME`` and has no ``accepted`` difference, use ``divergente``. An
     ``open`` difference has a known cause and still awaits acceptance.
   - If any row has an ``accepted`` difference, use ``diferenca``. Otherwise, use ``igual``.

   An entry with multiple kinds, such as Pin with INPUT and OUTPUT, takes the worst status among
   its kinds. An entry without sweep rows can use other evidence declared in ``rows``.
   Watch the files that own the kind's definition (``metadataOwners``, excluding
   ``ComponentDefinition``). The corpus and CI tests cover shared simulator, codec, and UI files,
   so changes to those files require no per-row recheck.
2. Product rows cover Evolution menus, preferences, project options, formats, CLI features, and
   integrations. Use ``extra_rows`` for behaviors without their own inventory entries. The key
   prefix selects an area using ``area_rules``. Without an entry in ``rows``, use ``ausente``.
   With ``mobile`` paths alone, use ``nao_comparado``. With ``evidence`` and ``result``, use
   ``igual``, ``diferenca``, or ``divergente`` and list the component's files in ``watch``.
3. Evidence records a run's kind (``oracle`` against Evolution or native ``scenario``), Mobile
   revision, command, and record file in the repository. Oracles also record the Evolution
   revision and, when available, the JAR SHA-256. Sweeps point to a ``receipt.json`` with these
   details per suite.
4. Flag the row for a recheck when ``git log <evidence-revision>..HEAD -- <watched-files>`` returns
   commits. The matrix shows the first commit that changed a watched file after the evidence
   revision. Later changes to the same file leave that first commit unchanged.
5. Verified coverage equals (matches + explained differences) / (total - out of scope).
   Implemented coverage counts all rows except Missing and Out of scope.

Files
-----

- ``parity/matrix.json`` is the hand-maintained declarative source for evidence, differences,
  scope decisions, and product rows.
- ``parity/evolution-d58a61e0.json`` is the read-only inventory for the pinned Evolution revision.
- ``parity/registry/mobile.json`` is the registry exported by ``scripts/export-registry.py``.
- ``docs/parity-evidence/temporal-kinds/receipt.json`` and the TSVs record the temporal sweep.
- ``docs/parity.md`` and ``docs/parity.json`` are generated outputs.

Usage
-----

    python3 scripts/parity_matrix.py            # Regenerate docs/parity.md and docs/parity.json
    python3 scripts/parity_matrix.py --check    # Fail if generated outputs are stale
    python3 scripts/parity_matrix.py reverify temporal-kinds --evolution-jar JAR [--suites default,...]
    python3 scripts/parity_matrix.py reverify blif-circuit --evolution-jar JAR

A receipt with a ``fixtures`` regular expression reruns the sweep suites only for matching
fixtures. The ``corpus`` and ``corpus-roundtrip`` suites run ``scripts/oracle/blif-circuit/run.sh``.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MATRIX = "parity/matrix.json"
REGISTRY = "parity/registry/mobile.json"
OUTPUT_MD = "docs/parity.md"
OUTPUT_JSON = "docs/parity.json"
ISSUES = "https://github.com/ThalesMMS/logisim-mobile-dev/issues/"

STATUSES = ["igual", "diferenca", "divergente", "nao_comparado", "ausente", "fora_de_escopo"]
LABELS = {"igual": "Matches Evolution", "diferenca": "Explained difference", "divergente": "Unexplained difference",
          "nao_comparado": "Not compared", "ausente": "Missing", "fora_de_escopo": "Out of scope"}
# For entries with multiple kinds, use the least favorable status.
SEVERITY = ["ausente", "divergente", "nao_comparado", "diferenca", "igual"]
RESULTS = {"igual", "diferenca", "divergente"}
TREATMENTS = {"excluded", "accepted", "open"}
REFERENCE_GROUPS = ["menus", "preferences", "project_options", "formats", "cli", "integrations"]


class MatrixError(ValueError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(root: Path, relative: str) -> dict:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def require_file(root: Path, relative: str, where: str) -> None:
    if not relative or relative.startswith(("/", "./", "../")) or not (root / relative).is_file():
        raise MatrixError(f"{where}: missing or non-canonical repository file {relative!r}")


class Git:
    """Answers which of a set of files changed after a revision, with caching."""

    def __init__(self, root: Path):
        self.root = root
        self.cache: dict[tuple, list[str]] = {}

    def run(self, *args: str) -> str:
        result = subprocess.run(["git", *args], cwd=self.root, text=True, capture_output=True)
        if result.returncode:
            raise MatrixError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout

    def first_change(self, revision: str, paths: list[str]) -> str | None:
        """Oldest commit after ``revision`` that touches ``paths``. It never changes once it exists, so
        the generated matrix only changes when a row first needs re-verification."""
        key = (revision, tuple(sorted(paths)))
        if key not in self.cache:
            self.run("cat-file", "-e", revision + "^{commit}")
            out = self.run("log", "--reverse", "--format=%h %s", f"{revision}..HEAD", "--", *sorted(paths)) if paths else ""
            lines = [line for line in out.splitlines() if line]
            self.cache[key] = lines[0] if lines else None
        return self.cache[key]


# --- Evidence ---------------------------------------------------------------------------------

def load_evidence(root: Path, matrix: dict) -> dict[str, dict]:
    evidence = {}
    for ident, record in matrix["evidence"].items():
        where = f"evidence {ident}"
        if record.get("kind") not in {"oracle", "scenario"}:
            raise MatrixError(f"{where}: kind must be oracle or scenario")
        if "receipt" in record:
            require_file(root, record["receipt"], where)
            receipt = load_json(root, record["receipt"])
            suites = []
            for suite in receipt["suites"]:
                require_file(root, suite["artifact"], where)
                if sha256(root / suite["artifact"]) != suite["artifact_sha256"]:
                    raise MatrixError(f"{where}: {suite['artifact']} does not match its receipt SHA-256")
                for field in ("mobile_revision", "command"):
                    if not suite.get(field):
                        raise MatrixError(f"{where}: suite {suite['id']} lacks {field}")
                suites.append(suite)
            record = {**record, "evolution": receipt["evolution"], "environment": receipt.get("environment"),
                      "suites": suites}
        else:
            for field in ("mobile_revision", "command", "record", "title"):
                if not record.get(field):
                    raise MatrixError(f"{where}: missing {field}")
            require_file(root, record["record"], where)
            if record["kind"] == "oracle" and not record.get("evolution_revision"):
                raise MatrixError(f"{where}: an oracle must record the Evolution revision")
        evidence[ident] = record
    return evidence


def sweep_rows(root: Path, evidence: dict[str, dict]) -> dict[str, list[dict]]:
    """Fixture rows of every sweep campaign, grouped by kind prefix."""
    by_kind: dict[str, list[dict]] = {}
    for ident, record in evidence.items():
        for suite in record.get("suites", []):
            for line in (root / suite["artifact"]).read_text(encoding="utf-8").splitlines():
                if not line.strip() or line.startswith("#"):
                    continue
                fixture, *results = line.split()
                if len(results) != len(suite["modes"]):
                    raise MatrixError(f"{suite['artifact']}: {fixture} has {len(results)} results for modes {suite['modes']}")
                by_kind.setdefault(fixture.split("__", 1)[0], []).append(
                    {"evidence": ident, "suite": suite["id"], "fixture": fixture, "results": results,
                     "mobile_revision": suite["mobile_revision"]})
    return by_kind


def difference_for(row: dict, differences: list[dict]) -> dict | None:
    for difference in differences:
        if difference["evidence"] != row["evidence"]:
            continue
        if difference.get("suites") and row["suite"] not in difference["suites"]:
            continue
        if re.fullmatch(difference["fixtures"], row["fixture"]) and set(row["results"]) <= set(difference["results"]):
            return difference
    return None


def judge_kind(rows: list[dict], differences: list[dict]) -> dict:
    compared, notes, open_ = [], {}, []
    for row in rows:
        if set(row["results"]) == {"SAME"}:
            compared.append(row)
            continue
        difference = difference_for(row, differences)
        if difference is None:
            compared.append(row)
            open_.append(row["fixture"])
            continue
        notes[difference["id"]] = difference
        if difference["treatment"] == "excluded":
            continue
        compared.append(row)
        if difference["treatment"] == "open":
            open_.append(row["fixture"])
    counts = {"rows": len(rows), "compared": len(compared),
              "same": sum(set(row["results"]) == {"SAME"} for row in rows)}
    if not compared:
        status = "nao_comparado"
    elif open_:
        status = "divergente"
    elif any(difference["treatment"] == "accepted" for difference in notes.values()):
        status = "diferenca"
    else:
        status = "igual"
    return {"status": status, "counts": counts, "differences": sorted(notes), "unexplained": sorted(open_)[:10],
            "revisions": sorted({row["mobile_revision"] for row in compared})}


# --- Rows -------------------------------------------------------------------------------------

def out_of_scope_reason(matrix: dict, key: str) -> str | None:
    for decision in matrix["out_of_scope"]:
        if key in decision.get("keys", []) or any(key.startswith(p) for p in decision.get("prefixes", [])):
            return decision["reason"]
    return None


def area_of(matrix: dict, key: str) -> str:
    for prefix, area in matrix["area_rules"]:
        if key.startswith(prefix):
            return area
    raise MatrixError(f"No area rule for {key}")


def evidence_summary(evidence: dict, ident: str) -> dict:
    record = evidence[ident]
    summary = {"id": ident, "kind": record["kind"], "title": record.get("title", ident)}
    if "suites" in record:
        summary["receipt"] = record["receipt"]
    else:
        summary.update({field: record[field] for field in ("record", "platform", "mobile_revision",
                        "evolution_revision", "evolution_jar_sha256", "command") if record.get(field)})
    return summary


def apply_declared(root: Path, git: Git, evidence: dict, row: dict, spec: dict) -> None:
    """Applies a hand-declared row (presence, issues, evidence) on top of an absent default."""
    where = f"row {row['key']}"
    for path in spec.get("mobile", []) + spec.get("watch", []):
        require_file(root, path, where)
    row["mobile"] = spec.get("mobile", [])
    row["issues"] = sorted(set(row.get("issues", [])) | set(spec.get("issues", [])))
    if spec.get("note"):
        row["note"] = spec["note"]
    if row["mobile"] and row["status"] == "ausente":
        row["status"] = "nao_comparado"
    if not spec.get("evidence"):
        if spec.get("result"):
            raise MatrixError(f"{where}: result without evidence")
        return
    if spec.get("result") not in RESULTS:
        raise MatrixError(f"{where}: evidence requires result igual, diferenca or divergente")
    if not spec.get("watch"):
        raise MatrixError(f"{where}: evidence requires the component's own files in watch")
    if spec["result"] != "igual" and not spec.get("difference"):
        raise MatrixError(f"{where}: a {spec['result']} result must document the difference")
    if not row["mobile"] and not row.get("kinds"):
        raise MatrixError(f"{where}: evidence on a row without Mobile presence")
    for ident in spec["evidence"]:
        if ident not in evidence or "suites" in evidence[ident]:
            raise MatrixError(f"{where}: unknown evidence {ident!r}")
    row["status"] = spec["result"]
    row["evidence"] = [evidence_summary(evidence, ident) for ident in spec["evidence"]]
    if spec.get("difference"):
        row["difference"] = spec["difference"]
    row["watch"] = spec["watch"]
    stale = sorted(filter(None, (git.first_change(evidence[ident]["mobile_revision"], spec["watch"])
                                 for ident in spec["evidence"])))
    if stale:
        row["reverify"] = stale


def owner_files(root: Path, registry: dict) -> dict[str, list[str]]:
    """Maps each kind to the files that declare its definition owners (same rule as export-registry.py)."""
    paths = {}
    for source in registry["registrySources"]:
        text = (root / source["path"]).read_text(encoding="utf-8")
        for name in re.findall(r"\b(?:class|object|interface)\s+([A-Za-z_][A-Za-z_0-9]*)", text):
            paths.setdefault(name, source["path"])
        paths.setdefault(Path(source["path"]).stem + "Kt", source["path"])
    owners = {}
    for definition in registry["definitions"]:
        files = []
        for owner in definition["metadataOwners"]:
            name = owner.rsplit(".", 1)[-1]
            if name == "ComponentDefinition":
                continue
            if name not in paths:
                raise MatrixError(f"Registry owner {owner} has no source path in {REGISTRY}")
            files.append(paths[name])
        owners[definition["kind"]] = sorted(set(files))
    return owners


def library_rows(root: Path, git: Git, matrix: dict, reference: dict, registry: dict,
                 evidence: dict) -> list[dict]:
    by_entry: dict[tuple, list[str]] = {}
    for definition in registry["definitions"]:
        by_entry.setdefault((definition["library"].lstrip("#"), definition["fileName"]), []).append(definition["kind"])
    owners = owner_files(root, registry)
    sweeps = sweep_rows(root, evidence)
    swept_kinds = set()
    rows = []
    for entry in reference["libraries"]:
        key = entry["key"]
        kinds = sorted(by_entry.get((entry["library"], entry["component_id"]), []))
        row = {"key": key, "library": entry["library"], "name": entry["component_id"], "kinds": kinds,
               "status": "ausente", "issues": []}
        reason = out_of_scope_reason(matrix, key)
        spec = matrix["rows"].get(key)
        if reason:
            row.update(status="fora_de_escopo", note=reason)
        elif kinds:
            verdicts = {}
            for kind in kinds:
                swept = sweeps.get(kind.lower())
                if swept:
                    swept_kinds.add(kind.lower())
                    verdicts[kind] = judge_kind(swept, matrix["differences"])
            if verdicts and len(verdicts) == len(kinds):
                row["status"] = min((v["status"] for v in verdicts.values()), key=SEVERITY.index)
                row["sweep"] = verdicts
                watch = sorted({path for kind in kinds for path in owners[kind]})
                row["watch"] = watch
                revisions = sorted({rev for v in verdicts.values() for rev in v["revisions"]})
                stale = sorted(filter(None, {git.first_change(rev, watch) for rev in revisions}))
                if stale and row["status"] in RESULTS:
                    row["reverify"] = stale
                row["evidence"] = [evidence_summary(evidence, ident) for ident in
                                   sorted({r["evidence"] for kind in kinds for r in sweeps[kind.lower()]})]
            else:
                row["status"] = "nao_comparado"
                row["note"] = "The temporal sweep has no fixture for this entry, and there is no other recorded comparison."
                if verdicts:
                    row["sweep"] = verdicts
                    row["note"] = "The sweep covers only " + ", ".join(sorted(verdicts)) + "."
            if spec:
                if row["status"] != "nao_comparado" and spec.get("evidence"):
                    raise MatrixError(f"row {key}: declared evidence would override sweep results")
                if row["status"] == "nao_comparado":
                    if spec.get("evidence"):
                        row.pop("sweep", None)
                        row.pop("note", None)
                    apply_declared(root, git, evidence, row, spec)
                else:
                    row["issues"] = sorted(set(spec.get("issues", [])))
                    if spec.get("note"):
                        row["note"] = spec["note"]
        elif spec:
            apply_declared(root, git, evidence, row, spec)
        rows.append(row)
    unknown = set(sweeps) - swept_kinds
    if unknown:
        raise MatrixError("Sweep fixtures without a matching library entry: " + ", ".join(sorted(unknown)))
    return rows


def product_rows(root: Path, git: Git, matrix: dict, reference: dict, evidence: dict) -> list[dict]:
    rows = []
    entries = [(group, entry) for group in REFERENCE_GROUPS for entry in reference[group]]
    entries += [("extra", entry) for entry in matrix["extra_rows"]]
    for group, entry in entries:
        key = entry["key"]
        row = {"key": key, "group": group, "area": entry.get("area") or area_of(matrix, key),
               "name": entry.get("name") or key.split("/", 1)[1], "status": "ausente", "issues": []}
        if row["area"] not in {area["id"] for area in matrix["areas"]}:
            raise MatrixError(f"row {key}: unknown area {row['area']}")
        reason = out_of_scope_reason(matrix, key)
        if reason:
            row.update(status="fora_de_escopo", note=reason)
            row["issues"] = matrix["rows"].get(key, {}).get("issues", [])
        elif key in matrix["rows"]:
            apply_declared(root, git, evidence, row, matrix["rows"][key])
        rows.append(row)
    return rows


def totals(rows: list[dict]) -> dict:
    counts = {status: 0 for status in STATUSES}
    for row in rows:
        counts[row["status"]] += 1
    total = len(rows)
    scope = total - counts["fora_de_escopo"]
    proven = counts["igual"] + counts["diferenca"]
    return {"total": total, **counts, "reverificar": sum("reverify" in row for row in rows),
            "comparado_pct": round(100 * proven / scope, 1) if scope else 0.0,
            "existe_pct": round(100 * (scope - counts["ausente"]) / scope, 1) if scope else 0.0}


def build(root: Path = ROOT) -> dict:
    matrix = load_json(root, MATRIX)
    reference = load_json(root, matrix["reference"])
    registry = load_json(root, REGISTRY)
    known = {e["key"] for e in reference["libraries"]} | {e["key"] for g in REFERENCE_GROUPS for e in reference[g]}
    extra_keys = [entry["key"] for entry in matrix["extra_rows"]]
    if len(set(extra_keys)) != len(extra_keys) or known & set(extra_keys):
        raise MatrixError("extra_rows keys must be unique and not repeat reference keys")
    unknown = set(matrix["rows"]) - known - set(extra_keys)
    if unknown:
        raise MatrixError("rows for unknown keys: " + ", ".join(sorted(unknown)))
    for decision in matrix["out_of_scope"]:
        missing = set(decision.get("keys", [])) - known - set(extra_keys)
        if missing:
            raise MatrixError("out_of_scope for unknown keys: " + ", ".join(sorted(missing)))
    for difference in matrix["differences"]:
        if difference["treatment"] not in TREATMENTS or difference["evidence"] not in matrix["evidence"]:
            raise MatrixError(f"difference {difference['id']}: bad treatment or evidence")
    git = Git(root)
    evidence = load_evidence(root, matrix)
    libraries = library_rows(root, git, matrix, reference, registry, evidence)
    product = product_rows(root, git, matrix, reference, evidence)
    library_names = list(dict.fromkeys(row["library"] for row in libraries))
    return {
        "schema_version": 1,
        "generator": "scripts/parity_matrix.py",
        "reference": {"product": reference["product"], "revision": reference["revision"],
                      "inventory": matrix["reference"]},
        "statuses": LABELS,
        "summary": {"overall": totals(libraries + product), "libraries": totals(libraries),
                    "product": totals(product),
                    "by_library": {name: totals([r for r in libraries if r["library"] == name]) for name in library_names},
                    "by_area": {area["id"]: totals([r for r in product if r["area"] == area["id"]])
                                for area in matrix["areas"]}},
        "areas": matrix["areas"],
        "evidence": {ident: ({k: v for k, v in record.items() if k != "suites"} |
                             ({"suites": [{k: s[k] for k in ("id", "artifact", "modes", "command", "mobile_revision")}
                                          for s in record["suites"]]} if "suites" in record else {}))
                     for ident, record in evidence.items()},
        "differences": matrix["differences"],
        "libraries": libraries,
        "product": product,
    }


# --- Markdown ---------------------------------------------------------------------------------

def pct(summary: dict) -> str:
    return f"{summary['comparado_pct']:.1f}%"


def issue_links(row: dict) -> str:
    return ", ".join(f"[#{n}]({ISSUES}{n})" for n in row.get("issues", []))


def link(path: str) -> str:
    return f"[{Path(path).name}](../{path})"


def status_cell(row: dict) -> str:
    text = LABELS[row["status"]]
    return text + (" ⚠ Needs recheck" if "reverify" in row else "")


def detail(row: dict) -> str:
    parts = []
    if row.get("sweep"):
        same = sum(v["counts"]["same"] for v in row["sweep"].values())
        total = sum(v["counts"]["rows"] for v in row["sweep"].values())
        parts.append(f"{same}/{total} fixtures `SAME`")
        diffs = sorted({d for v in row["sweep"].values() for d in v["differences"]})
        if diffs:
            parts.append("Recorded differences include " + ", ".join(f"`{d}`" for d in diffs))
        unexplained = sorted({f for v in row["sweep"].values() for f in v["unexplained"]})
        if unexplained:
            parts.append("Fixtures with unexplained differences include " + ", ".join(f"`{f}`" for f in unexplained[:3]))
    for record in row.get("evidence", []):
        if "receipt" not in record:
            parts.append(link(record["record"]))
    if row.get("difference"):
        parts.append(row["difference"])
    if row.get("note"):
        parts.append(row["note"])
    if row.get("mobile") and row["status"] == "nao_comparado":
        parts.append(", ".join(link(p) for p in row["mobile"][:3]))
    if row.get("reverify"):
        parts.append("First changes since the recorded run include " + ", ".join(f"`{c}`" for c in row["reverify"]))
    return "; ".join(parts).replace("|", "\\|")


def summary_table(title: str, groups: dict[str, dict], labels: dict[str, str]) -> list[str]:
    head = [f"| {title} | Total | " + " | ".join(LABELS[s] for s in STATUSES) + " | Needs recheck | Verified |",
            "| --- | ---: | " + " | ".join("---:" for _ in STATUSES) + " | ---: | ---: |"]
    for key, summary in groups.items():
        name = labels.get(key, key)
        if key == "__total__":
            name = "**Total**"
        head.append(f"| {name} | {summary['total']} | " + " | ".join(str(summary[s]) for s in STATUSES)
                    + f" | {summary['reverificar']} | {pct(summary)} |")
    return head


def markdown(data: dict) -> str:
    summary = data["summary"]
    overall = summary["overall"]
    ref = data["reference"]
    lines = [
        "# Logisim Evolution parity matrix", "",
        "Generated from `parity/matrix.json` by `scripts/parity_matrix.py`. Edit the source and regenerate this file.", "",
        f"The reference is **{ref['product']}** `{ref['revision']}`. [Pinned inventory](../{ref['inventory']})", "",
        f"**{pct(overall)} verified** ({overall['igual']} matches and {overall['diferenca']} explained differences "
        f"across {overall['total'] - overall['fora_de_escopo']} in-scope rows). "
        f"Mobile includes {overall['existe_pct']:.1f}% of in-scope entries. Counts are {overall['ausente']} missing, "
        f"{overall['nao_comparado']} not compared, and {overall['divergente']} with unexplained differences. "
        f"Compared rows that need rechecking total {overall['reverificar']}.", "",
        "## How to read the matrix", "",
        "Each row covers an entry from the pinned Evolution revision, such as a component, menu item, preference, "
        "option, format, CLI flag, or integration. Status comes from recorded runs.", "",
        "- **Matches Evolution** means a comparison against Evolution (an oracle run) or a native scenario found no difference.",
        "- **Explained difference** means a comparison found a documented and accepted difference.",
        "- **Unexplained difference** means a comparison found a difference without an accepted explanation.",
        "- **Not compared** means the feature exists in Mobile and has no recorded comparison.",
        "- **Missing** means the feature is absent from Mobile.",
        "- **Out of scope** means the maintainer excluded the item from the percentage.",
        "- **⚠ Needs recheck** means a file belonging to the component changed after the recorded Mobile revision. "
        "The verified status carries over until the next run.", "",
        "Verified percentage = (matches + explained differences) / (total − out of scope). "
        "Related issues record the implementation history and may already be closed. Unverified rows still need evidence. "
        "The docstring in `scripts/parity_matrix.py` gives the full rules. "
        "See [parity/README.md](../parity/README.md) for how to record evidence and recheck rows.", "",
        "## Summary", "",
        *summary_table("Group", {"libraries": summary["libraries"], "product": summary["product"],
                                 "__total__": overall},
                       {"libraries": "Component libraries", "product": "Product areas"}), "",
        "### Libraries", "",
        *summary_table("Library", summary["by_library"], {}), "",
        "### Product areas", "",
        *summary_table("Area", {k: v for k, v in summary["by_area"].items() if v["total"]},
                       {area["id"]: area["title"] for area in data["areas"]}), "",
        "## Evidence", "",
        "| Evidence | Type | Mobile revision | Evolution | Command | Record |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for ident, record in data["evidence"].items():
        if "suites" in record:
            for suite in record["suites"]:
                evo = record["evolution"]
                lines.append(f"| {ident}/{suite['id']} | {record['kind']} | `{suite['mobile_revision'][:10]}` | "
                             f"`{evo['revision'][:10]}`, JAR `{evo.get('jar_sha256', '')[:12]}` | "
                             f"`{suite['command'].replace('|', '/')}` | {link(suite['artifact'])} |")
        else:
            evo = record.get("evolution_revision", "")
            lines.append(f"| {ident} | {record['kind']}{' ' + record['platform'] if record.get('platform') else ''} | "
                         f"`{record['mobile_revision'][:10]}` | {'`' + evo[:10] + '`' if evo else '—'} | "
                         f"`{record['command'].replace('|', '/')}` | {link(record['record'])} |")
    lines += ["", "### Differences recorded in sweeps", "",
              "`excluded` means the fixture is invalid or Evolution fails, so it is omitted from comparisons. "
              "`accepted` marks an accepted difference. `open` marks a known cause awaiting acceptance.", "",
              "| ID | Treatment | Fixtures | Reason |", "| --- | --- | --- | --- |"]
    for difference in data["differences"]:
        lines.append(f"| `{difference['id']}` | {difference['treatment']} | `{difference['fixtures'].replace('|', '/')}` | "
                     f"{difference['reason']} |")
    lines += ["", "## Component libraries", ""]
    for library in summary["by_library"]:
        lines += [f"### {library}", "", "| Entry | Mobile kinds | Status | Evidence and notes | Related issues |",
                  "| --- | --- | --- | --- | --- |"]
        for row in (r for r in data["libraries"] if r["library"] == library):
            kinds = ", ".join(f"`{k}`" for k in row["kinds"]) or "—"
            lines.append(f"| {row['name']} | {kinds} | {status_cell(row)} | {detail(row)} | {issue_links(row)} |")
        lines.append("")
    lines += ["## Product areas", ""]
    for area in data["areas"]:
        rows = [r for r in data["product"] if r["area"] == area["id"]]
        if not rows:
            continue
        lines += [f"### {area['title']}", "", "| Item | Status | Evidence and notes | Related issues |", "| --- | --- | --- | --- |"]
        for row in sorted(rows, key=lambda r: (SEVERITY.index(r["status"]) if r["status"] in SEVERITY else 9, r["key"])):
            lines.append(f"| `{row['key']}` {row['name'] if row['name'] != row['key'].split('/', 1)[-1] else ''} | "
                         f"{status_cell(row)} | {detail(row)} | {issue_links(row)} |")
        lines.append("")
    return "\n".join(line.rstrip() for line in lines).rstrip() + "\n"


def render(data: dict) -> tuple[str, str]:
    return markdown(data), json.dumps(data, ensure_ascii=False, indent=1) + "\n"


# --- Re-verification --------------------------------------------------------------------------

SWEEP_SUITES = {
    "default": [],
    "variants": ["--variants"],
    "negative": ["--variants", "--negative"],
    "pairs": ["--pairs", "6"],
}
# Corpus suites: artifact written by scripts/oracle/blif-circuit/run.sh.
CORPUS_SUITES = {"corpus": "summary.tsv", "corpus-roundtrip": "roundtrip.tsv"}


def reverify_temporal(root: Path, receipt_path: str, evolution_jar: Path, core_jar: Path, suites: list[str],
                      work: Path) -> dict:
    """Re-runs the temporal sweep suites and rewrites their artifacts and receipt entries."""
    dirty = subprocess.run(["git", "status", "--porcelain", "--", "core"], cwd=root, text=True,
                           capture_output=True, check=True).stdout.strip()
    if dirty:
        raise MatrixError("core/ has uncommitted changes; commit them so the receipt names the revision that ran")
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True,
                              check=True).stdout.strip()
    receipt = load_json(root, receipt_path)
    if sha256(evolution_jar) != receipt["evolution"]["jar_sha256"]:
        raise MatrixError("Evolution JAR differs from the receipt's pinned JAR; update the receipt deliberately first")
    by_id = {suite["id"]: suite for suite in receipt["suites"]}
    missing = set(suites) - set(by_id)
    if missing:
        raise MatrixError("The receipt has no suite " + ", ".join(sorted(missing)))
    run = root / "scripts/oracle/temporal-kinds/run.sh"
    today = datetime.date.today().isoformat()
    environment = dict(os.environ, FIXTURES=receipt["fixtures"]) if receipt.get("fixtures") else None
    only = f" FIXTURES='{receipt['fixtures']}'" if receipt.get("fixtures") else ""
    reverify = f"python3 scripts/parity_matrix.py reverify {receipt_path.split('/')[-2]}"
    if any(ident in CORPUS_SUITES for ident in suites):
        corpus = work / "corpus"
        subprocess.run([str(root / "scripts/oracle/blif-circuit/run.sh"), str(evolution_jar), str(corpus), str(core_jar)],
                       cwd=root, check=True, timeout=3600)
    for ident in suites:
        if ident in CORPUS_SUITES:
            artifact = root / by_id[ident]["artifact"]
            shutil.copyfile(work / "corpus" / CORPUS_SUITES[ident], artifact)
            by_id[ident].update(artifact_sha256=sha256(artifact), mobile_revision=revision,
                                core_jar_sha256=sha256(core_jar), recorded=today,
                                command=f"scripts/oracle/blif-circuit/run.sh EVOLUTION_JAR WORK/corpus CORE_JAR "
                                        f"({reverify} --suites {ident})")
            continue
        if ident == "roundtrip":
            continue
        output = work / ident
        shutil.rmtree(output, ignore_errors=True)
        subprocess.run([str(run), *SWEEP_SUITES[ident], str(evolution_jar), str(output), str(core_jar)],
                       cwd=root, check=True, timeout=7200, env=environment)
        artifact = root / by_id[ident]["artifact"]
        shutil.copyfile(output / "summary.tsv", artifact)
        if ident == "variants":
            shutil.copyfile(output / "attribute-options.tsv", artifact.with_name("attribute-options.tsv"))
        by_id[ident].update(artifact_sha256=sha256(artifact), mobile_revision=revision,
                            core_jar_sha256=sha256(core_jar), recorded=today,
                            command=only.strip() + (" " if only else "") +
                            " ".join(["scripts/oracle/temporal-kinds/run.sh", *SWEEP_SUITES[ident],
                                      "EVOLUTION_JAR", f"WORK/{ident}", "CORE_JAR"])
                            + f" ({reverify} --suites {ident})")
    if "roundtrip" in suites:
        variants = work / "variants"
        if not (variants / "fixtures/index.txt").is_file():
            raise MatrixError("roundtrip needs the variants run in the same --work directory")
        roundtrip_lines = roundtrip(root, variants, evolution_jar, core_jar)
        artifact = root / by_id["roundtrip"]["artifact"]
        artifact.write_text("".join(f"{line}\n" for line in roundtrip_lines))
        by_id["roundtrip"].update(artifact_sha256=sha256(artifact), mobile_revision=revision,
                                  core_jar_sha256=sha256(core_jar), recorded=today,
                                  command="ResaveDesktop WORK/variants/fixtures, then TemporalCorpusCli settle on each "
                                          "<id>.desktop.circ against the Evolution trace for the original "
                                          f"({reverify} --suites roundtrip)")
    (root / receipt_path).write_text(json.dumps(receipt, ensure_ascii=False, indent=1) + "\n")
    return receipt


def roundtrip(root: Path, variants: Path, evolution_jar: Path, core_jar: Path) -> list[str]:
    classes = variants / "classes"
    subprocess.run(["javac", "-cp", str(evolution_jar), "-d", str(classes),
                    str(root / "scripts/oracle/temporal-kinds/ResaveDesktop.java")], check=True)
    fixtures = variants / "fixtures"
    subprocess.run(["java", "-Djava.awt.headless=true", "-cp", f"{classes}:{evolution_jar}", "ResaveDesktop",
                    str(fixtures)], check=True, timeout=3600, capture_output=True)
    stdlib = kotlin_stdlib(root)
    lines = []
    for ident in (fixtures / "index.txt").read_text().split():
        resaved = fixtures / f"{ident}.desktop.circ"
        if ident.startswith("telnet") or not resaved.is_file():
            continue
        result = subprocess.run(["java", "-cp", f"{core_jar}:{stdlib}", "dev.logisim.mobile.core.TemporalCorpusCli",
                                 str(resaved), str(fixtures / f"{ident}.ops.tsv")], capture_output=True, text=True,
                                timeout=300)
        desktop = (variants / "desktop" / f"{ident}.tsv").read_text()
        lines.append(f"{ident} {'SAME' if result.returncode == 0 and result.stdout == desktop else 'DIFF'}")
    return sorted(lines)


def kotlin_stdlib(root: Path) -> Path:
    if os.environ.get("KOTLIN_STDLIB_JAR"):
        return Path(os.environ["KOTLIN_STDLIB_JAR"])
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (root / "build.gradle.kts").read_text())[1]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next(p for p in (cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar")
                if not p.name.endswith(("-sources.jar", "-javadoc.jar")))


# --- CLI --------------------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="fail if docs/parity.md or docs/parity.json is stale")
    sub = parser.add_subparsers(dest="command")
    again = sub.add_parser("reverify", help="re-run a sweep campaign and refresh its receipt")
    again.add_argument("evidence", choices=["temporal-kinds", "blif-circuit"])
    again.add_argument("--evolution-jar", type=Path, required=True)
    again.add_argument("--core-jar", type=Path, default=ROOT / "core/build/libs/core-jvm.jar")
    again.add_argument("--suites", help="comma-separated suites (default: every suite of the receipt)")
    again.add_argument("--work", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "reverify":
            matrix = load_json(ROOT, MATRIX)
            receipt_path = matrix["evidence"][args.evidence]["receipt"]
            suites = ([s for s in args.suites.split(",") if s] if args.suites else
                      [suite["id"] for suite in load_json(ROOT, receipt_path)["suites"]])
            unknown = set(suites) - set(SWEEP_SUITES) - {"roundtrip"} - set(CORPUS_SUITES)
            if unknown:
                raise MatrixError("Unknown suites: " + ", ".join(sorted(unknown)))
            if "roundtrip" in suites and "variants" not in suites and not args.work:
                raise MatrixError("roundtrip needs --suites including variants, or --work with a previous variants run")
            work = args.work or Path(tempfile.mkdtemp(prefix="parity-reverify-"))
            reverify_temporal(ROOT, receipt_path, args.evolution_jar.resolve(),
                              args.core_jar.resolve(), suites, work)
            print(f"Receipt updated; work files in {work}. Regenerate with python3 scripts/parity_matrix.py")
            return 0
        md, js = render(build(ROOT))
        if args.check:
            stale = [path for path, text in ((OUTPUT_MD, md), (OUTPUT_JSON, js))
                     if not (ROOT / path).is_file() or (ROOT / path).read_text(encoding="utf-8") != text]
            if stale:
                print("Stale: " + ", ".join(stale) + ". Run python3 scripts/parity_matrix.py", file=sys.stderr)
                return 1
            return 0
        (ROOT / OUTPUT_MD).write_text(md, encoding="utf-8")
        (ROOT / OUTPUT_JSON).write_text(js, encoding="utf-8")
        overall = build_summary_line(json.loads(js))
        print(overall)
        return 0
    except (MatrixError, KeyError, subprocess.CalledProcessError) as error:
        print(f"parity_matrix: {error}", file=sys.stderr)
        return 1


def build_summary_line(data: dict) -> str:
    overall = data["summary"]["overall"]
    statuses = " ".join(f"{LABELS[status]}={overall[status]}" for status in STATUSES)
    return f"{statuses} Needs recheck={overall['reverificar']} Verified={pct(overall)}"


if __name__ == "__main__":
    sys.exit(main())
