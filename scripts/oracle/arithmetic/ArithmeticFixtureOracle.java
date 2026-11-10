import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.util.Map;

/** Confirms the shared circuit fixture with the desktop's complete elaboration and simulator. */
class ArithmeticFixtureOracle {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        int result = 0;
        try {
            var expected = Map.ofEntries(
                Map.entry("Adder", Map.of("result", 6L, "carryOut", 0L)),
                Map.entry("Subtractor", Map.of("result", 0L, "borrowOut", 0L)),
                Map.entry("Multiplier", Map.of("low", 7L, "high", 0L)),
                Map.entry("Divider", Map.of("quotient", 129L, "remainder", 1L)),
                Map.entry("Negator", Map.of("result", 253L)),
                Map.entry("Exponentiator", Map.of("low", 9L, "high", 0L)),
                Map.entry("SquareRoot", Map.of("root", 22L, "remainder", 31L)),
                Map.entry("Absolute", Map.of("result", 3L, "overflow", 0L)),
                Map.entry("Comparator", Map.of("greater", 1L, "equal", 0L, "less", 0L)),
                Map.entry("MinMax", Map.of("minimum", 2L, "maximum", 3L)),
                Map.entry("Shifter", Map.of("result", 12L)),
                Map.entry("BitAdder", Map.of("count", 3L)),
                Map.entry("BitFinder", Map.of("present", 1L, "index", 0L))
            );
            int compared = 0;
            for (var circuit : file.getCircuits()) {
                var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
                state.getPropagator().propagate();
                for (var component : circuit.getNonWires()) {
                    if (!(component.getFactory() instanceof Pin)) continue;
                    var label = component.getAttributeSet().getValue(StdAttr.LABEL);
                    var wanted = expected.get(circuit.getName()).get(label);
                    var value = Pin.FACTORY.getValue(state.getInstanceState(component));
                    if (wanted == null || !value.isFullyDefined() || value.toLongValue() != wanted)
                        throw new AssertionError(circuit.getName() + "/" + label + " expected=" + wanted + " actual=" + value.toBinaryString());
                    compared++;
                }
            }
            if (compared != 24) throw new AssertionError("Missing outputs: " + compared);
            System.out.println("PASS desktop fixture: 13 circuits, " + compared + " observed outputs");
        } catch (Throwable failure) {
            failure.printStackTrace();
            result = 1;
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(result);
    }
}
