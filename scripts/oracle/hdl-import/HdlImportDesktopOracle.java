import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Loads the imported .circ in the original desktop engine; stdout is canonical TSV. */
public final class HdlImportDesktopOracle {
    public static void main(String[] args) {
        Main.headless = true;
        Project project = null;
        int status = 1;
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException("Usage: HdlImportDesktopOracle fixture.circ circuitName vectors.tsv");
            }
            var file = new Loader(null).openLogisimFile(new File(args[0]));
            project = new Project(file);
            var circuitRows = new ArrayList<String>();
            var pinRows = new ArrayList<String>();
            var hierarchyRows = new ArrayList<String>();
            var gateRows = new ArrayList<String>();
            for (var circuit : file.getCircuits()) {
                String circuitName = field(circuit.getName());
                circuitRows.add("C\t" + circuitName);
                for (var component : circuit.getNonWires()) {
                    var factory = component.getFactory();
                    var attributes = component.getAttributeSet();
                    String point = component.getLocation().getX() + "\t" + component.getLocation().getY();
                    if (factory instanceof Pin) {
                        var instance = Instance.getInstanceFor(component);
                        String direction = Pin.FACTORY.isInputPin(instance) ? "INPUT" : "OUTPUT";
                        pinRows.add("P\t" + circuitName + "\t" + field(attributes.getValue(StdAttr.LABEL))
                                + "\t" + direction + "\t" + Pin.FACTORY.getWidth(instance).getWidth()
                                + "\t" + point + "\t" + attribute(attributes, "facing"));
                    } else if (factory instanceof SubcircuitFactory subcircuit) {
                        hierarchyRows.add("H\t" + circuitName + "\t" + field(subcircuit.getSubcircuit().getName())
                                + "\t" + point);
                    } else if (factory.getName().endsWith(" Gate")) {
                        gateRows.add("G\t" + circuitName + "\t" + field(factory.getName()) + "\t" + point
                                + "\t" + attribute(attributes, "width") + "\t" + attribute(attributes, "inputs")
                                + "\t" + attribute(attributes, "size") + "\t" + attribute(attributes, "facing"));
                    }
                }
            }
            for (var rows : List.of(circuitRows, pinRows, hierarchyRows, gateRows)) {
                rows.sort(String::compareTo);
                rows.forEach(System.out::println);
            }
            var circuit = file.getCircuits().stream().filter(candidate -> candidate.getName().equals(args[1]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing circuit: " + args[1]));
            var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
            var evaluator = new TestVectorEvaluator(state, new TestVector(args[2]));
            var expectedPins = new HashSet<>(circuit.getNonWires().stream()
                    .filter(component -> component.getFactory() instanceof Pin).toList());
            var vectorPins = new HashSet<com.cburch.logisim.comp.Component>();
            boolean sawOutput = false;
            for (var pin : evaluator.getPins()) {
                if (!(pin.getFactory() instanceof Pin) || !vectorPins.add(pin.getComponent())) {
                    throw new IllegalArgumentException("Fixture vectors contain a non-pin or duplicate pin column");
                }
                boolean input = Pin.FACTORY.isInputPin(pin);
                if (input && sawOutput) {
                    throw new IllegalArgumentException("Fixture vectors must place every input before outputs");
                }
                sawOutput |= !input;
            }
            if (!expectedPins.equals(vectorPins)) {
                throw new IllegalArgumentException("Fixture vectors must cover every top-level pin");
            }
            evaluator.evaluate((row, differences) -> {
                StringBuilder output = new StringBuilder("V\t").append(row).append('\t')
                        .append(differences.isEmpty() ? "PASS" : "FAIL");
                for (var pin : evaluator.getPins()) {
                    if (!(pin.getFactory() instanceof Pin)) {
                        throw new IllegalArgumentException("Fixture vector includes a non-pin column");
                    }
                    output.append('\t').append(bits(Pin.FACTORY.getValue(state.getInstanceState(pin))));
                }
                System.out.println(output);
            });
            status = 0;
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
        } finally {
            if (project != null) project.getSimulator().shutDown();
        }
        System.exit(status);
    }

    private static String attribute(AttributeSet attributes, String name) {
        var attribute = attributes.getAttribute(name);
        return attribute == null ? "-" : standard(attribute, attributes);
    }

    private static <T> String standard(Attribute<T> attribute, AttributeSet attributes) {
        T value = attributes.getValue(attribute);
        return value == null ? "-" : field(attribute.toStandardString(value));
    }

    private static String field(String value) {
        if (value == null || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Fixture metadata is not a single TSV field");
        }
        return value;
    }

    private static String bits(Value value) {
        var output = new StringBuilder();
        for (int bit = value.getWidth() - 1; bit >= 0; bit--) {
            Value item = value.get(bit);
            output.append(item.equals(Value.FALSE) ? '0' : item.equals(Value.TRUE) ? '1'
                    : item.equals(Value.ERROR) ? 'E' : '?');
        }
        return output.toString();
    }
}
