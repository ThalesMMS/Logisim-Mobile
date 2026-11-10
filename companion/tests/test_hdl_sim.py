"""Behavior tests. The integration cases execute the real NVC binary."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

from companion.hdl_sim import HdlSimulationManager, MAX_SOURCE_BYTES, MAX_TIME_NS, map_std_logic, parse_design


FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "hdl" / "cosim"


def design(name="and_gate", *, parameters=None):
    if name == "counter":
        ports = [{"name": "clk", "direction": "in"}, {"name": "reset", "direction": "in"}, {"name": "q", "direction": "out", "width": 4}]
    elif name == "logic_states":
        ports = [{"name": "a", "direction": "in", "width": 9}, {"name": "y", "direction": "out", "width": 9}]
    else:
        ports = [{"name": "a", "direction": "in"}, {"name": "y", "direction": "out"}]
        if name == "and_gate":
            ports.insert(1, {"name": "b", "direction": "in"})
    return {"source": (FIXTURES / f"{name}.vhd").read_text(), "topLevel": name, "ports": ports, "parameters": parameters or {}}


def file_policy_probe(prefix: str, path: Path):
    source = f'''library ieee; use ieee.std_logic_1164.all;
entity file_policy_probe is port(y: out std_logic); end;
architecture rtl of file_policy_probe is
{prefix} type tf is file of character; file f: tf open read_mode is "{path}";
begin y <= '1'; end;
'''
    return {"source": source, "topLevel": "file_policy_probe", "ports": [{"name": "y", "direction": "out"}]}


FILE_MASKING_PREFIXES = {
    "quote-character": '''constant quote: character := '"';''',
    "qualified-character": '''constant quote: character := character'('"');''',
    "return-character": '''function quote return character is begin return '"'; end function;''',
    "percent-string": '''constant quote: string := %"%;''',
    "delimited-comment": '''/* " */''',
}


class SourcePolicyTests(unittest.TestCase):
    def test_raw_source_and_parameters_remain_immutable(self):
        request = design("counter", parameters={"WIDTH": "4", "INITIAL": 5})
        parsed = parse_design(request)
        request["source"] = "changed"
        request["parameters"]["INITIAL"] = 9
        self.assertEqual(parsed.source, (FIXTURES / "counter.vhd").read_text())
        self.assertEqual(parsed.parameters, (("WIDTH", "4"), ("INITIAL", "5")))
        self.assertEqual(parse_design(design("counter", parameters={"DELAY": "5000000 fs"})).parameters, (("DELAY", "5000000 fs"),))

    def test_all_ieee_logic_states_have_explicit_mobile_mapping(self):
        self.assertEqual(map_std_logic("UX01ZWLH-"), "?E01??01?")

    def test_no_tool_runs_during_import_or_parsing(self):
        with patch("subprocess.Popen", side_effect=AssertionError("must not execute")):
            parse_design(design())
            HdlSimulationManager().close()

    def test_file_foreign_and_external_names_are_rejected(self):
        for construct in ["file f: text;", "attribute foreign of f: procedure is \"danger\";", "use std.textio.all;", "signal \\odd\\: std_logic;", "alias external is << signal .a: std_logic >>;", "library nvc;", "use std.env.all;"]:
            with self.subTest(construct=construct):
                request = design()
                request["source"] += "\n" + construct
                response = HdlSimulationManager().handle_request("hdl.start", request)
                self.assertFalse(response["ok"])
                self.assertEqual(response["code"], "unsupported_source")
                self.assertEqual(response["outputs"], {})

    def test_comments_and_report_strings_do_not_trigger_source_policy(self):
        request = design()
        request["source"] += '\n-- file std.textio foreign\n'
        request["source"] = request["source"].replace("y <= a and b;", 'report "file std.textio foreign"; y <= a and b;')
        parse_design(request)

    def test_quotes_cannot_hide_a_file_declaration_before_tool_launch(self):
        for name, prefix in FILE_MASKING_PREFIXES.items():
            with self.subTest(prefix=name):
                with patch("subprocess.Popen", side_effect=AssertionError("must reject before compiler execution")):
                    result = HdlSimulationManager().handle_request("hdl.start", file_policy_probe(prefix, Path("/not-staged.bin")))
                self.assertFalse(result["ok"])
                self.assertEqual(result["code"], "unsupported_source")
                self.assertEqual(result["outputs"], {})

    def test_unterminated_literals_and_comments_fail_before_tool_launch(self):
        for suffix in ['constant s: string := "unfinished;', 'constant s: string := %unfinished;', '/* unfinished']:
            with self.subTest(suffix=suffix):
                request = design()
                request["source"] += "\n" + suffix
                with patch("subprocess.Popen", side_effect=AssertionError("must reject before compiler execution")):
                    result = HdlSimulationManager().handle_request("hdl.start", request)
                self.assertEqual(result["code"], "unsupported_source")
                self.assertEqual(result["outputs"], {})

    def test_source_size_and_argv_injection_are_rejected_before_execution(self):
        for changes in [{"source": "a" * (MAX_SOURCE_BYTES + 1)}, {"topLevel": "top;touch /tmp/pwned"}, {"parameters": {"WIDTH": "4); report \"bad\";"}}, {"parameters": {"WIDTH": "--load=/tmp/plugin"}}]:
            with self.subTest(changes=list(changes)):
                request = design()
                request.update(changes)
                with patch("subprocess.Popen", side_effect=AssertionError("must not execute")):
                    result = HdlSimulationManager().handle_request("hdl.start", request)
                self.assertFalse(result["ok"])
                self.assertEqual(result["outputs"], {})

    def test_blif_inout_is_rejected(self):
        request = {"backend": "blif", "source": ".model chip\n.inputs a\n.end", "topLevel": "chip",
                   "ports": [{"name": "a", "direction": "inout"}]}
        result = HdlSimulationManager().handle_request("hdl.start", request)
        self.assertEqual(result["code"], "unsupported_port")

    def test_only_blif_accepts_an_index_zero_suffix_on_a_single_bit_port(self):
        request = blif_design("one_bit_indexed")
        self.assertEqual([port.name for port in parse_design(request).ports], ["a[0]", "y[0]"])
        with patch("subprocess.Popen", side_effect=AssertionError("must reject before compiler execution")):
            for ports in (
                [{"name": "a[0]", "direction": "in", "width": 2}],
                [{"name": "a[0]", "direction": "in"}, {"name": "a", "direction": "in"}],
                [{"name": "a[0];invalid", "direction": "in"}],
            ):
                result = HdlSimulationManager().handle_request("hdl.start", {**request, "ports": ports})
                self.assertEqual(result["code"], "invalid_request", result)
            vhdl = design()
            vhdl["ports"][0]["name"] = "a[0]"
            result = HdlSimulationManager().handle_request("hdl.start", vhdl)
            self.assertEqual(result["code"], "invalid_request", result)

    def test_missing_backend_is_unavailable_with_no_outputs(self):
        with patch("shutil.which", return_value=None):
            result = HdlSimulationManager().handle_request("hdl.start", design())
        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "tool_unavailable")
        self.assertEqual(result["outputs"], {})


@unittest.skipUnless(shutil.which("nvc"), "NVC is required for real VHDL integration tests")
class RealVhdlTests(unittest.TestCase):
    def setUp(self):
        self.manager = HdlSimulationManager()

    def tearDown(self):
        self.manager.close()

    def start(self, name="and_gate", **kwargs):
        response = self.manager.handle_request("hdl.start", design(name, **kwargs))
        self.assertTrue(response["ok"], response)
        self.assertEqual(response["outputs"], {})
        return response["sessionId"]

    def step(self, session_id, inputs, advance=1):
        response = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": inputs, "advanceNs": advance})
        self.assertTrue(response["ok"], response)
        return response

    def test_validation_really_analyzes_and_elaborates_without_simulating(self):
        request = design("failing_gate")
        response = self.manager.handle_request("hdl.validate", request)
        self.assertTrue(response["ok"], response)
        self.assertEqual(response["status"], "validated")
        self.assertNotIn("sessionId", response)
        request["source"] = request["source"].replace("y <= a;", "y <= missing_signal;")
        failed = self.manager.handle_request("hdl.validate", request)
        self.assertFalse(failed["ok"])
        self.assertEqual(failed["code"], "analysis_failed")
        self.assertIn("missing_signal", failed["diagnostics"].lower())
        self.assertEqual(failed["outputs"], {})

    def test_and_truth_table_comes_from_real_simulator(self):
        session_id = self.start()
        for a, b, expected in [("0", "0", "0"), ("0", "1", "0"), ("1", "0", "0"), ("1", "1", "1")]:
            response = self.step(session_id, {"a": a, "b": b})
            self.assertEqual(response["outputs"]["y"], {"stdLogic": expected, "logic": expected})
        self.assertEqual(response["timeNs"], 4)
        self.assertEqual(response["step"], 4)

    def test_inout_drives_releases_reads_and_reports_contention(self):
        source = """library ieee;
use ieee.std_logic_1164.all;
entity bidir is port(enable: in std_logic; b: inout std_logic; seen: out std_logic); end;
architecture rtl of bidir is
begin
  b <= '1' when enable = '1' else 'Z';
  seen <= b;
end;
"""
        request = {"source": source, "topLevel": "bidir", "ports": [
            {"name": "enable", "direction": "in"},
            {"name": "b", "direction": "inout"},
            {"name": "seen", "direction": "out"},
        ]}
        started = self.manager.handle_request("hdl.start", request)
        self.assertTrue(started["ok"], started)
        session = started["sessionId"]
        for enable, drive, resolved, contribution in [
            ("1", "Z", "1", "1"),
            ("0", "Z", "Z", "?"),
            ("0", "0", "0", "?"),
            ("1", "0", "X", "E"),
            ("1", "1", "1", "?"),
        ]:
            with self.subTest(enable=enable, drive=drive):
                result = self.step(session, {"enable": enable, "b": drive})
                self.assertEqual(result["outputs"]["b"], {"stdLogic": resolved, "logic": contribution})
                self.assertEqual(result["outputs"]["seen"]["stdLogic"], resolved)
        result = self.manager.handle_request("hdl.step", {"sessionId": session, "inputs": {"enable": "0", "b": "E"}})
        self.assertEqual(result["code"], "unrepresentable_inout_input")
        self.assertEqual(result["outputs"], {})
        self.assertEqual(self.step(session, {"enable": "0", "b": "Z"})["step"], 6)

        request["source"] = source.replace("else 'Z'", "else 'W'")
        started = self.manager.handle_request("hdl.start", request)
        self.assertTrue(started["ok"], started)
        failed = self.manager.handle_request("hdl.step", {
            "sessionId": started["sessionId"], "inputs": {"enable": "0", "b": "Z"},
        })
        self.assertEqual(failed["code"], "unrepresentable_inout_output")
        self.assertEqual(failed["outputs"], {})

    def test_literal_boundaries_keep_characters_strings_comments_and_qualified_expressions(self):
        source = '''library ieee;
use ieee.std_logic_1164.all;
entity literal_probe is port(a: in std_logic; y: out std_logic); end;
architecture rtl of literal_probe is
  constant quote: character := '"';
  constant apostrophe: character := ''' + "'''" + ''';
  constant slash: character := '\\';
  constant doubled: string := "double ""-- file foreign""";
  constant alternate: string := %double "-- file foreign"%;
  function quote_value return character is begin return '"'; end function;
  /* Quotes " and ' do not change the comment boundary: file foreign. */
begin
  process(a) begin
    assert quote = character'('"') severity failure;
    assert quote_value = quote severity failure;
    assert apostrophe = character'(''' + "'''" + ''') severity failure;
    assert doubled'length > 0 and alternate'length > 0 severity failure;
    report "literal ""-- file foreign""";
    if a = '1' then y <= std_logic'('1'); else y <= '0'; end if;
  end process;
end;
'''
        request = {"source": source, "topLevel": "literal_probe", "ports": [{"name": "a", "direction": "in"}, {"name": "y", "direction": "out"}]}
        started = self.manager.handle_request("hdl.start", request)
        self.assertTrue(started["ok"], started)
        for value in ("0", "1"):
            response = self.step(started["sessionId"], {"a": value})
            self.assertEqual(response["outputs"]["y"]["logic"], value)

    def test_real_compiler_file_opens_are_rejected_by_the_lexical_boundary(self):
        # This census uses only an empty file created by the test. Bypassing the
        # policy proves the compiler performs host I/O; the same request must be
        # rejected before launching the compiler under the normal policy.
        with tempfile.TemporaryDirectory(prefix="logisim-vhdl-lexical-") as directory:
            outside = Path(directory) / "a.bin"
            for name, prefix in FILE_MASKING_PREFIXES.items():
                with self.subTest(prefix=name):
                    request = file_policy_probe(prefix, outside)
                    outside.write_bytes(b"")
                    with patch("companion.hdl_sim._source_policy", return_value=None):
                        present = self.manager.handle_request("hdl.start", request)
                        self.assertTrue(present["ok"], present)
                        self.assertEqual(self.step(present["sessionId"], {})["outputs"]["y"]["logic"], "1")
                        self.manager.handle_request("hdl.cancel", {"sessionId": present["sessionId"]})
                        outside.unlink()
                        absent = self.manager.handle_request("hdl.start", request)
                        if absent["ok"]:
                            absent = self.manager.handle_request("hdl.step", {"sessionId": absent["sessionId"], "inputs": {}})
                        self.assertFalse(absent["ok"], absent)
                        self.assertIn(absent["code"], {"elaboration_failed", "simulation_failed"})
                        self.assertIn(str(outside), absent["diagnostics"])
                        self.assertEqual(absent["outputs"], {})
                    with patch("subprocess.Popen", side_effect=AssertionError("host I/O source must not execute")):
                        rejected = self.manager.handle_request("hdl.start", request)
                    self.assertEqual(rejected["code"], "unsupported_source")
                    self.assertEqual(rejected["outputs"], {})

    def test_counter_retains_state_edges_generics_and_reset(self):
        session_id = self.start("counter", parameters={"WIDTH": 4, "INITIAL": 5})
        sequence = [("0", "0", "0101"), ("1", "0", "0110"), ("1", "0", "0110"), ("0", "0", "0110"), ("1", "0", "0111"), ("1", "1", "0101")]
        for clk, reset, expected in sequence:
            response = self.step(session_id, {"clk": clk, "reset": reset})
            self.assertEqual(response["outputs"]["q"]["logic"], expected)
        digest = response["sourceSha256"]
        reset = self.manager.handle_request("hdl.reset", {"sessionId": session_id})
        self.assertEqual(reset["timeNs"], 0)
        self.assertEqual(reset["outputs"], {})
        self.assertEqual(reset["sourceSha256"], digest)
        self.assertEqual(self.step(session_id, {"clk": "0", "reset": "0"})["outputs"]["q"]["logic"], "0101")

    def test_sessions_have_independent_instance_state(self):
        first = self.start("counter")
        second = self.start("counter")
        self.step(first, {"clk": "0", "reset": "0"})
        self.step(first, {"clk": "1", "reset": "0"})
        self.assertEqual(self.step(first, {"clk": "0", "reset": "0"})["outputs"]["q"]["logic"], "0001")
        self.assertEqual(self.step(second, {"clk": "0", "reset": "0"})["outputs"]["q"]["logic"], "0000")

    def test_all_nine_logic_values_roundtrip_without_fictitious_bits(self):
        session_id = self.start("logic_states")
        output = self.step(session_id, {"a": "UX01ZWLH-"})["outputs"]["y"]
        self.assertEqual(output, {"stdLogic": "UX01ZWLH-", "logic": "?E01??01?"})

    def test_delayed_assignment_samples_after_target_time_delta_cycles(self):
        session_id = self.start("delayed_gate")
        self.assertEqual(self.step(session_id, {"a": "1"})["outputs"]["y"]["logic"], "0")
        self.assertEqual(self.step(session_id, {"a": "1"}, advance=2)["outputs"]["y"]["logic"], "1")

    def test_time_generic_in_femtoseconds_sets_the_delay(self):
        source = """library ieee; use ieee.std_logic_1164.all;
entity delayed is generic (DELAY : time := 5 ns); port (a : in std_logic; y : out std_logic := '0'); end delayed;
architecture rtl of delayed is begin y <= a after DELAY; end rtl;
"""
        request = {"source": source, "topLevel": "delayed", "parameters": {"DELAY": "2000000 fs"},
                   "ports": [{"name": "a", "direction": "in"}, {"name": "y", "direction": "out"}]}
        response = self.manager.handle_request("hdl.start", request)
        self.assertTrue(response["ok"], response)
        session_id = response["sessionId"]
        self.assertEqual(self.step(session_id, {"a": "1"})["outputs"]["y"]["logic"], "0")
        self.assertEqual(self.step(session_id, {"a": "1"}, advance=1)["outputs"]["y"]["logic"], "1")

    def test_assertion_failure_has_no_outputs_and_requires_reset(self):
        session_id = self.start("failing_gate")
        self.assertEqual(self.step(session_id, {"a": "0"})["outputs"]["y"]["logic"], "0")
        failed = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "1"}})
        self.assertFalse(failed["ok"])
        self.assertEqual(failed["code"], "simulation_failed")
        self.assertIn("Fixture assertion failure", failed["diagnostics"])
        self.assertEqual(failed["outputs"], {})
        self.assertEqual(len(self.manager.sessions[session_id].steps), 1)
        rejected = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "0"}})
        self.assertEqual(rejected["code"], "session_failed")
        self.manager.handle_request("hdl.reset", {"sessionId": session_id})
        self.assertEqual(self.step(session_id, {"a": "0"})["timeNs"], 1)

    def test_invalid_step_does_not_advance_or_fail_the_session(self):
        session_id = self.start()
        invalid = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "0"}})
        self.assertEqual(invalid["code"], "invalid_request")
        self.assertEqual(self.step(session_id, {"a": "0", "b": "1"})["timeNs"], 1)

    def test_trace_limits_do_not_run_or_invent_outputs(self):
        session_id = self.start()
        self.step(session_id, {"a": "0", "b": "1"}, advance=MAX_TIME_NS)
        result = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "1", "b": "1"}})
        self.assertEqual(result["code"], "trace_limit")
        self.assertEqual(result["outputs"], {})

    def test_cancel_interrupts_real_busy_vhdl_and_discards_session(self):
        session_id = self.start("busy_loop")
        result = []
        run_started = threading.Event()
        original_popen = subprocess.Popen
        def observe_runtime(argv, **kwargs):
            process = original_popen(argv, **kwargs)
            if "-r" in argv:
                run_started.set()
            return process
        worker = threading.Thread(target=lambda: result.append(self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "1"}})))
        with patch("companion.hdl_sim.subprocess.Popen", observe_runtime):
            worker.start()
            self.assertTrue(run_started.wait(timeout=3))
            cancelled = self.manager.handle_request("hdl.cancel", {"sessionId": session_id})
            worker.join(timeout=3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(cancelled["status"], "cancelled")
        self.assertEqual(result[0]["code"], "cancelled")
        self.assertEqual(result[0]["outputs"], {})
        self.assertEqual(self.manager.handle_request("hdl.reset", {"sessionId": session_id})["code"], "unknown_session")

    def test_wall_timeout_interrupts_real_non_yielding_vhdl(self):
        session_id = self.start("busy_loop")
        with patch("companion.hdl_sim.PHASE_TIMEOUT_SECONDS", 0.4):
            response = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "1"}})
        self.assertEqual(response["code"], "timeout", response)
        self.assertEqual(response["outputs"], {})

    def test_diagnostic_limit_terminates_noisy_real_vhdl(self):
        session_id = self.start("noisy_gate")
        response = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "1"}})
        self.assertEqual(response["code"], "output_limit", response)
        self.assertEqual(response["outputs"], {})

    def test_delta_cycle_limit_never_returns_a_partial_sample(self):
        session_id = self.start("delta_loop")
        response = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": {"a": "1"}})
        self.assertFalse(response["ok"])
        self.assertIn(response["code"], {"simulation_failed", "incomplete_sample"})
        self.assertEqual(response["outputs"], {})


def blif_design(name="and_gate"):
    ports = [{"name": "a", "direction": "in"}, {"name": "b", "direction": "in"}, {"name": "y", "direction": "out"}]
    if name == "dff":
        ports = [{"name": "d", "direction": "in"}, {"name": "clk", "direction": "in"}, {"name": "q", "direction": "out"}]
    if name == "desktop_lightswitch":
        ports = [{"name": "button", "direction": "in"}, {"name": "reset", "direction": "in"}, {"name": "light_out", "direction": "out"}]
    if name == "one_bit_indexed":
        ports = [{"name": "a[0]", "direction": "in"}, {"name": "y[0]", "direction": "out"}]
    if name == "and_bus2":
        ports = [{"name": "a", "direction": "in", "width": 2}, {"name": "b", "direction": "in", "width": 2}, {"name": "y", "direction": "out", "width": 2}]
    if name == "desktop_bus2":
        ports = [{"name": signal, "direction": "in", "width": 2} for signal in ("a", "b", "en_a", "en_b")]
        ports.append({"name": "y", "direction": "out", "width": 2})
    return {"backend": "blif", "source": (FIXTURES / f"{name}.blif").read_text(), "topLevel": "lightswitch" if name == "desktop_lightswitch" else name, "ports": ports}


@unittest.skipUnless(all(shutil.which(tool) for tool in ("yosys", "iverilog", "vvp")), "Yosys and Icarus are required for BLIF integration tests")
class RealBlifTests(unittest.TestCase):
    def setUp(self):
        self.manager = HdlSimulationManager()

    def tearDown(self):
        self.manager.close()

    def start(self, name="and_gate"):
        response = self.manager.handle_request("hdl.start", blif_design(name))
        self.assertTrue(response["ok"], response)
        return response["sessionId"]

    def step(self, session_id, inputs):
        response = self.manager.handle_request("hdl.step", {"sessionId": session_id, "inputs": inputs})
        self.assertTrue(response["ok"], response)
        return response

    def test_blif_truth_table_runs_yosys_and_icarus(self):
        session_id = self.start()
        for a, b, expected in [("0", "0", "0"), ("0", "1", "0"), ("1", "0", "0"), ("1", "1", "1")]:
            self.assertEqual(self.step(session_id, {"a": a, "b": b})["outputs"]["y"]["logic"], expected)

    def test_blif_indexed_ports_bind_as_buses_without_reversing_bits(self):
        session_id = self.start("and_bus2")
        for a, b, expected in [("00", "11", "00"), ("01", "11", "01"), ("10", "11", "10"), ("11", "10", "10")]:
            response = self.step(session_id, {"a": a, "b": b})
            self.assertEqual(response["outputs"]["y"]["logic"], expected)

    def test_single_indexed_blif_bit_keeps_its_name_across_companion_samples(self):
        session_id = self.start("one_bit_indexed")
        for input_bit, expected in [("0", "1"), ("1", "0")]:
            response = self.step(session_id, {"a[0]": input_bit})
            self.assertEqual(response["outputs"]["y[0]"]["logic"], expected)

    def test_blif_dff_retains_state_across_replay_and_reset(self):
        session_id = self.start("dff")
        for d, clk, expected in [("1", "0", "0"), ("1", "1", "1"), ("0", "1", "1"), ("0", "0", "1"), ("0", "1", "0")]:
            self.assertEqual(self.step(session_id, {"d": d, "clk": clk})["outputs"]["q"]["logic"], expected)
        self.manager.handle_request("hdl.reset", {"sessionId": session_id})
        response = self.step(session_id, {"d": "0", "clk": "0"})
        self.assertEqual(response["outputs"]["q"]["logic"], "0")
        self.assertEqual(response["timeNs"], 1)

    def test_blif_unknown_does_not_become_an_invented_zero(self):
        session_id = self.start()
        self.assertEqual(self.step(session_id, {"a": "U", "b": "1"})["outputs"]["y"], {"rawLogic": "x", "stdLogic": "U", "logic": "?"})

    def test_desktop_blif_drives_releases_agrees_and_reports_contention_per_bit(self):
        session_id = self.start("desktop_bus2")
        for a, b, en_a, en_b, raw, expected in [
            ("10", "00", "11", "11", "10", "10"),
            ("10", "00", "00", "11", "zz", "??"),
            ("10", "10", "11", "00", "10", "10"),
            ("10", "11", "11", "00", "1x", "1E"),
            ("U0", "11", "11", "00", "1x", "1E"),
            ("00", "10", "UU", "UU", "10", "10"),
        ]:
            with self.subTest(a=a, b=b, en_a=en_a, en_b=en_b):
                inputs = {"a": a, "b": b, "en_a": en_a, "en_b": en_b}
                output = self.step(session_id, inputs)["outputs"]["y"]
                self.assertEqual(output, {"rawLogic": raw, "stdLogic": raw.upper(), "logic": expected})

    def test_desktop_blif_lightswitch_template_toggles_and_resets(self):
        session_id = self.start("desktop_lightswitch")
        for button, reset, expected in [("0", "0", "0"), ("1", "0", "1"), ("0", "0", "1"), ("1", "0", "0"), ("0", "0", "0"), ("1", "0", "1"), ("1", "1", "0")]:
            self.assertEqual(self.step(session_id, {"button": button, "reset": reset})["outputs"]["light_out"]["logic"], expected)

    def test_bad_blif_and_missing_port_fail_without_outputs(self):
        request = blif_design()
        request["source"] = request["source"].replace("11 1", "not_a_truth_table")
        result = self.manager.handle_request("hdl.start", request)
        self.assertEqual(result["code"], "analysis_failed", result)
        self.assertEqual(result["outputs"], {})
        request = blif_design()
        request["ports"][0]["name"] = "absent"
        result = self.manager.handle_request("hdl.start", request)
        self.assertEqual(result["code"], "elaboration_failed", result)
        self.assertEqual(result["outputs"], {})

    def test_blif_include_directive_cannot_read_host_files(self):
        request = blif_design()
        request["source"] += "\n.include /etc/passwd\n"
        result = self.manager.handle_request("hdl.start", request)
        self.assertEqual(result["code"], "unsupported_source")
        self.assertEqual(result["outputs"], {})


if __name__ == "__main__":
    unittest.main()
