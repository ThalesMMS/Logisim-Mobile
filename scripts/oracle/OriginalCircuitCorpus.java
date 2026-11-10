import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Clock;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses the public API in Carl Burch's Logisim 2.7.1 JAR. */
public final class OriginalCircuitCorpus {
    private record Probe(Location point, int width) {}

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(1);
        });
        if (args.length != 2 || !Main.VERSION_NAME.equals("2.7.1")) {
            throw new IllegalArgumentException("Use the original 2.7.1 JAR and pass fixture.circ operations.tsv");
        }
        var loader = new Loader(null) {
            @Override public void showError(String description) {
                throw new IllegalArgumentException(description);
            }
        };
        var file = loader.openLogisimFile(new File(args[0]));
        var project = new Project(file);
        try {
            System.out.println("M\trevision\t" + Main.VERSION_NAME);
            System.out.println("F\tmain\t" + file.getMainCircuit().getName());
            System.out.println("F\tcircuits\t" + file.getCircuits().size());
            for (var circuit : file.getCircuits()) {
                for (var component : circuit.getNonWires()) {
                    String key = circuit.getName() + "\t" + component.getFactory().getName()
                            + "\t" + point(component.getLocation());
                    var ports = component.getEnds().stream()
                            .map(port -> point(port.getLocation()) + ":" + port.getWidth().getWidth() + ":" + port.getType())
                            .sorted().toList();
                    System.out.println("G\t" + key + "\t" + String.join(";", ports));
                    var attrs = component.getAttributeSet();
                    for (var attribute : List.of(StdAttr.LABEL, StdAttr.FACING, Clock.ATTR_HIGH, Clock.ATTR_LOW)) {
                        if (attrs.containsAttribute(attribute)) {
                            var value = attrs.getValue(attribute);
                            if (value != null && !value.toString().isEmpty()) {
                                System.out.println("A\t" + key + "\t" + attribute.getName() + "\t" + value);
                            }
                        }
                    }
                }
                for (var wire : circuit.getWires()) {
                    var ends = List.of(point(wire.getEnd0()), point(wire.getEnd1())).stream().sorted().toList();
                    System.out.println("W\t" + circuit.getName() + "\t" + ends.get(0) + "\t" + ends.get(1) + "\t1");
                }
            }
            observe(new CircuitState(project, file.getMainCircuit()), Files.readAllLines(Path.of(args[1])));
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(0);
    }

    private static void observe(CircuitState state, List<String> operations) {
        Map<String, Probe> probes = new LinkedHashMap<>();
        var propagator = state.getPropagator();
        propagator.propagate();
        String action = "";
        int ticks = 0;
        for (String line : operations) {
            var fields = line.split("\t", -1);
            switch (fields[0]) {
                case "P" -> probes.put(fields[1], new Probe(
                        Location.create(Integer.parseInt(fields[2]), Integer.parseInt(fields[3])),
                        Integer.parseInt(fields[4])));
                case "A" -> { action = fields[1]; ticks = Integer.parseInt(fields[2]); }
                case "I" -> {
                    var pins = state.getCircuit().getNonWires().stream().filter(c -> c.getFactory() instanceof Pin
                            && Pin.FACTORY.isInputPin(Instance.getInstanceFor(c))
                            && fields[1].equals(c.getAttributeSet().getValue(StdAttr.LABEL))).toList();
                    if (pins.size() != 1) throw new IllegalArgumentException("Ambiguous input " + fields[1]);
                    var pin = pins.getFirst();
                    var width = pin.getAttributeSet().getValue(StdAttr.WIDTH);
                    if (width.getWidth() != fields[2].length()) {
                        throw new IllegalArgumentException("Input width " + fields[1]);
                    }
                    Pin.FACTORY.setValue(state.getInstanceState(pin), Value.createKnown(width, Integer.parseUnsignedInt(fields[2], 2)));
                    state.markComponentAsDirty(pin);
                }
                case "F" -> {
                    propagator.propagate();
                    for (int tick = 0; tick < ticks; tick++) {
                        propagator.tick();
                        propagator.propagate();
                    }
                    if (propagator.isOscillating()) throw new IllegalStateException("Oscillation at " + action);
                    if (fields[1].equals("1")) {
                        for (var entry : probes.entrySet()) {
                            var probe = entry.getValue();
                            var value = state.getValue(probe.point());
                            if (value.getWidth() != probe.width()) throw new IllegalStateException("Probe width " + entry.getKey());
                            System.out.println("V\t" + action + "\t" + entry.getKey() + "\t" + bits(value));
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown operation " + fields[0]);
            }
        }
    }

    private static String bits(Value value) {
        var result = new StringBuilder(value.getWidth());
        for (int bit = value.getWidth() - 1; bit >= 0; bit--) {
            var current = value.get(bit);
            result.append(current == Value.FALSE ? '0' : current == Value.TRUE ? '1' : current == Value.ERROR ? 'E' : '?');
        }
        return result.toString();
    }

    private static String point(Location location) {
        return location.getX() + "," + location.getY();
    }
}
