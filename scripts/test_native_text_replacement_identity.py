"""Run the production keyboard-label policy with Swift, without XCTest or devices."""

from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class NativeTextReplacementIdentityTests(unittest.TestCase):
    def test_production_label_and_final_value_predicates(self):
        source = (ROOT / "scripts/ios_native_ui/NativeTextReplacement.swift").read_text()
        policy = source.split("public enum NativeTextReplacementIdentity", 1)[1].split(
            "extension NativeUIHarness", 1
        )[0]
        policy = "public enum NativeTextReplacementIdentity" + policy
        empty_value = re.search(
            r"    private func nativeTextValueIsEmpty\(.*?\n    \}", source, re.DOTALL
        ).group(0).replace("private func", "func", 1)
        final_predicate = re.search(
            r"return expectedFinalValue\.isEmpty[^\n]+", source
        ).group(0).replace("self.", "")
        default_call = re.search(
            r"public func replacePresentedText\(_ field: XCUIElement, with text: String\) throws \{\n([^\n]+)",
            source,
        ).group(1)
        self.assertEqual(
            default_call.strip(),
            "try replacePresentedText(field, with: text, keyboardIdentity: nil, expectedFinalValue: text, fieldScope: nil)",
        )
        checks = r'''
let keyboard = NativeTextReplacementIdentity.keyboardBuffer(componentLabel: "IO_KEYBOARD")
func check(_ condition: Bool, _ name: String) {
    precondition(condition, name)
    print("PASS " + name)
}
check(nativeTextReplacementLabelMatches("Find", original: "Find", identity: nil), "static unchanged label")
check(!nativeTextReplacementLabelMatches("Y", original: "Find", identity: nil), "static Find-to-Y rejected")
check(nativeTextReplacementLabelMatches("", original: "", identity: nil), "static unlabeled property editor retained")
check(nativeTextReplacementLabelMatches("IO_KEYBOARD · 0/16", original: "IO_KEYBOARD · 2/16", identity: keyboard), "dynamic cleared buffer")
check(nativeTextReplacementLabelMatches("IO_KEYBOARD · 2/16", original: "IO_KEYBOARD · 0/16", identity: keyboard), "dynamic typed buffer")
check(nativeTextReplacementLabelMatches("IO_KEYBOARD · 4/4", original: "IO_KEYBOARD · 0/4", identity: keyboard), "dynamic full buffer")
for invalid in ["", "IO_KEYBOARD", "OTHER · 2/16", "NOT_IO_KEYBOARD · 2/16", "IO_KEYBOARD_EXTRA · 2/16",
                "IO_KEYBOARD· 2/16", "IO_KEYBOARD · ", "IO_KEYBOARD · -1/16", "IO_KEYBOARD · 17/16",
                "IO_KEYBOARD · 2/0", "IO_KEYBOARD · 2/257", "IO_KEYBOARD · 2/16 extra", "IO_KEYBOARD · 2/16/1",
                "IO_KEYBOARD · ٢/16"] {
    check(!nativeTextReplacementLabelMatches(invalid, original: "IO_KEYBOARD · 0/16", identity: keyboard), "reject " + invalid)
}
for invalidComponent in ["", " ", "\n"] {
    check(!nativeTextReplacementLabelMatches(" · 0/16", original: "", identity: .keyboardBuffer(componentLabel: invalidComponent)), "reject empty component label")
}
check(finalValueMatches("AB", expectedFinalValue: "AB"), "exact final value")
check(!finalValueMatches("Y", expectedFinalValue: "Find"), "static wrong final value rejected")
check(!finalValueMatches("ABCD", expectedFinalValue: "ABCDE"), "unopted truncation rejected")
check(finalValueMatches("ABCD", expectedFinalValue: "ABCD"), "explicit capacity final value accepted")
check(!finalValueMatches("ABC", expectedFinalValue: "ABCD"), "wrong capacity result rejected")
check(finalValueMatches("", expectedFinalValue: ""), "empty final value")
'''
        swift = "import Foundation\n" + policy + "\n" + empty_value + "\n"
        swift += "func finalValueMatches(_ actual: Any?, expectedFinalValue: String) -> Bool {\n"
        swift += final_predicate.replace("field.placeholderValue", "nil") + "\n}\n" + checks
        with tempfile.TemporaryDirectory(prefix="native-keyboard-identity-") as directory:
            path = Path(directory) / "identity.swift"
            path.write_text(swift)
            completed = subprocess.run(
                ["xcrun", "swift", str(path)], text=True, capture_output=True, check=False
            )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertEqual(completed.stdout.count("PASS "), 29)
        print(completed.stdout, end="")


if __name__ == "__main__":
    unittest.main()
