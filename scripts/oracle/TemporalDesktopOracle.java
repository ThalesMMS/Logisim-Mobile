package com.cburch.logisim.circuit;

import com.cburch.logisim.Main;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.Options;
import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Captures observable transitions after each desktop simulator event-time batch. */
public final class TemporalDesktopOracle {
    private record Probe(Location location, int width) {}

    private final CircuitState state;
    private final Propagator propagator;
    private final Field clock;
    private final Map<String, Component> inputs = new LinkedHashMap<>();
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private Map<String, String> lastSnapshot;
    private String action = "initial";
    private int origin;

    private TemporalDesktopOracle(CircuitState state) throws ReflectiveOperationException {
        this.state = state;
        propagator = state.getPropagator();
        // The desktop API exposes tick count, but not the scheduler's event time.
        clock = Propagator.class.getDeclaredField("clock");
        clock.setAccessible(true);
        for (var component : state.getCircuit().getNonWires()) {
            if (component.getFactory() instanceof Pin
                    && Pin.FACTORY.isInputPin(Instance.getInstanceFor(component))) {
                var label = component.getAttributeSet().getValue(StdAttr.LABEL);
                if (label != null && !label.isEmpty() && inputs.put(label, component) != null) {
                    throw new IllegalArgumentException("Duplicate input label: " + label);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: TemporalDesktopOracle fixture.circ operations.tsv");
        }
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        var status = 0;
        try {
            project.getOptions().getAttributeSet().setValue(Options.ATTR_SIM_RAND, 0);
            var state = CircuitState.createRootState(project, file.getMainCircuit(), Thread.currentThread());
            var oracle = new TemporalDesktopOracle(state);
            System.out.println("M\trevision\t" + BuildInfo.branchLastCommitHash);
            oracle.execute(Path.of(args[1]));
        } catch (Exception failure) {
            failure.printStackTrace();
            status = 1;
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(status);
    }

    private void execute(Path operations) throws Exception {
        var lines = Files.readAllLines(operations);
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.isBlank() || line.startsWith("#")) continue;
            var fields = line.split("\t", -1);
            try {
                switch (fields[0]) {
                    case "P" -> {
                        requireFields(fields, 5);
                        var width = Integer.parseInt(fields[4]);
                        if (fields[1].isEmpty() || width < 1 || width > 64) {
                            throw new IllegalArgumentException("Probe needs an ID and width in 1..64");
                        }
                        var probe = new Probe(Location.create(Integer.parseInt(fields[2]),
                                Integer.parseInt(fields[3]), true), width);
                        if (probes.putIfAbsent(fields[1], probe) != null) {
                            throw new IllegalArgumentException("Duplicate probe: " + fields[1]);
                        }
                    }
                    case "A" -> {
                        requireFields(fields, 2);
                        if (fields[1].isEmpty()) throw new IllegalArgumentException("Action needs a name");
                        action = fields[1];
                        origin = time();
                        lastSnapshot = null;
                    }
                    case "I" -> {
                        requireFields(fields, 3);
                        var input = inputs.get(fields[1]);
                        if (input == null) throw new IllegalArgumentException("Unknown input: " + fields[1]);
                        var value = parseBinary(fields[2]);
                        var width = input.getAttributeSet().getValue(StdAttr.WIDTH).getWidth();
                        if (value.getWidth() != width) {
                            throw new IllegalArgumentException("Input " + fields[1] + " needs " + width + " bits");
                        }
                        Pin.FACTORY.driveInputPin(state.getInstanceState(input), value);
                        state.markComponentAsDirty(input);
                    }
                    case "K" -> {
                        requireFields(fields, 1);
                        propagator.toggleClocks();
                    }
                    case "D", "Q" -> {
                        if (fields.length > 2) throw new IllegalArgumentException("Expected D/Q [step limit]");
                        var limit = fields.length == 2 ? Integer.parseInt(fields[1]) : 256;
                        if (limit < 1) throw new IllegalArgumentException("Step limit must be positive");
                        drain(fields[0].equals("D"), limit);
                    }
                    case "S" -> {
                        requireFields(fields, 1);
                        snapshot();
                    }
                    default -> throw new IllegalArgumentException("Unknown operation: " + fields[0]);
                }
            } catch (Exception failure) {
                throw new IllegalArgumentException(operations + ":" + (index + 1) + ": " + line, failure);
            }
        }
    }

    private void drain(boolean emit, int limit) throws IllegalAccessException {
        if (emit) snapshot();
        for (var count = 0; count < limit; count++) {
            // step processes dirty input components before removing every event at the next time.
            if (!propagator.step(null)) return;
            if (emit) snapshot();
            if (!propagator.isPending()) return;
        }
        throw new IllegalStateException("Event queue did not settle within " + limit + " timestamp batches");
    }

    private int time() throws IllegalAccessException {
        return clock.getInt(propagator);
    }

    private void snapshot() throws IllegalAccessException {
        if (probes.isEmpty()) throw new IllegalArgumentException("Define at least one P probe before D/S");
        var values = new LinkedHashMap<String, String>();
        for (var entry : probes.entrySet()) {
            var probe = entry.getValue();
            var value = state.getValue(probe.location());
            if (value.getWidth() == 0) {
                values.put(entry.getKey(), "?".repeat(probe.width()));
            } else {
                if (value.getWidth() != probe.width()) {
                    throw new IllegalArgumentException("Probe " + entry.getKey() + " expected "
                            + probe.width() + " bits, got " + value.getWidth());
                }
                values.put(entry.getKey(), binary(value));
            }
        }
        if (!values.equals(lastSnapshot)) {
            var relativeTime = time() - origin;
            if (relativeTime < 0) throw new IllegalStateException("Scheduler event time overflow");
            for (var entry : values.entrySet()) {
                System.out.println("E\t" + action + "\t" + relativeTime + "\t"
                        + entry.getKey() + "\t" + entry.getValue());
            }
            lastSnapshot = values;
        }
    }

    private static void requireFields(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException(fields[0] + " expects " + (expected - 1) + " arguments");
        }
    }

    private static Value parseBinary(String bits) {
        if (bits.isEmpty() || bits.length() > 64) {
            throw new IllegalArgumentException("Input width must be in 1..64");
        }
        var values = new Value[bits.length()];
        for (var index = 0; index < bits.length(); index++) {
            values[index] = switch (Character.toUpperCase(bits.charAt(bits.length() - index - 1))) {
                case '0' -> Value.FALSE;
                case '1' -> Value.TRUE;
                case '?', 'X', 'U' -> Value.UNKNOWN;
                case 'E' -> Value.ERROR;
                default -> throw new IllegalArgumentException("Invalid binary value: " + bits);
            };
        }
        return Value.create(values);
    }

    private static String binary(Value value) {
        var result = new StringBuilder();
        for (var index = value.getWidth() - 1; index >= 0; index--) {
            var bit = value.get(index);
            result.append(bit.equals(Value.TRUE) ? '1' : bit.equals(Value.FALSE) ? '0'
                    : bit.equals(Value.ERROR) ? 'E' : '?');
        }
        return result.toString();
    }
}
