#!/usr/bin/env python3
"""Exercise the file-only section gate with synthetic Mach-O and DER payloads."""
import importlib.util
import json
from pathlib import Path
import plistlib
import struct
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("simulator_entitlements", Path(__file__).with_name("simulator_entitlements.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
APP_ID = "thalesmms.logisim.validation.revocationfixture"
ENTITLEMENTS = {MODULE.GROUP_KEY: [GROUP]}


def tlv(tag, contents):
    length = len(contents)
    encoded = bytes([length]) if length < 128 else bytes([0x82]) + length.to_bytes(2, "big")
    if 128 <= length <= 255:
        encoded = bytes([0x81, length])
    return bytes([tag]) + encoded + contents


def der(value):
    if isinstance(value, str):
        return tlv(0x0C, value.encode())
    if isinstance(value, bool):
        return tlv(0x01, b"\xff" if value else b"\0")
    if isinstance(value, list):
        return tlv(0x30, b"".join(der(item) for item in value))
    if isinstance(value, dict):
        return tlv(0xB0, b"".join(tlv(0x30, der(key) + der(item)) for key, item in value.items()))
    raise ValueError("Unsupported synthetic value")


def entitlement_der(value):
    return tlv(0x70, tlv(0x02, b"\x01") + der(value))


def macho(section_payloads, kind=2):
    commands_size = 72 + 80 * len(section_payloads)
    offset = 32 + commands_size
    payload = b""
    sections = []
    for name, contents in section_payloads:
        sections.append(struct.pack("<16s16sQQ8I", name.encode(), b"__TEXT", 0, len(contents),
                                    offset + len(payload), 0, 0, 0, 0, 0, 0, 0))
        payload += contents
    header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, kind, 1, commands_size, 0, 0)
    command = struct.pack("<2I16s4Q4I", 0x19, commands_size, b"__TEXT", 0,
                          offset + len(payload), 0, offset + len(payload), 5, 5, len(sections), 0)
    return header + command + b"".join(sections) + payload


class SimulatorEntitlementTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "Fixture.app"
        self.bundle.mkdir()
        (self.bundle / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": APP_ID, "CFBundleExecutable": "Fixture"}))
        self.output = self.root / "retained"
        self.output.mkdir()

    def install(self, xml=ENTITLEMENTS, encoded=None, main=True):
        values = [("__entitlements", plistlib.dumps(xml)),
                  ("__ents_der", entitlement_der(xml) if encoded is None else encoded)]
        (self.bundle / "Fixture").write_bytes(macho(values if main else []))
        if not main:
            (self.bundle / "Fixture.debug.dylib").write_bytes(macho(values, kind=6))

    def verify(self):
        return MODULE.verify_bundle(self.bundle, APP_ID, GROUP, self.output, "App")

    def test_group_pair_is_retained_and_requires_native_review(self):
        self.install()
        report = self.verify()
        self.assertEqual(ENTITLEMENTS, report["simulatedEntitlements"])
        self.assertEqual({}, report["generatedAdditionalKeys"])
        self.assertEqual(["main"], report["sectionOwners"])
        self.assertTrue(report["mainHasSectionPair"])
        self.assertEqual("REQUIRED_BEFORE_NATIVE", report["generatedEntitlementsReview"])
        self.assertEqual("NOT_RUN", report["nativeExecution"])
        self.assertEqual(2, len(list(self.output.glob("*.bin"))))

    def test_generated_keys_and_values_are_retained_without_approval_claim(self):
        value = dict(ENTITLEMENTS, **{"application-identifier": APP_ID, "get-task-allow": True})
        self.install(value)
        self.assertEqual({"application-identifier": APP_ID, "get-task-allow": True},
                         self.verify()["generatedAdditionalKeys"])

    def test_debug_owner_is_explicit_and_not_reported_as_main(self):
        self.install(main=False)
        report = self.verify()
        self.assertEqual(["debug-dylib"], report["sectionOwners"])
        self.assertFalse(report["mainHasSectionPair"])

    def test_no_sections_fails(self):
        (self.bundle / "Fixture").write_bytes(macho([]))
        with self.assertRaisesRegex(ValueError, "no Simulator"):
            self.verify()

    def test_split_or_missing_pair_fails(self):
        (self.bundle / "Fixture").write_bytes(macho([("__entitlements", plistlib.dumps(ENTITLEMENTS))]))
        with self.assertRaisesRegex(ValueError, "same image"):
            self.verify()

    def test_wrong_group_or_multiple_groups_fail(self):
        for groups in [["group.other"], [GROUP, "group.other"], ["group.*"]]:
            self.install({MODULE.GROUP_KEY: groups})
            with self.subTest(groups=groups), self.assertRaisesRegex(ValueError, "exact fixture"):
                self.verify()

    def test_nonempty_team_fails(self):
        self.install(dict(ENTITLEMENTS, **{"com.apple.developer.team-identifier": "TEAM123"}))
        with self.assertRaisesRegex(ValueError, "Team identifier"):
            self.verify()

    def test_different_xml_and_der_fail(self):
        self.install(encoded=entitlement_der({MODULE.GROUP_KEY: ["group.other"]}))
        with self.assertRaisesRegex(ValueError, "disagree"):
            self.verify()

    def test_bool_and_integer_are_not_semantically_equal(self):
        self.install(dict(ENTITLEMENTS, flag=True), encoded=tlv(0x70, tlv(2, b"\x01") +
            tlv(0xB0, tlv(0x30, der(MODULE.GROUP_KEY) + der([GROUP])) + tlv(0x30, der("flag") + tlv(2, b"\x01")))))
        with self.assertRaisesRegex(ValueError, "disagree"):
            self.verify()

    def test_duplicate_der_dictionary_key_fails(self):
        entry = tlv(0x30, der(MODULE.GROUP_KEY) + der([GROUP]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            MODULE.der_entitlements(tlv(0x70, tlv(2, b"\x01") + tlv(0xB0, entry + entry)))

    def test_truncated_noncanonical_and_trailing_der_fail(self):
        valid = entitlement_der(ENTITLEMENTS)
        for malformed in [valid[:-1], valid + b"\0", b"\x70\x80", b"\x70\x81\x01\0"]:
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                MODULE.der_entitlements(malformed)

    def test_macho_size_cpu_and_duplicate_sections_fail(self):
        valid = macho([("__entitlements", b"a"), ("__ents_der", b"b")])
        bad_cpu = bytearray(valid)
        struct.pack_into("<I", bad_cpu, 4, 7)
        bad_size = bytearray(valid)
        struct.pack_into("<I", bad_size, 36, 7)
        duplicate = macho([("__entitlements", b"a"), ("__entitlements", b"a")])
        for invalid in [valid[:20], bytes(bad_cpu), bytes(bad_size), duplicate]:
            with self.subTest(invalid=invalid[:40]), self.assertRaises(ValueError):
                MODULE.macho_sections(invalid)


if __name__ == "__main__":
    unittest.main()
