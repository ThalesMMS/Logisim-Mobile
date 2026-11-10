import com.cburch.logisim.Main;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import dev.logisim.mobile.core.FloatingPointLibrary;
import dev.logisim.mobile.core.FloatingPointNumbers;
import dev.logisim.mobile.core.ComponentKind;
import dev.logisim.mobile.core.ComponentRegistry;
import dev.logisim.mobile.core.GridPoint;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import java.lang.reflect.Proxy;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Runs the actual desktop factories, without replacing their calculations with a second formula. */
public class FloatingPointOracle {
    private static final int[] WIDTHS = {8, 16, 32, 64};
    private static final Random RANDOM = new Random(33042026L);
    private static int comparisons;
    private static int layoutComparisons;
    private static final ComponentRegistry REGISTRY = new ComponentRegistry(FloatingPointLibrary.INSTANCE.definitions());

    private static String factoryName(String id) {
        return switch (id) {
            case "FPToFP" -> "FpToFp";
            case "IntToFP" -> "IntToFp";
            default -> "Fp" + id.substring(2);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static AttributeSet attributes(InstanceFactory factory, Map<String, String> raw) {
        AttributeSet attributes = factory.createAttributeSet();
        for (Attribute attribute : attributes.getAttributes()) {
            String value = raw.get(attribute.getName());
            if (value != null) attributes.setValue(attribute, attribute.parse(value));
        }
        return attributes;
    }

    private static LogicVector input(int width, int sample) {
        List<LogicValue> bits = new ArrayList<>();
        long raw = RANDOM.nextLong();
        for (int bit = 0; bit < width; bit++) {
            bits.add(sample % 67 == 0 ? LogicValue.UNKNOWN : sample % 71 == 0 ? LogicValue.ERROR
                    : ((raw >>> bit) & 1L) == 0 ? LogicValue.LOW : LogicValue.HIGH);
        }
        return LogicVector.Companion.fromBits(bits);
    }

    private static Value desktop(LogicVector vector) {
        Value[] bits = new Value[vector.getWidth()];
        for (int bit = 0; bit < bits.length; bit++) bits[bit] = switch (vector.bit(bit)) {
            case LOW -> Value.FALSE;
            case HIGH -> Value.TRUE;
            case UNKNOWN -> Value.UNKNOWN;
            case ERROR -> Value.ERROR;
        };
        return Value.create(bits);
    }

    private static int[] inputIndices(String name, Map<String, String> attributes) {
        return switch (name) {
            case "FPConstant" -> new int[0];
            case "FPExponentiator", "FPLogarithm" ->
                (attributes.getOrDefault("mode", name.equals("FPExponentiator") ? "arb" : "logxy")
                    .equals(name.equals("FPExponentiator") ? "arb" : "logxy")) ? new int[] {0, 3} : new int[] {0};
            case "FPMultiplier" -> attributes.getOrDefault("multiplyMode", "multiply").equals("fusedMultiplyAdd")
                ? new int[] {0, 1, 4} : new int[] {0, 1};
            case "FPAdder", "FPSubtractor", "FPDivider", "FPComparator", "FPMinMax" -> new int[] {0, 1};
            default -> new int[] {0};
        };
    }

    private static int[] outputIndices(String name) {
        return switch (name) {
            case "FPConstant" -> new int[] {0};
            case "FPAdder", "FPSubtractor", "FPMultiplier" -> new int[] {2, 3};
            case "FPDivider", "FPMinMax" -> new int[] {2, 3, 4};
            case "FPComparator" -> new int[] {2, 3, 4, 5};
            case "FPTrigonometry" -> new int[] {1, 2, 3, 4};
            case "FPClassificator" -> new int[] {1, 2, 3, 4, 5, 6};
            default -> new int[] {1, 2};
        };
    }

    private static void check(String name, Map<String, String> raw, List<LogicVector> input) throws Exception {
        InstanceFactory factory = (InstanceFactory) Class.forName(
            "com.cburch.logisim.std.arith.floating." + factoryName(name)).getConstructor().newInstance();
        AttributeSet attrs = attributes(factory, raw);
        Map<Integer, Value> inputs = new HashMap<>();
        int[] inputIndices = inputIndices(name, raw);
        for (int i = 0; i < inputIndices.length; i++) inputs.put(inputIndices[i], desktop(input.get(i)));
        Map<Integer, Value> outputs = new HashMap<>();
        Map<Integer, Integer> delays = new HashMap<>();
        InstanceState state = (InstanceState) Proxy.newProxyInstance(InstanceState.class.getClassLoader(),
            new Class<?>[] {InstanceState.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAttributeValue" -> attrs.getValue((Attribute<?>) args[0]);
                case "getAttributeSet" -> attrs;
                case "getPortValue" -> inputs.getOrDefault((Integer) args[0], Value.NIL);
                case "setPort" -> {
                    outputs.put((Integer) args[0], (Value) args[1]);
                    delays.put((Integer) args[0], (Integer) args[2]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        factory.propagate(state);
        List<LogicVector> actual = FloatingPointLibrary.INSTANCE.evaluate(name, raw, input);
        int[] outputIndices = outputIndices(name);
        if (actual.size() != outputIndices.length) throw new AssertionError(name + " output count");
        long delay = FloatingPointLibrary.INSTANCE.delay(name, raw);
        for (int i = 0; i < outputIndices.length; i++) {
            Value expected = outputs.get(outputIndices[i]);
            LogicVector result = actual.get(i);
            if (expected.getWidth() != result.getWidth()) throw new AssertionError(name + " output width");
            boolean integerOutput = name.equals("FPToInt");
            boolean bothNaN = expected.getWidth() > 1 && result.isFullyDefined() && expected.isFullyDefined()
                && Double.isNaN(expected.toDoubleValueFromAnyFloat())
                && Double.isNaN(FloatingPointNumbers.INSTANCE.decode(result));
            // JDK arithmetic and float16 intrinsics may choose a different NaN payload than their fallback.
            if (!integerOutput && bothNaN) {
                if (delays.get(outputIndices[i]) != delay) throw new AssertionError(name + " propagation delay");
                comparisons++;
                continue;
            }
            for (int bit = 0; bit < expected.getWidth(); bit++) {
                Value b = expected.get(bit);
                boolean equal = switch (result.bit(bit)) {
                    case LOW -> b == Value.FALSE;
                    case HIGH -> b == Value.TRUE;
                    case UNKNOWN -> b == Value.UNKNOWN;
                    case ERROR -> b == Value.ERROR;
                };
                if (!equal) throw new AssertionError(name + " " + raw + " input=" + input
                    + " output=" + i + " desktop=" + expected.toHexString() + " mobile=" + result.getDisplay());
            }
            if (delays.get(outputIndices[i]) != delay) throw new AssertionError(name + " propagation delay");
            comparisons++;
        }
    }

    private static void samples(String name, Map<String, String> attrs) throws Exception {
        checkLayout(name, attrs);
        int count = inputIndices(name, attrs).length;
        int width = Integer.parseInt(attrs.getOrDefault(name.equals("FPToFP") ? "fpwidthin" : "fpwidth", "32"));
        if (name.equals("IntToFP")) width = Integer.parseInt(attrs.getOrDefault("width", "8"));
        double[] boundaries = {0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.5, -2.5, Double.MIN_VALUE,
            Double.MIN_NORMAL, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int sample = 0; sample < 160; sample++) {
            List<LogicVector> values = new ArrayList<>();
            for (int i = 0; i < count; i++) values.add(sample < boundaries.length && !name.equals("IntToFP")
                ? FloatingPointNumbers.INSTANCE.encode(width, boundaries[(sample + i) % boundaries.length]) : input(width, sample));
            check(name, attrs, values);
        }
    }

    private static void checkLayout(String name, Map<String, String> raw) throws Exception {
        InstanceFactory factory = (InstanceFactory) Class.forName(
            "com.cburch.logisim.std.arith.floating." + factoryName(name)).getConstructor().newInstance();
        var desktop = factory.createComponent(Location.create(100, 100, true), attributes(factory, raw));
        var component = REGISTRY.create(new ComponentKind(name), "fp", new GridPoint(100, 100), raw, "");
        var mobile = REGISTRY.layout(component);
        int[] inputs = inputIndices(name, raw);
        int[] outputs = outputIndices(name);
        for (int group = 0; group < 2; group++) {
            var ports = group == 0 ? mobile.getInputs() : mobile.getOutputs();
            var indices = group == 0 ? inputs : outputs;
            if (ports.size() != indices.length) throw new AssertionError(name + " layout port count");
            for (int i = 0; i < indices.length; i++) {
                var expected = desktop.getEnd(indices[i]);
                var actual = ports.get(i);
                if (expected.getLocation().getX() != actual.getPosition().getX()
                    || expected.getLocation().getY() != actual.getPosition().getY()
                    || expected.getWidth().getWidth() != actual.getWidth()) {
                    throw new AssertionError(name + " " + raw + " port " + indices[i] + " geometry differs");
                }
                layoutComparisons++;
            }
        }
        var a = desktop.getBounds();
        var b = mobile.getBounds();
        if (a.getX() != b.getLeft() || a.getY() != b.getTop()
            || a.getX() + a.getWidth() != b.getRight() || a.getY() + a.getHeight() != b.getBottom()) {
            throw new AssertionError(name + " " + raw + " bounds differ: " + a + " / " + b);
        }
        layoutComparisons++;
    }

    private static void checkExample() throws Exception {
        var file = new Loader(null).openLogisimFile(new File("examples/floating-point.circ"));
        var project = new Project(file);
        try {
            if (file.getCircuits().size() != 4) throw new AssertionError("Example format count");
            for (var circuit : file.getCircuits()) {
                var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
                state.getPropagator().propagate();
                for (var component : circuit.getNonWires()) {
                    if (!(component.getFactory() instanceof Pin)) continue;
                    String label = component.getAttributeSet().getValue(StdAttr.LABEL);
                    Value actual = Pin.FACTORY.getValue(state.getInstanceState(component));
                    Value expected = label.equals("NaN") ? Value.FALSE : Value.createKnown(actual.getWidth(), 5.0);
                    if (!actual.equals(expected)) throw new AssertionError(circuit.getName() + " / " + label + " fixture output");
                    comparisons++;
                }
            }
        } finally {
            project.getSimulator().shutDown();
        }
    }

    private static void run() throws Exception {
        Main.headless = true;
        for (int width : WIDTHS) {
            for (String name : FloatingPointLibrary.INSTANCE.getFileNames()) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("fpwidth", "" + width);
                samples(name, attrs);
                if (name.equals("FPMultiplier")) {
                    attrs.put("multiplyMode", "fusedMultiplyAdd"); samples(name, attrs);
                }
                String[] modes = switch (name) {
                    case "FPExponentiator" -> new String[] {"exp", "expm1"};
                    case "FPLogarithm" -> new String[] {"log", "log10", "log1p"};
                    case "FPRound", "FPToInt" -> new String[] {"ceil", "floor", "rint", "truncate"};
                    case "IntToFP" -> new String[] {"unsigned"};
                    default -> new String[0];
                };
                for (String mode : modes) { attrs.put("mode", mode); samples(name, attrs); }
                if (name.equals("FPTrigonometry")) for (String type : new String[] {"arc", "hyp"}) {
                    attrs.put("type", type); samples(name, attrs);
                }
            }
            for (int other : WIDTHS) {
                samples("FPToFP", Map.of("fpwidthin", "" + width, "fpwidthout", "" + other));
                for (String mode : new String[] {"twosComplement", "unsigned"}) {
                    samples("IntToFP", Map.of("width", "" + other, "fpwidth", "" + width, "mode", mode));
                }
                for (String mode : new String[] {"round", "ceil", "floor", "rint", "truncate"}) {
                    samples("FPToInt", Map.of("width", "" + other, "fpwidth", "" + width, "mode", mode));
                }
            }
            for (String literal : new String[] {"-0.0", "1.5", "NaN", "Infinity", "-Infinity", "1e300", "0x1.8p2"}) {
                check("FPConstant", Map.of("fpwidth", "" + width, "value", literal), List.of());
            }
        }
        checkExample();
        System.out.println("PASS " + comparisons + " floating point outputs/delays and " + layoutComparisons
            + " layout comparisons against 18 desktop factories; seed=33042026");
    }

    public static void main(String[] args) {
        try {
            run();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
