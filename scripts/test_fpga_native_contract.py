#!/usr/bin/env python3
"""Validate FPGA fixture data only. This module never executes a native app or EDA tool."""

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures/user-scenarios"
CONTRACT = FIXTURES / "fpga-actions-v1.json"
SCENARIO_IDS = {
    "fpga-native-mapping-preflight", "fpga-native-real-synthesis",
    "fpga-native-real-bitstream", "fpga-native-invalid-package-pin-error",
    "fpga-native-cancel-real-job",
}


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_contract(contract, payloads):
    """Check literal fixture expectations without treating them as execution evidence."""
    _require(contract.get("schemaVersion") == 1, "Unsupported FPGA contract version")
    _require(contract.get("nativeExecution") == "NOT_RUN", "Fixture parsing cannot qualify native execution")
    _require(contract["fixture"] == "fixtures/user-scenarios/fpga-and.circ", "Unexpected circuit fixture")
    adapter = contract["platformAdapters"]["android"]["boardImport"]
    _require(adapter["transport"] == "native-open-document" and adapter["actionKey"] == "fpga_import_board_file" and
             adapter["replacesActions"] == ["pasteBoardXml", "readBoard"] and adapter["pasteXmlNativeExecution"] == "NOT_RUN",
             "Android board files must not imply native XML-paste qualification")
    _require(contract["boards"] == {
        "valid": "fixtures/user-scenarios/fpga-hx1k.xml",
        "invalidPackagePin": "fixtures/user-scenarios/fpga-hx1k-invalid-pin.xml",
    }, "Unexpected board fixtures")
    _require(set(payloads) == {"fpga-and.circ", "fpga-hx1k.xml", "fpga-hx1k-invalid-pin.xml"}, "Missing or extra fixture payload")
    _require(contract["fixtureSha256"] == {
        name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()
    }, "Fixture bytes do not match their hashes")

    project = ET.fromstring(payloads["fpga-and.circ"])
    circuits = project.findall("circuit")
    _require(project.find("main").get("name") == "main" and len(circuits) == 1, "Expected one main circuit")
    circuit = circuits[0]
    _require(circuit.attrib == {"name": "main", "id": "fpga-native-main"}, "Circuit identity changed")
    components = circuit.findall("comp")
    _require(len(components) == 4, "Expected four circuit components")
    observed = {}
    for component in components:
        identity = component.get("logisim-mobile-id")
        _require(identity not in observed, "Repeated component identity")
        attributes = component.findall("a")
        _require(len({value.get("name") for value in attributes}) == len(attributes), "Duplicate component attribute")
        values = {value.get("name"): value.get("val") for value in attributes}
        observed[identity] = (component.get("name"), component.get("loc"), values)
    _require(set(observed) == {"fpga-a", "fpga-b", "fpga-and", "fpga-y"}, "Component identities changed")
    for identity, location, kind, direction, label in (
        ("fpga-a", "(100,80)", "Pin", "input", "a"),
        ("fpga-b", "(100,120)", "Pin", "input", "b"),
        ("fpga-y", "(300,100)", "Pin", "output", "y"),
    ):
        name, actual_location, attributes = observed[identity]
        _require((name, actual_location, attributes["type"], attributes["label"], attributes["width"]) ==
                 (kind, location, direction, label, "1"), "A circuit pin changed")
        _require(attributes["facing"] == ("west" if direction == "output" else "east"), "Pin terminal direction changed")
    gate_name, gate_location, gate_attributes = observed["fpga-and"]
    _require((gate_name, gate_location, gate_attributes) == ("AND Gate", "(200,100)", {
        "facing": "east", "width": "1", "size": "50", "inputs": "2", "out": "01", "label": "AND",
    }), "The two-input AND gate changed")
    _require([(node.get("from"), node.get("to")) for node in circuit.findall("wire")] == [
        ("(100,80)", "(150,80)"), ("(100,120)", "(150,120)"), ("(200,100)", "(300,100)"),
    ], "Wire endpoints changed")
    document = contract["document"]
    _require((document["mainCircuit"], document["circuitId"], document["circuitCount"],
              document["componentCount"], document["wireCount"], document["componentIds"]) ==
             ("main", "fpga-native-main", 1, 4, 3, ["fpga-a", "fpga-b", "fpga-and", "fpga-y"]),
             "Document identity or counts changed")
    _require(document["wires"] == [
        [[100, 80], [150, 80]], [[100, 120], [150, 120]], [[200, 100], [300, 100]],
    ], "Contract wire endpoints changed")
    _require(contract["document"]["ports"] == [
        {"name": "p_a", "componentId": "fpga-a", "label": "a", "direction": "INPUT", "width": 1},
        {"name": "p_b", "componentId": "fpga-b", "label": "b", "direction": "INPUT", "width": 1},
        {"name": "p_y", "componentId": "fpga-y", "label": "y", "direction": "OUTPUT", "width": 1},
    ], "Expected HDL ports changed")
    _require(contract["document"]["truthTable"] == [
        {"a": 0, "b": 0, "y": 0}, {"a": 0, "b": 1, "y": 0},
        {"a": 1, "b": 0, "y": 0}, {"a": 1, "b": 1, "y": 1},
    ], "The literal AND truth table changed")

    valid = ET.fromstring(payloads["fpga-hx1k.xml"])
    invalid = ET.fromstring(payloads["fpga-hx1k-invalid-pin.xml"])
    _require(valid.find("BoardInformation/FPGAInformation").attrib == {
        "Vendor": "OPENFPGA", "Family": "ice40", "Part": "hx1k", "Package": "tq144", "Speedgrade": "1",
    }, "The selected FPGA device changed")
    _require(valid.find("BoardInformation/ClockInformation") is None, "This combinational fixture must not invent a clock")
    _require(valid.find("BoardInformation/UnusedPins").attrib == {"PullBehavior": "Float"}, "Unused physical pins must remain floating")
    _require([(node.tag, node.attrib) for node in valid.find("IOComponents")] == [
        ("Button", {"Label": "A", "InputPinSet": "21", "ActivityLevel": "Active high", "FPGAPinIOStandard": "LVCMOS33"}),
        ("Button", {"Label": "B", "InputPinSet": "22", "ActivityLevel": "Active high", "FPGAPinIOStandard": "LVCMOS33"}),
        ("LED", {"Label": "Y", "OutputPinSet": "23", "FPGAPinIOStandard": "LVCMOS33"}),
    ], "The board resources or physical pins changed")
    _require(invalid.find("IOComponents/LED").get("OutputPinSet") == "999", "The failure board must declare package pin999")
    invalid.find("IOComponents/LED").set("OutputPinSet", "23")
    _require(ET.tostring(valid) == ET.tostring(invalid), "The failure board must differ only at its declared output pin")
    _require(contract["mapping"] == {"p_a": "21", "p_b": "22", "p_y": "23"}, "Physical mapping changed")
    _require(contract["invalidPackagePinMapping"] == {"p_a": "21", "p_b": "22", "p_y": "999"}, "Failure mapping changed")

    companion = contract["companion"]
    _require(companion["operation"] == "fpga-synthesize" and companion["programmingAllowed"] is False and
             companion["programTargets"] == [], "The native fixture cannot authorize programming")
    _require(companion["parameters"] == {
        "flow": "ice40", "part": "hx1k", "package": "tq144", "top": "logisim_fpga_top", "language": "verilog",
        "sources": ["source-0000.v", "source-0001.v"],
    }, "The companion request parameters changed")
    safety = contract["dataSafety"]
    _require(safety["applicationPackage"] == "thalesmms.logisim.mobile.e2e" and
             safety["normalApplicationAccessAllowed"] is False and safety["originalFixtureImmutable"] is True,
             "The scenario must preserve isolated fixture data")
    scenarios = contract["scenarios"]
    _require(len(scenarios) == 5 and {case["id"] for case in scenarios} == SCENARIO_IDS, "Missing or duplicate native scenario")
    _require(all(case["nativeExecution"] == "NOT_RUN" for case in scenarios), "A source fixture cannot mark a native case as executed")
    cases = {case["id"]: case for case in scenarios}
    _require(cases["fpga-native-real-bitstream"]["required"] is False, "Success bitstream is a separate optional case")
    for case in scenarios:
        actions = [step["action"] for step in case["steps"]]
        _require(actions[0:2] == ["importFixture", "exportBaseline"] and actions[-1] == "assertDocumentUnchanged",
                 "Each native case needs independent document preservation checks")
        _require("pairRealCompanion" in actions and actions.index("pairRealCompanion") < actions.index("openFpgaTool"),
                 "Synthesis controls require a paired companion before opening FPGA")
        _require(not any("program" in action.lower() for action in actions), "No scenario may program hardware")
        if "expectedOutcome" in case:
            _require(case["expectedOutcome"]["programmed"] is False and case["expectedOutcome"]["hardwareVerified"] is False,
                     "Build results cannot imply hardware verification")
    bad_pin = cases["fpga-native-invalid-package-pin-error"]
    _require(bad_pin["board"] == "invalidPackagePin" and bad_pin["goal"] == "bitstream", "The invalid-package case must reach place and route")
    _require(next(step for step in bad_pin["steps"] if step["action"] == "assertToolError") == {
        "action": "assertToolError", "code": "fpga-stage-failed", "stage": "place-route", "exitCode": "nonzero",
        "stderrSubstring": "package does not have a pin named '999'",
    }, "The invalid-package case must require a real nextpnr diagnostic")
    cancel = cases["fpga-native-cancel-real-job"]
    _require(cancel["cancellationPolicy"] == {
        "maxSubmissions": 1, "completedBeforeCancellation": "FAIL_NOT_QUALIFIED", "requireSameJobId": True,
        "requireObservedNonterminalState": True, "allowedToolDelaySeconds": 0,
        "syntheticWaitOperationAllowed": False, "retryUntilCancelledAllowed": False,
    }, "The cancellation case cannot create an artificial cancellation opportunity")
    actions = [step["action"] for step in cancel["steps"]]
    _require(next(step for step in cancel["steps"] if step["action"] == "observeNonterminalJob") == {
        "action": "observeNonterminalJob", "allowedStates": ["queued", "running"],
    }, "Cancellation needs an actual queued or running job")
    _require(actions.index("observeNonterminalJob") < actions.index("cancelJobUsingUi") < actions.index("observeTerminalJob"),
             "Cancellation needs a nonterminal observation before the real UI action")
    _require(next(step for step in cancel["steps"] if step["action"] == "observeTerminalJob")["expectedState"] == "cancelled",
             "Only the same job's cancelled state qualifies cancellation")
    _require(contract["remainingIssue65Gates"]["issueMayClose"] is False and
             contract["remainingIssue65Gates"]["physicalBoardProgramming"] == "NOT_RUN",
             "Fixture checks cannot close issue65 or qualify physical hardware")


class FpgaNativeContractTests(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads(CONTRACT.read_text())
        self.payloads = {name: (FIXTURES / name).read_bytes() for name in self.contract["fixtureSha256"]}

    def validate(self, contract=None, payloads=None):
        validate_contract(self.contract if contract is None else contract, self.payloads if payloads is None else payloads)

    def test_checked_in_fixture_and_literal_expectations_agree(self):
        self.validate()

    def test_same_counts_with_a_wrong_wire_fail(self):
        payloads = dict(self.payloads)
        payloads["fpga-and.circ"] = payloads["fpga-and.circ"].replace(b'to="(150,80)"', b'to="(150,90)"')
        contract = deepcopy(self.contract)
        contract["fixtureSha256"]["fpga-and.circ"] = hashlib.sha256(payloads["fpga-and.circ"]).hexdigest()
        with self.assertRaisesRegex(ValueError, "Wire endpoints"):
            self.validate(contract, payloads)

    def test_same_counts_with_a_wrong_gate_fail(self):
        payloads = dict(self.payloads)
        payloads["fpga-and.circ"] = payloads["fpga-and.circ"].replace(b'name="AND Gate"', b'name="OR Gate"')
        contract = deepcopy(self.contract)
        contract["fixtureSha256"]["fpga-and.circ"] = hashlib.sha256(payloads["fpga-and.circ"]).hexdigest()
        with self.assertRaisesRegex(ValueError, "AND gate"):
            self.validate(contract, payloads)

    def test_wrong_literal_truth_table_is_rejected(self):
        changed = deepcopy(self.contract)
        changed["document"]["truthTable"][2]["y"] = 1
        with self.assertRaisesRegex(ValueError, "truth table"):
            self.validate(changed)

    def test_unprefixed_hdl_port_names_are_rejected(self):
        changed = deepcopy(self.contract)
        changed["document"]["ports"][0]["name"] = "a"
        with self.assertRaisesRegex(ValueError, "HDL ports"):
            self.validate(changed)

    def test_contract_only_wire_drift_is_rejected(self):
        changed = deepcopy(self.contract)
        changed["document"]["wires"][0][1][1] = 90
        with self.assertRaisesRegex(ValueError, "Contract wire endpoints"):
            self.validate(changed)

    def test_contract_only_document_identity_drift_is_rejected(self):
        changed = deepcopy(self.contract)
        changed["document"]["componentIds"][0] = "wrong"
        with self.assertRaisesRegex(ValueError, "Document identity"):
            self.validate(changed)

    def test_changed_board_bytes_do_not_reuse_the_declared_hash(self):
        payloads = dict(self.payloads)
        payloads["fpga-hx1k.xml"] += b"\n"
        with self.assertRaisesRegex(ValueError, "hashes"):
            self.validate(payloads=payloads)

    def test_failure_board_cannot_change_unrelated_resources(self):
        payloads = dict(self.payloads)
        payloads["fpga-hx1k-invalid-pin.xml"] = payloads["fpga-hx1k-invalid-pin.xml"].replace(b'InputPinSet="21"', b'InputPinSet="24"')
        changed = deepcopy(self.contract)
        changed["fixtureSha256"]["fpga-hx1k-invalid-pin.xml"] = hashlib.sha256(payloads["fpga-hx1k-invalid-pin.xml"]).hexdigest()
        with self.assertRaisesRegex(ValueError, "differ only"):
            self.validate(changed, payloads)

    def test_real_app_data_and_programming_cannot_be_enabled(self):
        for section, key in (("dataSafety", "normalApplicationAccessAllowed"), ("companion", "programmingAllowed")):
            changed = deepcopy(self.contract)
            changed[section][key] = True
            with self.assertRaises(ValueError):
                self.validate(changed)

    def test_pure_validation_does_not_become_native_qualification(self):
        changed = deepcopy(self.contract)
        changed["nativeExecution"] = "PASSED"
        with self.assertRaisesRegex(ValueError, "qualify native"):
            self.validate(changed)

    def test_preflight_cannot_expect_a_synthesis_control_without_pairing(self):
        changed = deepcopy(self.contract)
        case = next(case for case in changed["scenarios"] if case["id"] == "fpga-native-mapping-preflight")
        case["steps"] = [step for step in case["steps"] if step["action"] != "pairRealCompanion"]
        with self.assertRaisesRegex(ValueError, "paired companion"):
            self.validate(changed)

    def test_already_completed_job_cannot_qualify_cancellation(self):
        changed = deepcopy(self.contract)
        case = next(case for case in changed["scenarios"] if case["id"] == "fpga-native-cancel-real-job")
        case["cancellationPolicy"]["completedBeforeCancellation"] = "PASS"
        with self.assertRaisesRegex(ValueError, "artificial cancellation"):
            self.validate(changed)

    def test_terminal_state_cannot_be_allowed_as_a_cancellation_opportunity(self):
        changed = deepcopy(self.contract)
        case = next(case for case in changed["scenarios"] if case["id"] == "fpga-native-cancel-real-job")
        step = next(step for step in case["steps"] if step["action"] == "observeNonterminalJob")
        step["allowedStates"].append("succeeded")
        with self.assertRaisesRegex(ValueError, "actual queued or running"):
            self.validate(changed)

    def test_cancellation_cannot_use_retries_or_tool_delays(self):
        for key, value in (("maxSubmissions", 2), ("allowedToolDelaySeconds", 1), ("syntheticWaitOperationAllowed", True)):
            changed = deepcopy(self.contract)
            case = next(case for case in changed["scenarios"] if case["id"] == "fpga-native-cancel-real-job")
            case["cancellationPolicy"][key] = value
            with self.assertRaisesRegex(ValueError, "artificial cancellation"):
                self.validate(changed)

    def test_real_package_error_cannot_be_replaced_with_a_fixture_result(self):
        changed = deepcopy(self.contract)
        case = next(case for case in changed["scenarios"] if case["id"] == "fpga-native-invalid-package-pin-error")
        step = next(step for step in case["steps"] if step["action"] == "assertToolError")
        step["stage"] = "fixture"
        with self.assertRaisesRegex(ValueError, "real nextpnr"):
            self.validate(changed)


if __name__ == "__main__":
    unittest.main()
