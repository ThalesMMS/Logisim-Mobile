"""Source-only regression tests. No desktop classes or Java runtime are loaded.

The optional full-source test requires the pinned desktop checkout. Set
LOGISIM_DESKTOP_SOURCE when it is not a sibling of the project/worktree set.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("desktop_registry_ids", ROOT / "scripts/desktop_registry_ids.py")
ids = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ids
SPEC.loader.exec_module(ids)
DESKTOP_REVISION = "d58a61e0c290361722b43df5b1a6b86c1f7c7f0d"

# Reviewed IDs from all active registrations at DESKTOP_REVISION. These are
# literals, not expectations recomputed by the resolver or generated matrix.
EXPECTED_IDS = {'Base/Text.FACTORY': 'Text',
 'Base/Image.FACTORY': 'Image',
 'Base/WiringTool': 'Wiring Tool',
 'Base/PokeTool': 'Poke Tool',
 'Base/EditTool': 'Edit Tool',
 'Base/TextTool': 'Text Tool',
 'Base/MenuTool': 'Menu Tool',
 'Gates/NotGate.FACTORY': 'NOT Gate',
 'Gates/Buffer.FACTORY': 'Buffer',
 'Gates/AndGate.FACTORY': 'AND Gate',
 'Gates/OrGate.FACTORY': 'OR Gate',
 'Gates/NandGate.FACTORY': 'NAND Gate',
 'Gates/NorGate.FACTORY': 'NOR Gate',
 'Gates/XorGate.FACTORY': 'XOR Gate',
 'Gates/XnorGate.FACTORY': 'XNOR Gate',
 'Gates/OddParityGate.FACTORY': 'Odd Parity',
 'Gates/EvenParityGate.FACTORY': 'Even Parity',
 'Gates/ControlledBuffer.FACTORY_BUFFER': 'Controlled Buffer',
 'Gates/ControlledBuffer.FACTORY_INVERTER': 'Controlled Inverter',
 'Gates/Pla.FACTORY': 'PLA',
 'Wiring/SplitterFactory.instance': 'Splitter',
 'Wiring/Pin.FACTORY': 'Pin',
 'Wiring/Probe.FACTORY': 'Probe',
 'Wiring/Tunnel.FACTORY': 'Tunnel',
 'Wiring/PullResistor.FACTORY': 'Pull Resistor',
 'Wiring/Clock.FACTORY': 'Clock',
 'Wiring/PowerOnReset.FACTORY': 'POR',
 'Wiring/Constant.FACTORY': 'Constant',
 'Wiring/Power': 'Power',
 'Wiring/Ground': 'Ground',
 'Wiring/DoNotConnect': 'NoConnect',
 'Wiring/Transistor': 'Transistor',
 'Wiring/TransmissionGate': 'Transmission Gate',
 'Wiring/BitExtender': 'Bit Extender',
 'Plexers/Multiplexer': 'Multiplexer',
 'Plexers/Demultiplexer': 'Demultiplexer',
 'Plexers/Decoder': 'Decoder',
 'Plexers/PriorityEncoder': 'Priority Encoder',
 'Plexers/BitSelector': 'BitSelector',
 'Arithmetic/Adder': 'Adder',
 'Arithmetic/Subtractor': 'Subtractor',
 'Arithmetic/Multiplier': 'Multiplier',
 'Arithmetic/Divider': 'Divider',
 'Arithmetic/Negator': 'Negator',
 'Arithmetic/Exponentiator': 'Exponentiator',
 'Arithmetic/SquareRoot': 'SquareRoot',
 'Arithmetic/Absolute': 'Absolute',
 'Arithmetic/Comparator': 'Comparator',
 'Arithmetic/MinMax': 'MinMax',
 'Arithmetic/Shifter': 'Shifter',
 'Arithmetic/BitAdder': 'BitAdder',
 'Arithmetic/BitFinder': 'BitFinder',
 'FPArithmetic/FpConstant': 'FPConstant',
 'FPArithmetic/FpAdder': 'FPAdder',
 'FPArithmetic/FpSubtractor': 'FPSubtractor',
 'FPArithmetic/FpMultiplier': 'FPMultiplier',
 'FPArithmetic/FpDivider': 'FPDivider',
 'FPArithmetic/FpNegator': 'FPNegator',
 'FPArithmetic/FpExponentiator': 'FPExponentiator',
 'FPArithmetic/FpLogarithm': 'FPLogarithm',
 'FPArithmetic/FpSquareRoot': 'FPSquareRoot',
 'FPArithmetic/FpAbsolute': 'FPAbsolute',
 'FPArithmetic/FpComparator': 'FPComparator',
 'FPArithmetic/FpMinMax': 'FPMinMax',
 'FPArithmetic/FpRound': 'FPRound',
 'FPArithmetic/FpTrigonometry': 'FPTrigonometry',
 'FPArithmetic/FpClassificator': 'FPClassificator',
 'FPArithmetic/FpToFp': 'FPToFP',
 'FPArithmetic/FpToInt': 'FPToInt',
 'FPArithmetic/IntToFp': 'IntToFP',
 'Memory/DFlipFlop': 'D Flip-Flop',
 'Memory/TFlipFlop': 'T Flip-Flop',
 'Memory/JKFlipFlop': 'J-K Flip-Flop',
 'Memory/SRFlipFlop': 'S-R Flip-Flop',
 'Memory/Register': 'Register',
 'Memory/Counter': 'Counter',
 'Memory/ShiftRegister': 'Shift Register',
 'Memory/Random': 'Random',
 'Memory/Ram': 'RAM',
 'Memory/Rom': 'ROM',
 'Memory/DualRam': 'DualRAM',
 'I/O/Button': 'Button',
 'I/O/DipSwitch': 'DipSwitch',
 'I/O/Joystick': 'Joystick',
 'I/O/Keyboard': 'Keyboard',
 'I/O/Led': 'LED',
 'I/O/LedBar': 'LedBar',
 'I/O/RgbLed': 'RGBLED',
 'I/O/SevenSegment': '7-Segment Display',
 'I/O/HexDigit': 'Hex Digit Display',
 'I/O/DotMatrix': 'DotMatrix',
 'I/O/Tty': 'TTY',
 'I/O/PortIo': 'PortIO',
 'I/O/ReptarLocalBus': 'ReptarLB',
 'I/O/Telnet': 'Telnet',
 'I/O/RealTimeClock': 'RealTimeClock',
 'I/O/MatrixKeypad': 'MatrixKeypad',
 'I/O/Video.factory': 'RGB Video',
 'TTL/Ttl7400': '7400',
 'TTL/Ttl7402': '7402',
 'TTL/Ttl7404': '7404',
 'TTL/Ttl7405': '7405',
 'TTL/Ttl7408': '7408',
 'TTL/Ttl7410': '7410',
 'TTL/Ttl7411': '7411',
 'TTL/Ttl7413': '7413',
 'TTL/Ttl7414': '7414',
 'TTL/Ttl7418': '7418',
 'TTL/Ttl7419': '7419',
 'TTL/Ttl7420': '7420',
 'TTL/Ttl7421': '7421',
 'TTL/Ttl7424': '7424',
 'TTL/Ttl7427': '7427',
 'TTL/Ttl7430': '7430',
 'TTL/Ttl7432': '7432',
 'TTL/Ttl7434': '7434',
 'TTL/Ttl7436': '7436',
 'TTL/Ttl7438': '7438',
 'TTL/Ttl7442': '7442',
 'TTL/Ttl7443': '7443',
 'TTL/Ttl7444': '7444',
 'TTL/Ttl7447': '7447',
 'TTL/Ttl7451': '7451',
 'TTL/Ttl7454': '7454',
 'TTL/Ttl7458': '7458',
 'TTL/Ttl7464': '7464',
 'TTL/Ttl7474': '7474',
 'TTL/Ttl7476': '7476',
 'TTL/Ttl7485': '7485',
 'TTL/Ttl7486': '7486',
 'TTL/Ttl7487': '7487',
 'TTL/Ttl7493': '7493',
 'TTL/Ttl74107': '74107',
 'TTL/Ttl74123': '74123',
 'TTL/Ttl74125': '74125',
 'TTL/Ttl74132': '74132',
 'TTL/Ttl74138': '74138',
 'TTL/Ttl74139': '74139',
 'TTL/Ttl74148': '74148',
 'TTL/Ttl74151': '74151',
 'TTL/Ttl74153': '74153',
 'TTL/Ttl74157': '74157',
 'TTL/Ttl74158': '74158',
 'TTL/Ttl74161': '74161',
 'TTL/Ttl74163': '74163',
 'TTL/Ttl74164': '74164',
 'TTL/Ttl74165': '74165',
 'TTL/Ttl74166': '74166',
 'TTL/Ttl74173': '74173',
 'TTL/Ttl74175': '74175',
 'TTL/Ttl74181': '74181',
 'TTL/Ttl74182': '74182',
 'TTL/Ttl74190': '74190',
 'TTL/Ttl74192': '74192',
 'TTL/Ttl74193': '74193',
 'TTL/Ttl74194': '74194',
 'TTL/Ttl74240': '74240',
 'TTL/Ttl74241': '74241',
 'TTL/Ttl74244': '74244',
 'TTL/Ttl74245': '74245',
 'TTL/Ttl74257': '74257',
 'TTL/Ttl74259': '74259',
 'TTL/Ttl74266': '74266',
 'TTL/Ttl74273': '74273',
 'TTL/Ttl74283': '74283',
 'TTL/Ttl74299': '74299',
 'TTL/Ttl74374': '74374',
 'TTL/Ttl74365': '74365',
 'TTL/Ttl74377': '74377',
 'TTL/Ttl74381': '74381',
 'TTL/Ttl74393': '74393',
 'TTL/Ttl74390': '74390',
 'TTL/Ttl74541': '74541',
 'TTL/Ttl74594': '74594',
 'TTL/Ttl74595': '74595',
 'TTL/Ttl74640': '74640',
 'TTL/Ttl74670': '74670',
 'TTL/Ttl74688': '74688',
 'TTL/Ttl744017': '744017',
 'TTL/Ttl744060': '744060',
 'TTL/Ttl744094': '744094',
 'TTL/Ttl744075': '744075',
 'TTL/Ttl747266': '747266',
 'HDL-IP/VhdlEntityComponent': 'VHDL Entity',
 'HDL-IP/BlifCircuitComponent': 'BLIFCircuit',
 'TCL/TclConsoleReds': 'TclConsoleReds',
 'TCL/TclGeneric': 'TclGeneric',
 'BFH-Praktika/BinToBcd': 'Binary_to_BCD_converter',
 'BFH-Praktika/BcdToSevenSegmentDisplay': 'BCD_to_7_Segment_decoder',
 'Input/Output-Extra/Switch': 'Switch',
 'Input/Output-Extra/Buzzer': 'Buzzer',
 'Input/Output-Extra/Slider': 'Slider',
 'Input/Output-Extra/DigitalOscilloscope': 'Digital Oscilloscope',
 'Input/Output-Extra/PlaRom': 'PlaRom',
 'Input/Output-Extra/TwoWaySwitch': 'TwoWaySwitch',
 'Input/Output-Extra/TwoPinLed': 'TWOPINLED',
 'Input/Output-Extra/NE555': 'NE555',
 'Soc/Rv32imRiscV': 'Rv32im',
 'Soc/Nios2': 'Nios2',
 'Soc/SocBus': 'SocBus',
 'Soc/SocMemory': 'Socmem',
 'Soc/SocPio': 'SocPio',
 'Soc/SocVga': 'SocVga',
 'Soc/SocDma': 'SocDma',
 'Soc/JtagUart': 'SocJtagUart'}

CORRECTED_IDS = {
    "Gates/AndGate.FACTORY": "AND Gate",
    "Gates/OrGate.FACTORY": "OR Gate",
    "Gates/NandGate.FACTORY": "NAND Gate",
    "Gates/NorGate.FACTORY": "NOR Gate",
    "Gates/XorGate.FACTORY": "XOR Gate",
    "Gates/XnorGate.FACTORY": "XNOR Gate",
    "Gates/OddParityGate.FACTORY": "Odd Parity",
    "Gates/EvenParityGate.FACTORY": "Even Parity",
    "Wiring/SplitterFactory.instance": "Splitter",
}

FAIL_CLOSED_FIXTURES = [{'name': 'dynamic_super_must_not_use_unused_id',
  'files': {'src/main/java/p/Widget.java': 'class Widget extends Factory {\n'
                                           '  static final String _ID = "Unused fallback";\n'
                                           '  static final Widget FACTORY = new Widget();\n'
                                           '  static String dynamicId() { return '
                                           'System.getProperty("factory.id"); }\n'
                                           '  Widget() { super(dynamicId()); }\n'
                                           '}'},
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': 'Widget.FACTORY',
  'expected': None},
 {'name': 'dynamic_getname_must_not_use_unused_id',
  'files': {'src/main/java/p/Widget.java': 'class Widget extends Factory {\n'
                                           '  static final String _ID = "Unused fallback";\n'
                                           '  static final Widget FACTORY = new Widget();\n'
                                           '  Widget() { super("Constructor ID"); }\n'
                                           '  public String getName() { if (Boolean.getBoolean("flag")) return '
                                           '"A"; return "B"; }\n'
                                           '}'},
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': 'Widget.FACTORY',
  'expected': None},
 {'name': 'ambiguous_constructor_must_not_use_unused_id',
  'files': {'src/main/java/p/Widget.java': 'class Widget extends Factory {\n'
                                           '  static final String _ID = "Unused fallback";\n'
                                           '  static final Widget FACTORY = new Widget(true);\n'
                                           '  Widget(boolean value) { super("Bool"); }\n'
                                           '  Widget(String value) { super("String"); }\n'
                                           '}'},
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': 'Widget.FACTORY',
  'expected': None}]

SOURCE_FIXTURES = [{'name': 'outer_id_ignores_comments',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Actual ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           '// public static final String _ID = "Comment ID";\n'
                                           '/* super("Comment super"); */\n'
                                           'class Widget { public static final String _ID = "Actual ID"; }\n'}},
 {'name': 'outer_id_ignores_inner_helper_id',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Widget ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget {\n'
                                           '  private static class Helper { public static final String _ID = '
                                           '"Helper ID"; }\n'
                                           '  public static final String _ID = "Widget ID";\n'
                                           '}\n'}},
 {'name': 'constructor_super_ignores_inner_helper',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Right widget',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  private static class Helper extends Factory { Helper() { '
                                           'super("Wrong helper"); } }\n'
                                           '  Widget() { super("Right widget", displayName()); }\n'
                                           '}\n'}},
 {'name': 'constructor_super_ignores_string_literals',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Right ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  String decoy = "super(\\"Wrong literal\\")";\n'
                                           '  Widget() { super("Right ID", displayName()); }\n'
                                           '}\n'}},
 {'name': 'constructor_super_comments_and_multiline',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Multiline ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  Widget() {\n'
                                           '    super /* valid comment */ (\n'
                                           '       "Multiline ID",\n'
                                           '       displayName());\n'
                                           '  }\n'
                                           '}\n'}},
 {'name': 'getname_forwards_imported_id',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Forwarded ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'import other.Target;\n'
                                           'class Widget { public String getName() { return Target._ID; } }\n',
            'src/main/java/other/Target.java': 'package other; public class Target { public static final String '
                                               '_ID = "Forwarded ID"; }'}},
 {'name': 'boolean_factory_false',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': 'Widget.FIRST',
  'expected': 'Buffered ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  static final Widget FIRST = new Widget(false);\n'
                                           '  static final Widget SECOND = new Widget(true);\n'
                                           '  private Widget(boolean inverted) { super(inverted ? "Inverted ID" : '
                                           '"Buffered ID", displayName()); }\n'
                                           '}\n'}},
 {'name': 'boolean_factory_true',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': 'Widget.SECOND',
  'expected': 'Inverted ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  static final Widget FIRST = new Widget(false);\n'
                                           '  static final Widget SECOND = new Widget(true);\n'
                                           '  private Widget(boolean inverted) { super(inverted ? "Inverted ID" : '
                                           '"Buffered ID", displayName()); }\n'
                                           '}\n'}},
 {'name': 'ambiguous_boolean_factory_without_expression',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': None,
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  static final Widget FIRST = new Widget(false);\n'
                                           '  static final Widget SECOND = new Widget(true);\n'
                                           '  private Widget(boolean inverted) { super(inverted ? "Inverted ID" : '
                                           '"Buffered ID", displayName()); }\n'
                                           '}\n'}},
 {'name': 'unknown_factory_expression',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': 'Widget.DOES_NOT_EXIST',
  'expected': None,
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory {\n'
                                           '  static final Widget FIRST = new Widget(false);\n'
                                           '  static final Widget SECOND = new Widget(true);\n'
                                           '  private Widget(boolean inverted) { super(inverted ? "Inverted ID" : '
                                           '"Buffered ID", displayName()); }\n'
                                           '}\n'}},
 {'name': 'dynamic_super_is_not_class_name',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': None,
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget extends Factory { Widget() { super(buildId(), '
                                           'displayName()); } }\n'}},
 {'name': 'constant_concat_is_not_truncated',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': None,
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget { public static final String _ID = "Prefix" + suffix(); '
                                           '}\n'}},
 {'name': 'sibling_class_id_is_not_widget_id',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Widget ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Helper { public static final String _ID = "Sibling ID"; }\n'
                                           'class Widget extends Factory { Widget() { super("Widget ID", '
                                           'displayName()); } }\n'}},
 {'name': 'escaped_literal_is_decoded',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Quote " ID',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           'class Widget { public static final String _ID = "Quote \\" ID"; }\n'}},
 {'name': 'fake_class_in_comment_is_not_selected',
  'source': 'src/main/java/p/Widget.java',
  'class_name': 'Widget',
  'expression': None,
  'expected': 'Actual class',
  'files': {'src/main/java/p/Widget.java': 'package p;\n'
                                           '/* class Widget { static final String _ID = "Wrong class"; } */\n'
                                           'class Widget { public static final String _ID = "Actual class"; }\n'}}]


class DesktopRegistryIdsTest(unittest.TestCase):
    def test_frozen_ids_cover_every_active_registration_and_the_nine_corrections(self):
        data = json.loads((ROOT / "parity/evolution-d58a61e0.json").read_text())
        self.assertEqual(DESKTOP_REVISION, data["revision"])
        self.assertEqual(205, len(EXPECTED_IDS))
        self.assertEqual(EXPECTED_IDS, {row["key"]: row["component_id"] for row in data["libraries"]})
        self.assertEqual(CORRECTED_IDS, {key: EXPECTED_IDS[key] for key in CORRECTED_IDS})
        self.assertEqual("Controlled Buffer", EXPECTED_IDS["Gates/ControlledBuffer.FACTORY_BUFFER"])
        self.assertEqual("Controlled Inverter", EXPECTED_IDS["Gates/ControlledBuffer.FACTORY_INVERTER"])

    def test_literal_source_fixtures_do_not_confuse_helpers_comments_or_dynamic_expressions(self):
        for fixture in SOURCE_FIXTURES:
            with self.subTest(name=fixture.get("name")), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for relative, content in fixture["files"].items():
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(content)
                self.assertEqual(fixture["expected"], ids.factory_id(root, root / fixture["source"],
                                 fixture["class_name"], fixture["expression"]))

    def test_dynamic_name_or_ambiguous_constructor_returns_none_without_id_fallback(self):
        for fixture in FAIL_CLOSED_FIXTURES:
            with self.subTest(name=fixture.get("name")), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for relative, content in fixture["files"].items():
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(content)
                self.assertIsNone(ids.factory_id(root, root / fixture["source"],
                                  fixture["class_name"], fixture["expression"]))

    def test_missing_or_outside_source_does_not_invent_a_class_name(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory)
            self.assertIsNone(ids.factory_id(root, root / "Missing.java", "Missing"))
            (root / "Inside.java").write_text('class Inside { static final String _ID = "Inside ID"; }')
            self.assertEqual("Inside ID", ids.factory_id(root, Path("Inside.java"), "Inside"))
            source = Path(outside) / "Outside.java"
            source.write_text('class Outside { static final String _ID = "Not in this desktop"; }')
            self.assertIsNone(ids.factory_id(root, source, "Outside"))

    def test_named_factory_must_be_registered_in_its_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Factory.java"
            source.write_text('class Factory { static final String _ID = "Actual"; '
                              'public static final Factory FACTORY = new Factory(); }')
            self.assertEqual("Actual", ids.factory_id(root, source, "Factory", "Factory.FACTORY"))
            self.assertIsNone(ids.factory_id(root, source, "Factory", "Factory.NOT_REGISTERED"))
            self.assertIsNone(ids.factory_id(root, source, "Factory", "Other.FACTORY"))
            self.assertIsNone(ids.factory_id(root, source, "Factory", "wrong.package.Factory.FACTORY"))


class PinnedDesktopRegistryIdsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        configured = os.environ.get("LOGISIM_DESKTOP_SOURCE")
        candidates = [Path(configured)] if configured else [ROOT.parent / "logisim-evolution", ROOT.parent.parent / "logisim-evolution"]
        cls.desktop = next((path.resolve() for path in candidates if
                            (path / "src/main/java/com/cburch/logisim/std/Builtin.java").is_file()), None)
        if cls.desktop is None:
            if configured:
                raise AssertionError("LOGISIM_DESKTOP_SOURCE does not contain the desktop sources")
            raise unittest.SkipTest("Set LOGISIM_DESKTOP_SOURCE to the pinned desktop checkout for the 205-source check")
        revision = subprocess.check_output(["git", "-C", str(cls.desktop), "rev-parse", "HEAD"], text=True).strip()
        if revision != DESKTOP_REVISION:
            raise AssertionError(f"Desktop revision {revision} differs from pinned {DESKTOP_REVISION}")
        dirty = subprocess.check_output(["git", "-C", str(cls.desktop), "status", "--porcelain=v1", "--untracked-files=no",
                                         "--", "src/main/java"], text=True).strip()
        if dirty:
            raise AssertionError("Pinned desktop Java sources have uncommitted changes")

    def test_all_205_factory_ids_match_the_pinned_sources(self):
        data = json.loads((ROOT / "parity/evolution-d58a61e0.json").read_text())
        self.assertEqual(205, len(data["libraries"]))
        for row in data["libraries"]:
            expression = row["entry"] if "." in row["entry"] else None
            with self.subTest(key=row["key"]):
                self.assertEqual(EXPECTED_IDS[row["key"]], ids.factory_id(self.desktop,
                    self.desktop / row["desktop_source"], row["entry"].split(".")[0], expression))


if __name__ == "__main__":
    unittest.main()
