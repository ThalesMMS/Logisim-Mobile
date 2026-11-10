import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.verilog.VerilogImporter;
import com.cburch.logisim.verilog.YosysRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Project > Import Verilog: Evolution's VerilogImportDialog runs VerilogImporter.synthesize with Yosys and
 * commits one project action. This runs that importer on the sources of each Verilog case recorded by
 * scripts/oracle/hdl-import/run.py, and compares it with the Mobile import of the same sources: both results
 * are evaluated by Evolution's TestVectorEvaluator against the Icarus vectors, and the top pins must match.
 *
 * Arguments: YOSYS RUN_PY_OUTPUT case:top[:DEFINE...]...
 */
public final class VerilogImportOracle {
    static String pins(com.cburch.logisim.circuit.Circuit circuit) {
        var set = new TreeSet<String>();
        for (var comp : circuit.getNonWires()) {
            if (comp.getFactory() instanceof Pin) {
                var attrs = comp.getAttributeSet();
                set.add(attrs.getValue(com.cburch.logisim.instance.StdAttr.LABEL) + ":"
                    + (Pin.FACTORY.isInputPin((com.cburch.logisim.instance.Instance) com.cburch.logisim.instance.Instance.getInstanceFor(comp)) ? "in" : "out")
                    + ":" + attrs.getValue(com.cburch.logisim.instance.StdAttr.WIDTH).getWidth());
            }
        }
        return String.join(",", set);
    }

    static String evaluate(Project proj, com.cburch.logisim.circuit.Circuit top, Path vectors) throws Exception {
        var state = CircuitState.createRootState(proj, top, Thread.currentThread());
        var vector = new TestVector(vectors.toString());
        var rows = new int[2];
        new TestVectorEvaluator(state, vector).evaluate((row, report) -> rows[report == null || report.isEmpty() ? 0 : 1]++);
        return rows[0] + "/" + rows[1];
    }

    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var yosys = args[0];
        var root = Path.of(args[1]);
        int failed = 0;
        for (int i = 2; i < args.length; i++) {
            var parts = args[i].split(":");
            var name = parts[0];
            var top = parts[1];
            var defines = List.of(parts).subList(2, parts.length);
            var job = root.resolve(name).resolve("job");
            var sources = new ArrayList<Path>();
            try (var files = Files.list(job)) {
                for (var file : files.sorted().toList()) if (file.toString().endsWith(".v")) sources.add(file.toAbsolutePath());
            }
            var work = Files.createTempDirectory("menus-verilog-" + name);
            var options = new YosysRunner.Options(yosys, sources, top, List.of(job.toAbsolutePath()), defines, work, false, "error",
                YosysRunner.Memories.AUTO);
            var destination = LogisimFile.createNew(new Loader(null), null);
            var proj = new Project(destination);
            var result = VerilogImporter.synthesize(options, destination, VerilogImporter.reservedNames(destination), message -> { });
            proj.doAction(result.action("import"));
            for (var circ : destination.getCircuits()) circ.setProject(proj);
            var evolution = evaluate(proj, result.top(), root.resolve(name).resolve("vectors.txt"));

            var mobileFile = new Loader(null).openLogisimFile(root.resolve(name).resolve("imported.circ").toFile());
            var mobileProject = new Project(mobileFile);
            for (var circ : mobileFile.getCircuits()) circ.setProject(mobileProject);
            var mobileTop = mobileFile.getCircuits().stream().filter(c -> c.getName().equalsIgnoreCase("hdl_" + top) || c.getName().equalsIgnoreCase(top))
                .findFirst().orElse(mobileFile.getMainCircuit());
            var mobile = evaluate(mobileProject, mobileTop, root.resolve(name).resolve("vectors.txt"));
            var same = evolution.endsWith("/0") && mobile.endsWith("/0") && pins(result.top()).equals(pins(mobileTop));
            if (!same) failed++;
            System.out.println((same ? "SAME " : "DIFF ") + name + " evolution(pass/fail)=" + evolution + " mobile=" + mobile
                + " evolutionPins=" + pins(result.top()) + " mobilePins=" + pins(mobileTop)
                + " evolutionCircuits=" + result.circuits().size() + " mobileCircuits=" + mobileFile.getCircuits().size());
            proj.getSimulator().shutDown();
            mobileProject.getSimulator().shutDown();
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
