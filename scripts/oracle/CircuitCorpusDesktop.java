import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Runs fixture operations on the pinned desktop engine without opening a window. */
public final class CircuitCorpusDesktop {
    record Probe(int x, int y, int width) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: CircuitCorpusDesktop fixture.circ operations.tsv");
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        int status = 0;
        try {
            System.out.println("M\trevision\t" + BuildInfo.branchLastCommitHash);
            System.out.println("F\tcircuits\t" + file.getCircuits().size());
            var circuits = new ArrayList<>(file.getCircuits());
            circuits.sort(Comparator.comparing(c -> c.getName()));
            for (var circuit : circuits) {
                var components = new ArrayList<Component>(circuit.getNonWires());
                components.sort(Comparator.comparingInt((Component c) -> c.getLocation().getY())
                        .thenComparingInt(c -> c.getLocation().getX()).thenComparing(c -> c.getFactory().getName()));
                for (var component : components) {
                    var ports = new ArrayList<String>();
                    for (var port : component.getEnds()) ports.add(port.getLocation().getX() + ","
                            + port.getLocation().getY() + ":" + port.getWidth().getWidth() + ":" + port.getType());
                    ports.sort(String::compareTo);
                    System.out.println("G\t" + circuit.getName() + "\t" + component.getFactory().getName()
                            + "\t" + component.getLocation().getX() + "," + component.getLocation().getY()
                            + "\t" + String.join(";", ports));
                }
            }
            var circuit = file.getMainCircuit();
            var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
            var propagator = state.getPropagator();
            var probes = new TreeMap<String, Probe>();
            String action = "";
            int ticks = 0;
            propagator.propagate();
            for (String line : Files.readAllLines(Path.of(args[1]))) {
                String[] fields = line.split("\t", -1);
                switch (fields[0]) {
                    case "P" -> probes.put(fields[1], new Probe(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4])));
                    case "A" -> { action = fields[1]; ticks = Integer.parseInt(fields[2]); }
                    case "I" -> {
                        var matches = circuit.getNonWires().stream().filter(c -> c.getFactory() instanceof Pin
                                && fields[1].equals(c.getAttributeSet().getValue(StdAttr.LABEL))).toList();
                        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous input " + fields[1]);
                        Pin.FACTORY.driveInputPin(state.getInstanceState(matches.get(0)), parseBits(fields[2]));
                        state.markComponentAsDirty(matches.get(0));
                    }
                    case "F" -> {
                        propagator.propagate();
                        for (int tick = 0; tick < ticks; tick++) { propagator.toggleClocks(); propagator.propagate(); }
                        if (propagator.isOscillating()) throw new IllegalStateException("Oscillation at " + action);
                        if (fields[1].equals("1")) for (var entry : probes.entrySet()) {
                            var probe = entry.getValue();
                            var value = state.getValue(Location.create(probe.x(), probe.y(), true));
                            if (value.getWidth() != probe.width()) throw new IllegalStateException("Probe width " + entry.getKey());
                            System.out.println("V\t" + action + "\t" + entry.getKey() + "\t" + bits(value));
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown operation " + fields[0]);
                }
            }
        } catch (Exception failure) {
            failure.printStackTrace();
            status = 1;
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(status);
    }

    private static Value parseBits(String text) {
        Value[] bits = new Value[text.length()];
        for (int i = 0; i < bits.length; i++) bits[i] = switch (Character.toUpperCase(text.charAt(text.length() - 1 - i))) {
            case '0' -> Value.FALSE;
            case '1' -> Value.TRUE;
            case '?', 'X', 'U' -> Value.UNKNOWN;
            case 'E' -> Value.ERROR;
            default -> throw new IllegalArgumentException("Invalid bit value");
        };
        return Value.create(bits);
    }

    private static String bits(Value value) {
        var out = new StringBuilder();
        for (int bit = value.getWidth() - 1; bit >= 0; bit--) {
            Value v = value.get(bit);
            out.append(v == Value.FALSE ? '0' : v == Value.TRUE ? '1' : v == Value.ERROR ? 'E' : '?');
        }
        return out.toString();
    }
}
