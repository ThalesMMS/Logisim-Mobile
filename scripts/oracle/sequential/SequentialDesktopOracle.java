import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.prefs.AppPreferences;
import dev.logisim.mobile.core.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Compares one propagation at a time; the project-level oracle checks event scheduling. */
public final class SequentialDesktopOracle {
    private record Device(String desktop, String mobile, String[] inputs, int[] inputPorts,
                          String[] outputs, int[] outputPorts) {}
    private static final Device[] DEVICES = {
        new Device("DFlipFlop", "D_FLIP_FLOP", new String[]{"d", "clock", "clear", "preset"}, new int[]{0,1,4,5}, new String[]{"q", "nq"}, new int[]{2,3}),
        new Device("TFlipFlop", "T_FLIP_FLOP", new String[]{"t", "clock", "clear", "preset"}, new int[]{0,1,4,5}, new String[]{"q", "nq"}, new int[]{2,3}),
        new Device("JKFlipFlop", "JK_FLIP_FLOP", new String[]{"j", "k", "clock", "clear", "preset"}, new int[]{0,1,2,5,6}, new String[]{"q", "nq"}, new int[]{3,4}),
        new Device("SRFlipFlop", "SR_FLIP_FLOP", new String[]{"s", "r", "clock", "clear", "preset"}, new int[]{0,1,2,5,6}, new String[]{"q", "nq"}, new int[]{3,4}),
        new Device("Register", "REGISTER", new String[]{"data", "clock", "clear", "enable"}, new int[]{1,2,3,4}, new String[]{"q"}, new int[]{0}),
        new Device("Counter", "COUNTER", new String[]{"data", "clock", "clear", "load", "enable", "up"}, new int[]{1,2,3,4,6,5}, new String[]{"q", "carry"}, new int[]{0,7}),
        new Device("ShiftRegister", "SHIFT_REGISTER", new String[]{"serial", "shift", "clock", "clear", "load", "parallel0", "parallel1", "parallel2", "parallel3"}, new int[]{0,1,2,3,5,6,8,10,12}, new String[]{"q", "stage0", "stage1", "stage2", "stage3"}, new int[]{4,7,9,11,13}),
        new Device("Random", "RANDOM", new String[]{"clock", "enable", "reset"}, new int[]{1,2,3}, new String[]{"q"}, new int[]{0}),
    };
    private static final Set<String> WORDS = Set.of("data", "serial", "parallel0", "parallel1", "parallel2", "parallel3");
    private static final ComponentRegistry REGISTRY = new ComponentRegistry(SequentialLibrary.INSTANCE.getDefinitions());

    public static void main(String[] args) throws Exception {
        boolean originalStartup = AppPreferences.Memory_Startup_Unknown.get();
        try {
        var random = new java.util.Random(420042L);
        long checks = 0;
        for (boolean startupUnknown : new boolean[]{false, true}) {
        setStartup(startupUnknown);
        for (var descriptor : DEVICES) {
            var widths = descriptor.desktop.endsWith("FlipFlop") ? new int[]{1} : new int[]{1,8,32,64};
            var triggers = Set.of("DFlipFlop", "SRFlipFlop", "Register").contains(descriptor.desktop)
                ? new String[]{"rising","falling","high","low"} : new String[]{"rising","falling"};
            for (int width : widths) for (String trigger : triggers) {
                var attrs = new LinkedHashMap<String,String>();
                attrs.put("appearance", "classic"); attrs.put("trigger", trigger);
                if (!descriptor.desktop.endsWith("FlipFlop")) attrs.put("width", Integer.toString(width));
                if (descriptor.desktop.equals("Random")) attrs.put("seed", "1");
                if (descriptor.desktop.equals("ShiftRegister")) { attrs.put("length", "4"); attrs.put("parallel", "true"); }
                var desktop = new Desktop(descriptor.desktop, attrs);
                var kind = new ComponentKind(descriptor.mobile);
                var component = REGISTRY.create(kind, "test", new GridPoint(0,0), attrs, "");
                var behavior = REGISTRY.requireDefinition(kind).getBehavior();
                ComponentState state = EmptyComponentState.INSTANCE;
                for (int step = 0; step < 500; step++) {
                    var inputs = new LinkedHashMap<String,LogicVector>();
                    for (int index = 0; index < descriptor.inputs.length; index++) {
                        String name = descriptor.inputs[index];
                        boolean word = WORDS.contains(name);
                        Value value;
                        if (name.equals("clock")) value = step % 2 == 0 ? Value.FALSE : Value.TRUE;
                        else if (name.equals("clear") || name.equals("reset") || name.equals("preset")) value = random.nextInt(19) == 0 ? Value.TRUE : Value.FALSE;
                        else if (word) {
                            var bits = new Value[width];
                            for (int bit=0;bit<width;bit++) bits[bit] = random.nextBoolean() ? Value.TRUE : Value.FALSE;
                            if (!descriptor.desktop.equals("Counter") && step % 23 == 0) bits[random.nextInt(width)] = Value.UNKNOWN;
                            if (!descriptor.desktop.equals("Counter") && step % 29 == 0) bits[random.nextInt(width)] = Value.ERROR;
                            value = Value.create(bits);
                        } else {
                            int bit = random.nextInt(12);
                            value = bit == 0 ? Value.UNKNOWN : bit == 1 ? Value.ERROR : bit < 6 ? Value.FALSE : Value.TRUE;
                        }
                        inputs.put(name, mobile(value)); desktop.ports.put(descriptor.inputPorts[index], value);
                    }
                    desktop.factory.propagate(desktop.instanceState);
                    var result = behavior.evaluate(new ComponentEvaluationContext(component, REGISTRY.attributes(component), inputs,
                        inputs.keySet(), state, step, step, step,
                        Map.of("memoryStartupUnknown", Boolean.toString(startupUnknown)), List.of(), false));
                    state = result.getState();
                    for (int output=0;output<descriptor.outputs.length;output++) {
                        String expected = mobile(desktop.ports.get(descriptor.outputPorts[output])).getDisplay();
                        String actual = result.getOutputs().get(descriptor.outputs[output]).getDisplay();
                        if (!expected.equals(actual)) throw new AssertionError(descriptor.desktop + " width=" + width + " trigger=" + trigger +
                            " step=" + step + " " + descriptor.outputs[output] + " expected=" + expected + " actual=" + actual + " inputs=" + inputs);
                        checks++;
                    }
                }
            }
        }
        }
        System.out.println("PASS " + checks + " sequential desktop/mobile observations, seed=420042");
        } finally {
            setStartup(originalStartup);
        }
    }

    private static void setStartup(boolean unknown) throws InterruptedException {
        AppPreferences.Memory_Startup_Unknown.set(unknown);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (AppPreferences.Memory_Startup_Unknown.get() != unknown && System.nanoTime() < deadline) Thread.sleep(1);
        if (AppPreferences.Memory_Startup_Unknown.get() != unknown) throw new AssertionError("Desktop preference did not update");
    }

    private static LogicVector mobile(Value value) {
        var bits = new ArrayList<LogicValue>();
        for (int i=0;i<value.getWidth();i++) {
            var bit = value.get(i);
            bits.add(bit == Value.TRUE ? LogicValue.HIGH : bit == Value.FALSE ? LogicValue.LOW : bit == Value.ERROR ? LogicValue.ERROR : LogicValue.UNKNOWN);
        }
        return LogicVector.Companion.fromBits(bits);
    }

    private static final class Desktop {
        final InstanceFactory factory;
        final AttributeSet attributes;
        final Map<Integer,Value> ports = new HashMap<>();
        final InstanceState instanceState;
        Object data;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Desktop(String className, Map<String,String> raw) throws Exception {
            factory = (InstanceFactory) Class.forName("com.cburch.logisim.std.memory." + className).getConstructor().newInstance();
            attributes = factory.createAttributeSet();
            for (var entry : raw.entrySet()) {
                Attribute attribute = attributes.getAttribute(entry.getKey());
                attributes.setValue(attribute, attribute.parse(entry.getValue()));
            }
            instanceState = (InstanceState) Proxy.newProxyInstance(InstanceState.class.getClassLoader(), new Class[]{InstanceState.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAttributeSet" -> attributes;
                case "getAttributeValue" -> attributes.getValue((Attribute)args[0]);
                case "getData" -> data;
                case "setData" -> { data=args[0]; yield null; }
                case "getFactory" -> factory;
                case "getPortValue" -> ports.getOrDefault((Integer)args[0], Value.UNKNOWN);
                case "setPort" -> { ports.put((Integer)args[0], (Value)args[1]); yield null; }
                case "isPortConnected" -> ports.containsKey((Integer)args[0]);
                case "isCircuitRoot" -> true;
                case "getTickCount" -> 0;
                default -> null;
            });
        }
    }

    public static final class MemoryPreferencesFactory implements PreferencesFactory {
        private final Preferences user = new MemoryPreferences(null, "");
        private final Preferences system = new MemoryPreferences(null, "");
        @Override public Preferences userRoot() { return user; }
        @Override public Preferences systemRoot() { return system; }
    }

    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String,String> values = new HashMap<>();
        private final Map<String,MemoryPreferences> children = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); children.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
        @Override protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n)); }
        @Override protected void syncSpi() {}
        @Override protected void flushSpi() {}
    }
}
