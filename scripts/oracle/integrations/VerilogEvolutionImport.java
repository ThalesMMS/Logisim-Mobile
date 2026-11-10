import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.verilog.VerilogImporter;
import com.cburch.logisim.verilog.YosysRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports Verilog with Evolution's own importer (VerilogImporter + Yosys), runs the given
 * test-vector file on the imported top circuit in Evolution's engine and prints
 * "V\trow\tPASS|FAIL\tvalues..." rows, like HdlImportDesktopOracle does for Mobile's import.
 * Usage: VerilogEvolutionImport workdir top vectors.txt [-DNAME=VALUE] source.v [more sources]
 */
public final class VerilogEvolutionImport {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var work = Path.of(args[0]);
        var sources = new ArrayList<Path>();
        var defines = new ArrayList<String>();
        for (var index = 3; index < args.length; index++) {
            if (args[index].startsWith("-D")) defines.add(args[index].substring(2));
            else sources.add(Path.of(args[index]));
        }
        var options = new YosysRunner.Options("yosys", sources, args[1], List.of(work), defines, work, false, "error",
            YosysRunner.Memories.AUTO);
        var loader = new Loader(null);
        var file = LogisimFile.createEmpty(loader);
        var result = VerilogImporter.synthesize(options, file, VerilogImporter.reservedNames(file), message -> { });
        for (var circuit : result.circuits()) file.addCircuit(circuit);
        var project = new Project(file);
        var status = 1;
        try {
            var top = result.top();
            for (var component : top.getNonWires()) {
                if (component.getFactory() instanceof Pin) {
                    System.out.println("P\t" + component.getAttributeSet().getValue(StdAttr.LABEL));
                }
            }
            var state = CircuitState.createRootState(project, top, Thread.currentThread());
            var evaluator = new TestVectorEvaluator(state, new TestVector(args[2]));
            evaluator.evaluate((row, differences) -> {
                var output = new StringBuilder("V\t").append(row).append('\t').append(differences.isEmpty() ? "PASS" : "FAIL");
                for (var pin : evaluator.getPins()) output.append('\t').append(bits(Pin.FACTORY.getValue(state.getInstanceState(pin))));
                System.out.println(output);
            });
            status = 0;
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(status);
    }

    private static String bits(Value value) {
        var text = new StringBuilder();
        for (var index = value.getWidth() - 1; index >= 0; index--) {
            var bit = value.get(index);
            text.append(bit == Value.TRUE ? '1' : bit == Value.FALSE ? '0' : bit == Value.ERROR ? 'E' : '?');
        }
        return text.toString();
    }
}
