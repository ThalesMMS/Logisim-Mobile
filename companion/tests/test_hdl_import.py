"""Real-tool checks for the companion's synthesized import artifact."""

import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

from companion.api import JobContext, JobFailure, OperationRegistry
from companion.hdl_import import MAX_SOURCE_BYTES, register, synthesize


FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "hdl" / "import"
TOOLS = {name: shutil.which(name) for name in ("yosys", "iverilog", "vvp")}


def preprocessor_file_cases(outside):
    """Census of raw policy spellings and actual Yosys frontend behavior.

    The frontend cases deliberately use only this test's own temporary file.
    Without policy validation, macro suffix/prefix/parameter forms read it.
    A backslash-newline between task fragments is a Yosys syntax error.
    """
    cases = [
        ("direct", "", f'$readmemh("{outside}",words)', "external-file", True),
        ("token-paste", '`define READ(F,M) $readmem``h(F,M)\n',
         f'`READ("{outside}",words)', "unsupported-preprocessor", True),
        ("macro-suffix", '`define SUFFIX h\n`define READ(F,M) $readmem`SUFFIX(F,M)\n',
         f'`READ("{outside}",words)', "unsupported-preprocessor", True),
        ("macro-prefix", '`define PREFIX $readmem\n`define SUFFIX h\n',
         f'`PREFIX`SUFFIX("{outside}",words)', "unsupported-preprocessor", True),
        ("macro-parameter", '`define DOLLAR(D) D\n`define NAME readmemh\n',
         f'`DOLLAR($)`NAME("{outside}",words)', "unsupported-system-function", True),
        ("backslash-newline", '`define READ(F,M) $readmem\\\nh(F,M)\n',
         f'`READ("{outside}",words)', "unsupported-system-function", False),
        ("escaped-identifier", "", f'$readmemh("{outside}",words)', "unsupported-identifier", True),
    ]
    return [(label, prefix + 'module top(output [3:0] q); ' +
             ('wire \\abc//def ; ' if label == "escaped-identifier" else "") + 'reg [3:0] words[0:0]; initial '
             + call + '; assign q=words[0]; endmodule\n', code, compiles)
            for label, prefix, call, code, compiles in cases]


class RecordingContext(JobContext):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.commands = []

    def run(self, argv, **kwargs):
        self.commands.append(list(argv))
        return super().run(argv, **kwargs)


class ImportTestCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="logisim-import-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.inputs = self.root / "uploaded"
        self.inputs.mkdir()
        self.work = self.root / "job"
        self.work.mkdir()

    def context(self, files, *, tools=None, **kwargs):
        paths = {}
        for name, content in files.items():
            path = self.inputs / name
            path.write_bytes(content if isinstance(content, bytes) else content.encode())
            paths[name] = path
        return RecordingContext(self.work, paths, TOOLS if tools is None else tools, threading.Event(), **kwargs)

    def fixture_context(self, *names, **kwargs):
        return self.context({name: (FIXTURES / name).read_bytes() for name in names}, **kwargs)

    def rejected(self, files, parameters, code):
        context = self.context(files)
        with self.assertRaises(JobFailure) as failure:
            synthesize(context, parameters)
        self.assertEqual(failure.exception.code, code, failure.exception.as_dict())
        self.assertEqual(context.commands, [])
        self.assertFalse((self.work / "outputs" / "netlist.json").exists())


class ImportBoundaryTests(ImportTestCase):
    def test_registration_does_not_execute_tools(self):
        registry = OperationRegistry()
        with patch("subprocess.Popen", side_effect=AssertionError("must not execute")):
            register(registry)
        operation = registry.operations["yosys-import"]
        self.assertEqual(operation.required_tools, ("yosys",))

    def test_invalid_options_cannot_become_tool_commands(self):
        for parameters, code in [
            ({"sourceNames": ["source.v"], "top": "top; shell touch marker"}, "invalid-request"),
            ({"sourceNames": ["source.v"], "defines": ["NAME=1;read_verilog /outside"]}, "invalid-request"),
            ({"sourceNames": ["source.v"], "includes": ["/tmp"]}, "external-file"),
            ({"sourceNames": ["source.v"], "memoryPolicy": "external"}, "unsupported-policy"),
            ({"sourceNames": ["source.v"], "undefined": "random"}, "invalid-request"),
        ]:
            with self.subTest(parameters=parameters):
                self.rejected({"source.v": "module top; endmodule"}, parameters, code)

    def test_includes_must_be_declared_literal_relative_paths(self):
        for include in ['"/etc/passwd"', '"../outside.vh"', '"missing.vh"', '`FILE', '"outside.vh" + "suffix"']:
            with self.subTest(include=include):
                self.rejected({"source.v": f"`include {include}\nmodule top; endmodule"}, {"sourceNames": ["source.v"]}, "external-file")

    def test_declared_tree_rejects_invalid_missing_and_conflicting_paths_before_launch(self):
        source = "module top; endmodule"
        for name, code in [
            ("../outside.vh", "invalid-name"),
            ("/outside.vh", "invalid-name"),
            ("inc/../outside.vh", "invalid-name"),
            (r"inc\outside.vh", "invalid-name"),
            ("inc//outside.vh", "invalid-name"),
            ("inputs/hidden.vh", "reserved-name"),
            ("hdl-tree/shadow.vh", "reserved-name"),
            ("top.v/header.vh", "input-name-collision"),
            ("Top.V", "input-name-collision"),
        ]:
            with self.subTest(path=name):
                self.rejected({"upload-0": source, "upload-1": ""}, {
                    "sourceNames": ["top.v"],
                    "inputPaths": {"upload-0": "top.v", "upload-1": name},
                }, code)
        self.rejected({"upload-0": source, "upload-1": ""}, {
            "sourceNames": ["top.v"], "inputPaths": {"upload-0": "top.v"},
        }, "invalid-request")
        self.rejected({"upload-0": '`include "inc/defs.vh"\n' + source, "upload-1": ""}, {
            "sourceNames": ["top.v"], "inputPaths": {"upload-0": "top.v", "upload-1": "other/defs.vh"},
        }, "external-file")

    def test_host_file_and_process_tasks_are_rejected(self):
        for task in ["$system(\"echo test\")", "$fopen(\"outside\",\"w\")", "$fread(data,fd)"]:
            with self.subTest(task=task):
                self.rejected({"source.v": f"module top; initial {task}; endmodule"}, {"sourceNames": ["source.v"]}, "host-access")

    def test_readmem_requires_a_staged_literal_even_across_newlines(self):
        for call in ['$readmemh("/outside.hex",words)', '$readmemh("missing.hex",words)', '$readmemh(filename,words)', '$readmemb(\nfilename,words)']:
            with self.subTest(call=call):
                self.rejected({"source.v": f"module top; reg words[0:0]; initial {call}; endmodule"}, {"sourceNames": ["source.v"]}, "external-file")

    def test_token_pasting_cannot_hide_a_readmem_task(self):
        # A real Yosys run before the fix read this external file and emitted
        # q=4'ha. The lexical boundary must reject it before starting a tool.
        outside = self.root / "outside.hex"
        outside.write_text("a\n")
        source = f'`define READ(F,M) $readmem``h(F,M)\nmodule top(output [3:0] q); reg[3:0] words[0:0]; initial `READ("{outside}",words); assign q=words[0]; endmodule\n'
        self.rejected({"source.v": source}, {"sourceNames": ["source.v"], "top": "top"}, "unsupported-preprocessor")
        self.assertEqual(outside.read_text(), "a\n")

    def test_preprocessor_census_rejects_file_task_fragments_before_launch(self):
        outside = self.root / "outside.hex"
        outside.write_text("a\n")
        for label, source, code, _ in preprocessor_file_cases(outside):
            with self.subTest(case=label):
                self.rejected({"source.v": source}, {"sourceNames": ["source.v"], "top": "top"}, code)
        self.assertEqual(outside.read_text(), "a\n")

    def test_unknown_system_functions_and_standalone_dollars_are_rejected(self):
        for expression in ('$getenv("PATH")', '$random', '$readmem', '$'):
            with self.subTest(expression=expression):
                self.rejected({"source.v": f"module top; initial {expression}; endmodule"},
                              {"sourceNames": ["source.v"]}, "unsupported-system-function")

    def test_escaped_identifiers_are_reported_before_comment_or_quote_markers(self):
        for identifier in (r'\abc//def', r'\abc/*def', r'\abc"def'):
            with self.subTest(identifier=identifier):
                self.rejected({"source.v": f'module top; wire {identifier} ; endmodule'},
                              {"sourceNames": ["source.v"]}, "unsupported-identifier")

    def test_blif_external_include_is_rejected(self):
        self.rejected({"source.blif": ".include outside.blif\n"}, {"sourceNames": ["source.blif"], "format": "blif"}, "external-file")

    def test_reserved_filenames_cannot_replace_the_job_script(self):
        self.rejected({"mobile_import.ys": "module top; endmodule"}, {"sourceNames": ["mobile_import.ys"]}, "reserved-name")

    def test_case_variants_cannot_replace_reserved_names_or_collide(self):
        for name in ("Outputs", "INPUTS", "Mobile_Import.ys", "LOGISIM_BLIF_CELLS.V", "Command-1.STDOUT"):
            with self.subTest(name=name):
                self.rejected({name: "module top; endmodule"}, {"sourceNames": [name]}, "reserved-name")
        self.rejected({"a.v": "module top; endmodule", "A.v": "module top; endmodule"},
                      {"sourceNames": ["a.v"]}, "input-name-collision")

    def test_size_and_utf8_boundaries_fail_before_a_tool_runs(self):
        self.rejected({"large.v": " " * (MAX_SOURCE_BYTES + 1)}, {"sourceNames": ["large.v"]}, "input-limit")
        self.rejected({"invalid.v": b"\xff"}, {"sourceNames": ["invalid.v"]}, "invalid-source")

    def test_cancellation_before_synthesis_produces_no_artifact(self):
        context = self.fixture_context("gates.v")
        context.cancelled.set()
        with self.assertRaises(JobFailure) as failure:
            synthesize(context, {"sourceNames": ["gates.v"], "top": "gates"})
        self.assertEqual(failure.exception.code, "cancelled")
        self.assertEqual(context.commands, [])
        self.assertFalse(context.output_path("netlist.json").exists())


@unittest.skipUnless(all(TOOLS.values()), "Yosys, Icarus and VVP are required for artifact behavior checks")
class RealImportTests(ImportTestCase):
    def test_string_escapes_and_comment_text_remain_usable(self):
        context = self.context({"source.v": r'''module top(output q);
initial $display("line\n quote \" slash \\");
assign q=1'b1; // \ignored//name
endmodule
'''})
        result = synthesize(context, {"sourceNames": ["source.v"], "top": "top"})
        netlist = json.loads(base64.b64decode(result["netlist"]["base64"]))
        self.assertEqual(netlist["modules"]["top"]["ports"]["q"]["bits"], ["1"])

    def test_constant_macros_and_supported_sizing_functions_remain_usable(self):
        context = self.context({"source.v": '''`define WIDTH 8
module top(input [`WIDTH-1:0] a, output [$clog2(`WIDTH)-1:0] q);
assign q=$unsigned(a[2:0]); endmodule
'''})
        result = synthesize(context, {"sourceNames": ["source.v"], "top": "top"})
        netlist = json.loads(base64.b64decode(result["netlist"]["base64"]))
        self.assertEqual(len(netlist["modules"]["top"]["ports"]["a"]["bits"]), 8)
        self.assertEqual(len(netlist["modules"]["top"]["ports"]["q"]["bits"]), 3)
        self.simulate_artifact(context, '''module testbench;reg[7:0] a=8'hfd;wire[2:0] q;top dut(a,q);
initial begin #1;$display("SAMPLE %b",q);$finish;end endmodule''', ["101"])

    def test_preprocessor_census_matches_actual_frontend_behavior(self):
        outside = self.root / "outside.hex"
        outside.write_text("a\n")
        # This bypass is local to the test, to prove why validation must run
        # before the compiler. It reads no file except our controlled fixture.
        for label, source, _, compiles in preprocessor_file_cases(outside):
            with self.subTest(case=label):
                work = self.root / ("census-" + label)
                work.mkdir()
                path = self.inputs / "census.v"
                path.write_text(source)
                context = RecordingContext(work, {"source.v": path}, TOOLS, threading.Event())
                with patch("companion.hdl_import._validate_source", return_value=None):
                    if compiles:
                        result = synthesize(context, {"sourceNames": ["source.v"], "top": "top"})
                        netlist = json.loads(base64.b64decode(result["netlist"]["base64"]))
                        self.assertEqual(netlist["modules"]["top"]["ports"]["q"]["bits"], ["0", "1", "0", "1"])
                    else:
                        with self.assertRaises(JobFailure) as failure:
                            synthesize(context, {"sourceNames": ["source.v"], "top": "top"})
                        self.assertEqual(failure.exception.code, "synthesis-failed")
        self.assertEqual(outside.read_text(), "a\n")

    def import_design(self, files, top, **options):
        context = self.fixture_context(*files)
        parameters = {"sourceNames": [name for name in files if name.endswith((".v", ".sv", ".blif"))], "top": top, **options}
        result = synthesize(context, parameters)
        artifact = result["netlist"]
        content = base64.b64decode(artifact["base64"], validate=True)
        self.assertEqual(artifact["sha256"], hashlib.sha256(content).hexdigest())
        self.assertEqual(artifact["size"], len(content))
        self.assertEqual(content, context.output_path("netlist.json").read_bytes())
        self.assertEqual(result["format"], "yosys-bit-netlist-v1")
        self.assertEqual(result["memoryPolicy"], options.get("memoryPolicy", "logic"))
        self.assertEqual(result["execution"], "explicit-companion-job")
        for name in files:
            self.assertEqual((self.work / name).read_bytes(), (FIXTURES / name).read_bytes())
        return context, json.loads(content), result

    def simulate_artifact(self, context, testbench, expected):
        # Execute the returned JSON artifact, not the original HDL. This proves
        # the artifact carries behavior through synthesis and serialization.
        recovered = context.run([context.tool("yosys"), "-Q", "-T", "-p", 'read_json outputs/netlist.json; write_verilog -noattr outputs/recovered.v'])
        self.assertEqual(recovered.returncode, 0, recovered.stderr)
        (self.work / "testbench.v").write_text(testbench)
        cell_sources = []
        if "\\$_" in context.output_path("recovered.v").read_text():
            # write_verilog retains internal tri-state cells as modules. Use
            # the simulator models shipped with this same Yosys installation.
            simcells = Path(context.tool("yosys")).resolve().parents[1] / "share" / "yosys" / "simcells.v"
            self.assertTrue(simcells.is_file(), f"Yosys simulator models not found: {simcells}")
            shutil.copyfile(simcells, self.work / "yosys_simcells.v")
            cell_sources.append("yosys_simcells.v")
        compiled = context.run([context.tool("iverilog"), "-g2012", "-s", "testbench", "-o", "test.vvp", "outputs/recovered.v", "testbench.v", *cell_sources])
        self.assertEqual(compiled.returncode, 0, compiled.stderr)
        simulated = context.run([context.tool("vvp"), "test.vvp"])
        self.assertEqual(simulated.returncode, 0, simulated.stderr)
        self.assertEqual([line.removeprefix("SAMPLE ") for line in simulated.stdout.splitlines() if line.startswith("SAMPLE ")], expected)

    def test_native_policy_keeps_compatible_memory_and_expands_other_memory(self):
        source = """module top(input clk, we, input [1:0] a,b, input [3:0] d, output [3:0] q,r);
        reg [3:0] same [0:3], separate [0:3];
        always @(posedge clk) if (we) begin same[a] <= d; separate[b] <= d; end
        assign q = same[a]; assign r = separate[a];
        endmodule"""
        context = self.context({"memory.v": source})
        result = synthesize(context, {"sourceNames": ["memory.v"], "memoryPolicy": "native"})
        self.assertEqual(result["memoryPolicy"], "native")
        cells = json.loads(context.output_path("netlist.json").read_bytes())["modules"]["top"]["cells"]
        self.assertEqual({name for name, cell in cells.items() if cell["type"] == "$mem_v2"}, {"same"})
        self.assertTrue(any(cell["type"] == "$_DFF_P_" for cell in cells.values()))
        self.assertEqual(cells["same"]["connections"]["RD_ADDR"], cells["same"]["connections"]["WR_ADDR"])

    def test_combinational_gates_artifact_has_literal_truth_table(self):
        context, netlist, _ = self.import_design(["gates.v"], "gates")
        self.assertEqual(set(netlist["modules"]["gates"]["ports"]), {"a", "b", "select", "and_y", "xor_y", "mux_y"})
        self.simulate_artifact(context, '''module testbench;
reg[1:0] a,b; reg select; wire[1:0] and_y,xor_y,mux_y;
gates dut(a,b,select,and_y,xor_y,mux_y);
initial begin
a=0;b=0;select=0;#1;$display("SAMPLE %b",{and_y,xor_y,mux_y});
a=2;b=1;select=0;#1;$display("SAMPLE %b",{and_y,xor_y,mux_y});
a=2;b=1;select=1;#1;$display("SAMPLE %b",{and_y,xor_y,mux_y});
a=3;b=2;select=1;#1;$display("SAMPLE %b",{and_y,xor_y,mux_y});
$finish;end endmodule''', ["000000", "001110", "001101", "100110"])

    def test_hierarchy_remains_in_the_json_and_simulates(self):
        context, netlist, _ = self.import_design(["hierarchy.v"], "hierarchy")
        self.assertEqual(set(netlist["modules"]), {"hierarchy", "leaf"})
        self.assertEqual(netlist["modules"]["hierarchy"]["cells"]["nested"]["type"], "leaf")
        self.simulate_artifact(context, '''module testbench; reg a,b;wire y; hierarchy dut(a,b,y);
initial begin a=0;b=0;#1;$display("SAMPLE %b",y);a=1;#1;$display("SAMPLE %b",y);b=1;#1;$display("SAMPLE %b",y);$finish;end endmodule''', ["0", "1", "0"])

    def test_hierarchical_bus2_and_counter_matches_native_comparison_fixture(self):
        context, netlist, _ = self.import_design(["hierarchy_counter.v"], "hierarchy_counter")
        self.assertEqual(set(netlist["modules"]), {"and_bus2", "hierarchy_counter"})
        self.assertEqual(len(netlist["modules"]["and_bus2"]["ports"]["a"]["bits"]), 2)
        self.simulate_artifact(context, '''module testbench; reg clk=0,reset=0;reg[1:0]a=2,b=3;wire[1:0]and_y,count;
hierarchy_counter dut(clk,reset,a,b,and_y,count);
initial begin #1;$display("SAMPLE %b",{and_y,count});clk=1;#1;$display("SAMPLE %b",{and_y,count});clk=0;#1;clk=1;#1;$display("SAMPLE %b",{and_y,count});clk=0;#1;clk=1;#1;$display("SAMPLE %b",{and_y,count});clk=0;#1;clk=1;#1;$display("SAMPLE %b",{and_y,count});clk=0;reset=1;a=1;b=3;#1;clk=1;#1;$display("SAMPLE %b",{and_y,count});$finish;end endmodule''', ["1000", "1001", "1010", "1011", "1000", "0100"])

    def test_dff_retains_init_edges_and_reset_after_json_roundtrip(self):
        context, netlist, _ = self.import_design(["dff.v"], "dff")
        self.assertTrue(any(cell["type"].startswith("$_DFF_") for cell in netlist["modules"]["dff"]["cells"].values()))
        self.simulate_artifact(context, '''module testbench; reg clk=0,reset=0,d=0;wire q;dff dut(clk,reset,d,q);
initial begin #1;$display("SAMPLE %b",q);d=1;#1;$display("SAMPLE %b",q);clk=1;#1;$display("SAMPLE %b",q);d=0;#1;$display("SAMPLE %b",q);clk=0;reset=1;#1;clk=1;#1;$display("SAMPLE %b",q);$finish;end endmodule''', ["0", "0", "1", "1", "0"])

    def test_readmem_memory_has_same_contents_and_writes_with_both_policies(self):
        for policy in ("logic", "native"):
            with self.subTest(policy=policy):
                context, netlist, _ = self.import_design(["memory.v", "words.hex"], "memory", memoryPolicy=policy)
                cells = netlist["modules"]["memory"]["cells"].values()
                self.assertEqual(any(cell["type"] == "$mem_v2" for cell in cells), policy == "native")
                self.simulate_artifact(context, '''module testbench;reg clk=0,write_enable=0;reg[1:0]address=0;reg[3:0]data=0;wire[3:0]q;memory dut(clk,write_enable,address,data,q);
        initial begin #1;$display("SAMPLE %h",q);address=1;#1;$display("SAMPLE %h",q);address=2;#1;$display("SAMPLE %h",q);address=3;#1;$display("SAMPLE %h",q);address=1;data=3;write_enable=1;#1;clk=1;#1;$display("SAMPLE %h",q);clk=0;write_enable=0;address=2;#1;$display("SAMPLE %h",q);address=1;#1;$display("SAMPLE %h",q);$finish;end endmodule''', ["1", "a", "5", "f", "3", "5", "3"])

    def test_staged_include_and_macro_override_change_real_bit_width_and_logic(self):
        context, netlist, _ = self.import_design(["macros.v", "constants.vh"], "macros", defines=["WIDTH=3", "MASK=3'b101"], includes=["."])
        self.assertEqual(len(netlist["modules"]["macros"]["ports"]["a"]["bits"]), 3)
        self.simulate_artifact(context, '''module testbench;reg[2:0]a;wire[2:0]y;macros dut(a,y);initial begin a=0;#1;$display("SAMPLE %b",y);a=7;#1;$display("SAMPLE %b",y);$finish;end endmodule''', ["101", "010"])

    def test_nested_source_tree_keeps_relative_includes_and_duplicate_basenames(self):
        context = self.context({
            "upload-0": '`include "inc/defs.vh"\nmodule top(output [3:0] q); assign q=`VALUE; endmodule',
            "upload-1": '`include "nested/constants.vh"\n',
            "upload-2": "`define VALUE 4'hA\n",
            "upload-3": "`define VALUE 4'hF\n",
        })
        result = synthesize(context, {
            "sourceNames": ["rtl/top.v"], "top": "top",
            "inputPaths": {
                "upload-0": "rtl/top.v", "upload-1": "rtl/inc/defs.vh",
                "upload-2": "rtl/inc/nested/constants.vh", "upload-3": "unrelated/constants.vh",
            },
        })
        self.assertEqual(result["sourceNames"], ["rtl/top.v"])
        self.simulate_artifact(context, """module testbench; wire[3:0] q; top dut(q);
initial begin #1;$display("SAMPLE %h",q);$finish;end endmodule""", ["a"])

    def test_blif_truth_table_is_a_real_import_artifact(self):
        context, _, _ = self.import_design(["and_gate.blif"], "and_gate", format="blif")
        self.simulate_artifact(context, '''module testbench;reg a,b;wire y;and_gate dut(a,b,y);initial begin a=0;b=1;#1;$display("SAMPLE %b",y);a=1;#1;$display("SAMPLE %b",y);$finish;end endmodule''', ["0", "1"])

    def test_blif_dff_init_and_edges_survive_json_roundtrip(self):
        context, _, _ = self.import_design(["dff.blif"], "dff", format="blif")
        self.simulate_artifact(context, '''module testbench;reg d=0,clk=0;wire q;dff dut(.d(d),.clk(clk),.q(q));initial begin #1;$display("SAMPLE %b",q);d=1;#1;$display("SAMPLE %b",q);clk=1;#1;$display("SAMPLE %b",q);d=0;#1;$display("SAMPLE %b",q);clk=0;#1;clk=1;#1;$display("SAMPLE %b",q);$finish;end endmodule''', ["0", "0", "1", "1", "0"])

    def test_desktop_trisi_enables_noninverted_input_when_control_is_low(self):
        context, _, _ = self.import_design(["desktop_trisi.blif"], "desktop_trisi", format="blif")
        self.simulate_artifact(context, '''module testbench;reg a=0,b=0;wire y;desktop_trisi dut(a,b,y);initial begin #1;$display("SAMPLE %b",y);a=1;#1;$display("SAMPLE %b",y);b=1;#1;$display("SAMPLE %b",y);a=0;#1;$display("SAMPLE %b",y);$finish;end endmodule''', ["0", "1", "z", "z"])

    def test_desktop_bus_conflicting_drivers_are_rejected_instead_of_ignoring_b(self):
        context = self.fixture_context("desktop_bus.blif")
        with self.assertRaises(JobFailure) as failure:
            synthesize(context, {"sourceNames": ["desktop_bus.blif"], "top": "desktop_bus", "format": "blif"})
        self.assertEqual(failure.exception.code, "synthesis-failed")
        self.assertIn("conflicting drivers", failure.exception.details["log"].lower())
        self.assertFalse(context.output_path("netlist.json").exists())

    def test_bad_source_and_missing_top_raise_real_synthesis_failures(self):
        for source, top in [("module broken( ; endmodule", "broken"), ("module actual; endmodule", "missing")]:
            with self.subTest(source=source):
                context = self.context({"bad.v": source})
                with self.assertRaises(JobFailure) as failure:
                    synthesize(context, {"sourceNames": ["bad.v"], "top": top})
                self.assertEqual(failure.exception.code, "synthesis-failed")
                self.assertNotEqual(failure.exception.details["returncode"], 0)
                self.assertTrue(failure.exception.details["log"])
                self.assertFalse(context.output_path("netlist.json").exists())

    def test_cancellation_interrupts_a_started_real_yosys_process(self):
        context = self.fixture_context("memory.v", "words.hex")
        original_popen = subprocess.Popen
        started = []
        def launch_then_cancel(argv, **kwargs):
            process = original_popen(argv, **kwargs)
            started.append(process)
            context.cancelled.set()
            return process
        with patch("companion.api.subprocess.Popen", launch_then_cancel):
            with self.assertRaises(JobFailure) as failure:
                synthesize(context, {"sourceNames": ["memory.v"], "top": "memory"})
        self.assertEqual(failure.exception.code, "cancelled")
        self.assertEqual(len(started), 1)
        self.assertIsNotNone(started[0].poll())
        self.assertFalse(context.output_path("netlist.json").exists())


if __name__ == "__main__":
    unittest.main()
