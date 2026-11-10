import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import dev.logisim.mobile.core.ArithmeticLogic;
import dev.logisim.mobile.core.ArithmeticOperation;
import dev.logisim.mobile.core.ArithmeticComponents;
import dev.logisim.mobile.core.ComponentRegistry;
import dev.logisim.mobile.core.ComponentKind;
import dev.logisim.mobile.core.GridPoint;
import dev.logisim.mobile.core.PortDirection;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;

/** Calls the desktop components' real propagate methods, including their attribute defaults. */
class ArithmeticOracle {
    private static final Random RANDOM = new Random(320064L);
    private static int comparisons;
    private static int layouts;
    private static final Set<String> CHECKED_LAYOUTS = new HashSet<>();
    private static final ComponentRegistry REGISTRY = new ComponentRegistry(ArithmeticComponents.INSTANCE.definitions());
    private static final Map<ArithmeticOperation, String> CLASSES = Map.ofEntries(
        Map.entry(ArithmeticOperation.ADDER, "Adder"), Map.entry(ArithmeticOperation.SUBTRACTOR, "Subtractor"),
        Map.entry(ArithmeticOperation.MULTIPLIER, "Multiplier"), Map.entry(ArithmeticOperation.DIVIDER, "Divider"),
        Map.entry(ArithmeticOperation.NEGATOR, "Negator"), Map.entry(ArithmeticOperation.EXPONENTIATOR, "Exponentiator"),
        Map.entry(ArithmeticOperation.SQUARE_ROOT, "SquareRoot"), Map.entry(ArithmeticOperation.ABSOLUTE, "Absolute"),
        Map.entry(ArithmeticOperation.COMPARATOR, "Comparator"), Map.entry(ArithmeticOperation.MIN_MAX, "MinMax"),
        Map.entry(ArithmeticOperation.SHIFTER, "Shifter"), Map.entry(ArithmeticOperation.BIT_ADDER, "BitAdder"),
        Map.entry(ArithmeticOperation.BIT_FINDER, "BitFinder")
    );

    private static Value desktop(LogicVector value) {
        Value[] bits = new Value[value.getWidth()];
        for (int i = 0; i < bits.length; i++) bits[i] = switch (value.bit(i)) {
            case LOW -> Value.FALSE;
            case HIGH -> Value.TRUE;
            case UNKNOWN -> Value.UNKNOWN;
            case ERROR -> Value.ERROR;
        };
        return Value.create(bits);
    }

    private static LogicVector number(int width, long number) {
        LogicValue[] bits = new LogicValue[width];
        for (int i = 0; i < width; i++) bits[i] = (number & (1L << i)) == 0 ? LogicValue.LOW : LogicValue.HIGH;
        return LogicVector.Companion.fromBits(Arrays.asList(bits));
    }

    private static LogicVector random(int width, boolean partial) {
        LogicValue[] bits = new LogicValue[width];
        for (int i = 0; i < width; i++) bits[i] = LogicValue.values()[RANDOM.nextInt(partial ? 4 : 2)];
        return LogicVector.Companion.fromBits(Arrays.asList(bits));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void attribute(AttributeSet attributes, String name, String value) {
        for (Attribute a : attributes.getAttributes()) {
            if (a.getName().equals(name)) {
                attributes.setValue(a, a.parse(value));
                return;
            }
        }
        throw new AssertionError("Missing desktop attribute " + name);
    }

    private static int[] inputPorts(ArithmeticOperation operation, int count) {
        return switch (operation) {
            case ADDER, SUBTRACTOR, MULTIPLIER, DIVIDER -> new int[] {0, 1, 3};
            case NEGATOR, ABSOLUTE -> new int[] {0};
            case SQUARE_ROOT -> new int[] {0, 2};
            case BIT_FINDER -> new int[] {2};
            case BIT_ADDER -> { int[] ports = new int[count]; for (int i = 0; i < count; i++) ports[i] = i + 1; yield ports; }
            default -> new int[] {0, 1};
        };
    }

    private static int[] outputPorts(ArithmeticOperation operation) {
        return switch (operation) {
            case ADDER, SUBTRACTOR, MULTIPLIER, DIVIDER -> new int[] {2, 4};
            case NEGATOR -> new int[] {1};
            case EXPONENTIATOR -> new int[] {2, 3};
            case SQUARE_ROOT -> new int[] {1, 3};
            case ABSOLUTE -> new int[] {1, 2};
            case COMPARATOR -> new int[] {2, 3, 4};
            case MIN_MAX -> new int[] {2, 3};
            case SHIFTER -> new int[] {2};
            case BIT_ADDER -> new int[] {0};
            case BIT_FINDER -> new int[] {0, 1};
        };
    }

    private static void check(ArithmeticOperation operation, int width, List<LogicVector> inputs,
                              String mode, String shift, String finder) throws Exception {
        InstanceFactory factory = (InstanceFactory) Class.forName("com.cburch.logisim.std.arith." + CLASSES.get(operation))
            .getConstructor().newInstance();
        AttributeSet attributes = factory.createAttributeSet();
        attribute(attributes, "width", Integer.toString(width));
        if (operation == ArithmeticOperation.MULTIPLIER || operation == ArithmeticOperation.DIVIDER ||
            operation == ArithmeticOperation.EXPONENTIATOR || operation == ArithmeticOperation.COMPARATOR ||
            operation == ArithmeticOperation.MIN_MAX) attribute(attributes, "mode", mode);
        if (operation == ArithmeticOperation.SHIFTER) attribute(attributes, "shift", shift);
        if (operation == ArithmeticOperation.BIT_FINDER) attribute(attributes, "type", finder);
        if (operation == ArithmeticOperation.BIT_ADDER) attribute(attributes, "inputs", Integer.toString(inputs.size()));
        if (CHECKED_LAYOUTS.add(operation + ":" + width + ":" + inputs.size())) {
            var component = REGISTRY.create(new ComponentKind(operation.name()), "subject", new GridPoint(200, 100),
                Map.of("width", Integer.toString(width), "mode", mode, "shift", shift, "type", finder,
                       "inputs", Integer.toString(inputs.size())), "");
            var layout = REGISTRY.layout(component);
            var desktopComponent = factory.createComponent(Location.create(200, 100, true), attributes);
            var ends = desktopComponent.getEnds();
            if (layout.getPorts().size() != ends.size()) throw new AssertionError(operation + " terminal count");
            for (int i = 0; i < ends.size(); i++) {
                var expected = ends.get(i);
                var actual = layout.getPorts().get(i);
                int direction = actual.getDirection() == PortDirection.INPUT ? EndData.INPUT_ONLY : EndData.OUTPUT_ONLY;
                if (expected.getLocation().getX() != actual.getPosition().getX() ||
                    expected.getLocation().getY() != actual.getPosition().getY() ||
                    expected.getWidth().getWidth() != actual.getWidth() || expected.getType() != direction)
                    throw new AssertionError(operation + " width=" + width + " terminal=" + i + " desktop=" + expected + " mobile=" + actual);
            }
            var bounds = desktopComponent.getBounds();
            var actualBounds = layout.getBounds();
            if (bounds.getX() != actualBounds.getLeft() || bounds.getY() != actualBounds.getTop() ||
                bounds.getX() + bounds.getWidth() != actualBounds.getRight() ||
                bounds.getY() + bounds.getHeight() != actualBounds.getBottom())
                throw new AssertionError(operation + " bounds desktop=" + bounds + " mobile=" + actualBounds);
            layouts++;
        }
        int[] ports = inputPorts(operation, inputs.size());
        Map<Integer, Value> stimuli = new HashMap<>();
        Map<Integer, Value> outputs = new HashMap<>();
        Map<Integer, Integer> delays = new HashMap<>();
        for (int i = 0; i < ports.length; i++) stimuli.put(ports[i], desktop(inputs.get(i)));
        InstanceState state = (InstanceState) Proxy.newProxyInstance(ArithmeticOracle.class.getClassLoader(),
            new Class<?>[] {InstanceState.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAttributeValue" -> attributes.getValue((Attribute<?>) args[0]);
                case "getAttributeSet" -> attributes;
                case "getPortValue" -> stimuli.getOrDefault((Integer) args[0], Value.NIL);
                case "setPort" -> { outputs.put((Integer) args[0], (Value) args[1]); delays.put((Integer) args[0], (Integer) args[2]); yield null; }
                case "isPortConnected" -> true;
                case "getFactory" -> factory;
                case "getTickCount" -> 0;
                case "isCircuitRoot" -> true;
                default -> null;
            });
        factory.propagate(state);
        List<LogicVector> actual = ArithmeticLogic.INSTANCE.evaluate(operation, width, inputs, mode, shift, finder);
        int[] expectedPorts = outputPorts(operation);
        if (actual.size() != expectedPorts.length) throw new AssertionError("Output count " + operation);
        for (int i = 0; i < actual.size(); i++) {
            comparisons++;
            Value expected = outputs.get(expectedPorts[i]);
            if (!expected.equals(desktop(actual.get(i)))) {
                throw new AssertionError(operation + " width=" + width + " mode=" + mode + " shift=" + shift + " finder=" + finder +
                    " inputs=" + inputs + " output=" + i + " expected=" + expected.toBinaryString() + " actual=" + actual.get(i).getDisplay());
            }
            long delay = ArithmeticLogic.INSTANCE.delay(operation, width, inputs.size());
            if (delays.get(expectedPorts[i]) != delay) throw new AssertionError(operation + " delay " + delay);
        }
    }

    public static void main(String[] args) throws Exception {
        int[] widths = java.util.stream.IntStream.rangeClosed(1, 64).toArray();
        String[] shifts = {"ll", "lr", "ar", "rl", "rr"};
        String[] finders = {"low1", "high1", "low0", "high0"};
        for (ArithmeticOperation operation : ArithmeticOperation.values()) {
            for (int width : widths) for (int sample = 0; sample < 160; sample++) {
                boolean partial = sample % 3 == 0;
                int count = operation == ArithmeticOperation.BIT_ADDER ? 1 + RANDOM.nextInt(8) : inputPorts(operation, 1).length;
                List<LogicVector> inputs = new ArrayList<>();
                for (int i = 0; i < count; i++) inputs.add(random(width, partial));
                if (operation == ArithmeticOperation.ADDER || operation == ArithmeticOperation.SUBTRACTOR)
                    inputs.set(2, random(1, partial));
                if (operation == ArithmeticOperation.EXPONENTIATOR)
                    inputs.set(1, partial ? random(width, true) : number(width, sample % 64));
                if (operation == ArithmeticOperation.SHIFTER)
                    inputs.set(1, random(ArithmeticLogic.INSTANCE.indexWidth(width), partial));
                String mode = sample % 2 == 0 ? "unsigned" : "twosComplement";
                if (operation == ArithmeticOperation.MULTIPLIER && sample % 4 == 0) mode = "signedUnsigned";
                check(operation, width, inputs, mode, shifts[sample % shifts.length], finders[sample % finders.length]);
            }
        }
        for (int width : widths) {
            LogicVector zero = number(width, 0);
            LogicVector ones = number(width, -1);
            check(ArithmeticOperation.DIVIDER, width, List.of(ones, zero, zero), "unsigned", "ll", "low1");
            check(ArithmeticOperation.MULTIPLIER, width, List.of(ones, ones, zero), "unsigned", "ll", "low1");
            check(ArithmeticOperation.SQUARE_ROOT, width, List.of(ones, ones), "unsigned", "ll", "low1");
            if (width >= 32) {
                for (long exponent : new long[] {0x80000000L, 0xffffffffL, 0x100000000L, Long.MIN_VALUE | 2}) {
                    check(ArithmeticOperation.EXPONENTIATOR, width,
                        List.of(number(width, 2), number(width, exponent)), "unsigned", "ll", "low1");
                    check(ArithmeticOperation.EXPONENTIATOR, width,
                        List.of(ones, number(width, exponent)), "twosComplement", "ll", "low1");
                }
            }
            if (width > 40) {
                for (LogicValue unknown : new LogicValue[] {LogicValue.UNKNOWN, LogicValue.ERROR}) {
                    check(ArithmeticOperation.MULTIPLIER, width,
                        List.of(ones.withBit(40, unknown), ones, zero), "unsigned", "ll", "low1");
                }
            }
        }
        System.out.println("PASS " + comparisons + " arithmetic output/delay comparisons and " + layouts +
            " layouts against desktop propagate, seed=320064");
    }
}
