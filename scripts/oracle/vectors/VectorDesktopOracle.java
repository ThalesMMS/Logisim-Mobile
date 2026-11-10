import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Clock;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;

public final class VectorDesktopOracle {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        var status = 0;
        try {
            var circuit = file.getCircuits().stream().filter(c -> c.getName().equals(args[2])).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing " + args[2] + "; loaded " + file.getCircuits().stream().map(c -> c.getName()).toList()));
            var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
            var evaluator = new TestVectorEvaluator(state, new TestVector(args[1]));
            evaluator.evaluate((row, differences) -> {
                var output = new StringBuilder().append(row).append('\t').append(differences.isEmpty() ? "PASS" : "FAIL");
                for (var pin : evaluator.getPins()) {
                    var instance = state.getInstanceState(pin);
                    var value = pin.getFactory() instanceof Clock ? Clock.FACTORY.getValue(instance) : Pin.FACTORY.getValue(instance);
                    output.append('\t').append(binary(value));
                }
                System.out.println(output);
            });
        } catch (Exception failure) {
            failure.printStackTrace();
            status = 1;
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(status);
    }

    private static String binary(Value value) {
        var output = new StringBuilder();
        for (var index = value.getWidth() - 1; index >= 0; index--) {
            var bit = value.get(index);
            output.append(bit.equals(Value.TRUE) ? '1' : bit.equals(Value.FALSE) ? '0' : bit.equals(Value.ERROR) ? 'E' : '?');
        }
        return output.toString();
    }
}
