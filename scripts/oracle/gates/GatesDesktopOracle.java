import com.cburch.logisim.Main;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Options;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.GatesLibrary;
import com.cburch.logisim.tools.AddTool;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/** Emits real factory propagation and geometry results for the complete desktop Gates catalog. */
public final class GatesDesktopOracle {
    private static final Random RANDOM = new Random(40031L);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void set(AttributeSet attrs, String name, String raw) {
        Attribute attribute = attrs.getAttribute(name);
        if (attribute == null) throw new IllegalArgumentException("Missing attribute " + name);
        attrs.setValue(attribute, attribute.parse(raw));
    }

    private static String bits(Value value) {
        var text = new StringBuilder();
        for (int bit = value.getWidth() - 1; bit >= 0; bit--) {
            Value v = value.get(bit);
            text.append(v == Value.TRUE ? '1' : v == Value.FALSE ? '0' : v == Value.ERROR ? 'E' : '?');
        }
        return text.toString();
    }

    private static Value value(int width, int caseIndex, boolean enabledControl) {
        Value[] values = new Value[width];
        for (int bit = 0; bit < width; bit++) {
            int choice = enabledControl ? caseIndex % 4 : RANDOM.nextInt(caseIndex % 3 == 0 ? 4 : 2);
            values[bit] = switch (choice) {
                case 0 -> Value.FALSE;
                case 1 -> Value.TRUE;
                case 2 -> Value.UNKNOWN;
                default -> Value.ERROR;
            };
        }
        return Value.create(values);
    }

    public static void main(String[] args) throws Exception {
        Main.headless = true;
        Project project = new Project(LogisimFile.createNew(new Loader(null), null));
        try {
            System.out.println("case\tcomponent\tattributes\tinputs\tconnected\tpolicy\toutput\tdelay\tports\tbounds");
            for (var tool : new GatesLibrary().getTools()) {
                InstanceFactory factory = (InstanceFactory) ((AddTool) tool).getFactory();
                String name = factory.getName();
                boolean pla = name.equals("PLA");
                boolean controlled = name.startsWith("Controlled");
                boolean unary = controlled || name.equals("Buffer") || name.equals("NOT Gate");
                boolean sized = name.equals("NOT Gate") || name.equals("Controlled Inverter");
                for (int sample = 0; sample < 24; sample++) {
                    int width = new int[] {1, 8, 64}[sample % 3];
                    Map<String, String> raw = new LinkedHashMap<>();
                    if (!unary && !pla) raw.put("inputs", String.valueOf(sample == 23 ? 64 : new int[] {2, 3, 5}[sample % 3]));
                    raw.put("facing", new String[] {"east", "north", "west", "south"}[sample % 4]);
                    if (pla) {
                        raw.put("in_width", "3");
                        raw.put("out_width", "2");
                        raw.put("table", sample % 2 == 0 ? "10x 01\n111 10\nxxx 11" : "xxx 01\n111 10");
                    } else {
                        raw.put("width", String.valueOf(width));
                        if (!controlled) raw.put("out", new String[] {"01", "0Z", "Z1"}[sample % 3]);
                        if (controlled) raw.put("control", sample % 2 == 0 ? "right" : "left");
                        if (sized) raw.put("size", sample % 2 == 0 ? "20" : "30");
                        if (!unary) {
                            raw.put("size", new String[] {"30", "50", "70"}[sample % 3]);
                            if (sample % 2 == 1) raw.put("negate0", "true");
                            if (name.equals("XOR Gate") || name.equals("XNOR Gate")) raw.put("xor", sample % 2 == 0 ? "1" : "odd");
                        }
                    }
                    AttributeSet attrs = factory.createAttributeSet();
                    raw.forEach((key, item) -> set(attrs, key, item));
                    var component = factory.createComponent(Location.create(100, 100, true), attrs);
                    var ends = component.getEnds();
                    List<Value> data = new ArrayList<>();
                    List<Boolean> connected = new ArrayList<>();
                    Map<Integer, Value> portValues = new LinkedHashMap<>();
                    Map<Integer, Boolean> portConnected = new LinkedHashMap<>();
                    for (int port = 0; port < ends.size(); port++) {
                        var end = ends.get(port);
                        if (end.isInput()) {
                            boolean attached = pla || sample % 6 != 0 || !data.isEmpty();
                            Value input = attached ? value(end.getWidth().getWidth(), sample, controlled && data.size() == 1)
                                    : Value.createUnknown(end.getWidth());
                            data.add(input);
                            connected.add(attached);
                            portValues.put(port, input);
                            portConnected.put(port, attached);
                        }
                    }
                    String policy = sample % 2 == 0 ? "ignore" : "error";
                    project.getOptions().getAttributeSet().setValue(Options.ATTR_GATE_UNDEFINED,
                            policy.equals("error") ? Options.GATE_UNDEFINED_ERROR : Options.GATE_UNDEFINED_IGNORE);
                    Value[] result = new Value[1];
                    int[] delay = new int[1];
                    InstanceState state = (InstanceState) Proxy.newProxyInstance(InstanceState.class.getClassLoader(),
                            new Class<?>[] {InstanceState.class}, (proxy, method, arguments) -> switch (method.getName()) {
                                case "getAttributeSet" -> attrs;
                                case "getAttributeValue" -> attrs.getValue((Attribute<?>) arguments[0]);
                                case "getPortValue" -> portValues.getOrDefault((Integer) arguments[0], Value.NIL);
                                case "isPortConnected" -> portConnected.getOrDefault((Integer) arguments[0], false);
                                case "getProject" -> project;
                                case "getFactory" -> factory;
                                case "setPort" -> {
                                    result[0] = (Value) arguments[1];
                                    delay[0] = (Integer) arguments[2];
                                    yield null;
                                }
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
                    factory.propagate(state);
                    String attributeText = raw.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().replace("\n", "\\n"))
                            .collect(Collectors.joining(";"));
                    String ports = ends.stream().map(e -> e.getLocation().getX() + "," + e.getLocation().getY()
                            + "," + e.getWidth().getWidth() + "," + e.getType()).collect(Collectors.joining(";"));
                    var bounds = component.getBounds();
                    System.out.println(name.replace(' ', '_') + "-" + sample + "\t" + name + "\t" + attributeText
                            + "\t" + data.stream().map(GatesDesktopOracle::bits).collect(Collectors.joining(","))
                            + "\t" + connected.stream().map(v -> v ? "1" : "0").collect(Collectors.joining())
                            + "\t" + policy + "\t" + bits(result[0]) + "\t" + delay[0] + "\t" + ports + "\t"
                            + bounds.getX() + "," + bounds.getY() + "," + (bounds.getX() + bounds.getWidth()) + "," + (bounds.getY() + bounds.getHeight()));
                }
            }
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(0);
    }
}
