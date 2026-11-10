#!/usr/bin/env python3
"""Check Inspector exports and modal selectors using literal XML, without adb."""

from copy import deepcopy
import unittest
import xml.etree.ElementTree as ET

from android_inspector_scenarios import assert_unchanged, assert_width_edit, inspector_dialog


PROJECT = '''<project version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/>
<circuit name="main"><a name="future" val="keep"/>
<comp lib="0" name="Pin" loc="(100,100)"><a name="type" val="input"/>
<a name="label" val="E2E_INSPECTOR"/><a name="width" val="1"/>
<a name="future-pin" val="opaque"/></comp></circuit></project>'''

DIALOG = '''<hierarchy><node package="thalesmms.logisim.mobile.e2e" bounds="[0,0][400,800]">
<node package="thalesmms.logisim.mobile.e2e" resource-id="editor-inspector-sheet"><node package="thalesmms.logisim.mobile.e2e" class="android.widget.EditText" text="E2E_INSPECTOR" enabled="true"/>
<node package="thalesmms.logisim.mobile.e2e" class="android.widget.Button" text="Apply" enabled="true"/></node>
<node resource-id="numeric-dialog"><node package="thalesmms.logisim.mobile.e2e" class="android.widget.EditText" text="0" enabled="true" focused="true"/>
<node package="thalesmms.logisim.mobile.e2e" text="Invalid value for width: 0."/>
<node package="thalesmms.logisim.mobile.e2e" class="android.widget.Button" enabled="false" resource-id="dialog-apply">
<node package="thalesmms.logisim.mobile.e2e" text="Apply"/></node>
<node package="thalesmms.logisim.mobile.e2e" class="android.widget.Button" text="Cancel" enabled="true" resource-id="dialog-cancel"/>
</node></node></hierarchy>'''


class InspectorExportTests(unittest.TestCase):
    def setUp(self):
        self.before = ET.fromstring(PROJECT)
        self.changed = deepcopy(self.before)
        self.changed.find("circuit/comp/a[@name='width']").set("val", "4")

    def test_width4_and_undo1_preserve_all_other_content(self):
        assert_width_edit(self.before, self.changed)
        assert_unchanged(self.before, ET.fromstring(PROJECT))
        self.assertEqual("opaque", self.changed.find("circuit/comp/a[@name='future-pin']").get("val"))

    def test_wrong_width_and_lost_metadata_are_rejected(self):
        wrong = deepcopy(self.changed)
        wrong.find("circuit/comp/a[@name='width']").set("val", "8")
        with self.assertRaises(AssertionError):
            assert_width_edit(self.before, wrong)
        self.changed.find("circuit").remove(self.changed.find("circuit/a"))
        with self.assertRaises(AssertionError):
            assert_width_edit(self.before, self.changed)

    def test_rejected_edit_cannot_change_label_or_wires(self):
        changed = deepcopy(self.before)
        changed.find("circuit/comp/a[@name='label']").set("val", "OTHER")
        with self.assertRaises(AssertionError):
            assert_unchanged(self.before, changed)
        changed = deepcopy(self.before)
        ET.SubElement(changed.find("circuit"), "wire", {"from": "(100,100)", "to": "(200,100)"})
        with self.assertRaises(AssertionError):
            assert_unchanged(self.before, changed)

    def test_duplicate_pin_attributes_are_not_silently_collapsed(self):
        ET.SubElement(self.changed.find("circuit/comp"), "a", {"name": "width", "val": "4"})
        with self.assertRaises(AssertionError):
            assert_width_edit(self.before, self.changed)


class InspectorSelectorTests(unittest.TestCase):
    def test_modal_actions_override_the_underlying_label_apply(self):
        root = ET.fromstring(DIALOG)
        scope, field, apply, cancel = inspector_dialog(root, "thalesmms.logisim.mobile.e2e", "0",
                                                     {"Apply"}, {"Cancel"}, lambda root, node: True)
        self.assertEqual("numeric-dialog", scope.get("resource-id"))
        self.assertEqual("true", field.get("focused"))
        self.assertEqual("dialog-apply", apply.get("resource-id"))
        self.assertEqual("false", apply.get("enabled"))
        self.assertEqual("dialog-cancel", cancel.get("resource-id"))

    def test_wrong_package_missing_cancel_or_duplicate_field_fails(self):
        root = ET.fromstring(DIALOG)
        with self.assertRaises(AssertionError):
            inspector_dialog(root, "another.application", "0", {"Apply"}, {"Cancel"}, lambda root, node: True)
        with self.assertRaises(AssertionError):
            inspector_dialog(root, "thalesmms.logisim.mobile.e2e", "0", {"Apply"}, {"Dismiss"}, lambda root, node: True)
        duplicate = deepcopy(root.find(".//node[@text='0']"))
        root.find(".//node[@resource-id='numeric-dialog']").append(duplicate)
        with self.assertRaises(AssertionError):
            inspector_dialog(root, "thalesmms.logisim.mobile.e2e", "0", {"Apply"}, {"Cancel"}, lambda root, node: True)

    def test_clipped_actions_fail_instead_of_using_stale_coordinates(self):
        root = ET.fromstring(DIALOG)
        with self.assertRaises(AssertionError):
            inspector_dialog(root, "thalesmms.logisim.mobile.e2e", "0", {"Apply"}, {"Cancel"},
                             lambda root, node: node.get("resource-id") != "dialog-apply")


if __name__ == "__main__":
    unittest.main()
