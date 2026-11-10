import com.cburch.logisim.Main;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.std.plexers.*;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import dev.logisim.mobile.core.PlexerDisabledOutput;
import dev.logisim.mobile.core.PlexersLogic;
import java.lang.reflect.Proxy;
import java.util.*;

/** Executes desktop component factories and the mobile evaluator with identical pin values. */
public class PlexersOracle {
    private static final Random RANDOM = new Random(31042026);
    private static int compared;
    private static int layoutsCompared;

    record Signal(Value desktop, LogicVector mobile) {}
    record Evaluation(Map<Integer, Value> outputs, Map<Integer, Integer> delays) {}

    static Signal signal(int width, int mode) {
        var bits = new ArrayList<LogicValue>();
        var values = new Value[width];
        for (int bit = 0; bit < width; bit++) {
            int state = mode < 0 ? RANDOM.nextInt(4) : mode;
            bits.add(LogicValue.values()[state]);
            values[bit] = new Value[] {Value.FALSE, Value.TRUE, Value.UNKNOWN, Value.ERROR}[state];
        }
        return new Signal(Value.create(values), LogicVector.Companion.fromBits(bits));
    }

    static Signal known(int width, long value) {
        var bits = new ArrayList<LogicValue>();
        for (int bit = 0; bit < width; bit++) bits.add((value & (1L << bit)) == 0 ? LogicValue.LOW : LogicValue.HIGH);
        return new Signal(Value.createKnown(com.cburch.logisim.data.BitWidth.create(width), value),
            LogicVector.Companion.fromBits(bits));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void attribute(AttributeSet attrs, String name, String value) {
        Attribute attr = attrs.getAttribute(name);
        if (attr == null) throw new AssertionError("Desktop attribute missing: " + name);
        attrs.setValue(attr, attr.parse(value));
    }

    static Evaluation desktop(InstanceFactory factory, AttributeSet attrs, Map<Integer, Value> pins,
            int enablePort, boolean enableConnected) {
        var outputs = new HashMap<Integer, Value>();
        var delays = new HashMap<Integer, Integer>();
        var state = (InstanceState) Proxy.newProxyInstance(InstanceState.class.getClassLoader(),
            new Class<?>[] {InstanceState.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAttributeValue" -> attrs.getValue((Attribute<?>) args[0]);
                case "getPortValue" -> pins.getOrDefault((int) args[0], Value.UNKNOWN);
                case "isPortConnected" -> (int) args[0] != enablePort || enableConnected;
                case "setPort" -> {
                    outputs.put((int) args[0], (Value) args[1]);
                    delays.put((int) args[0], (int) args[2]);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected desktop state call: " + method.getName());
            });
        factory.propagate(state);
        return new Evaluation(outputs, delays);
    }

    static void check(String title, Evaluation expected, int index, LogicVector actual) {
        compared++;
        var value = expected.outputs.get(index);
        if (value == null || value.getWidth() != actual.getWidth() || expected.delays.get(index) != 3)
            throw new AssertionError(title + " output width/index/delay differ at " + index);
        for (int bit = 0; bit < actual.getWidth(); bit++) {
            var expectedBit = value.get(bit);
            var actualBit = actual.bit(bit);
            if (!(expectedBit == Value.FALSE && actualBit == LogicValue.LOW
                    || expectedBit == Value.TRUE && actualBit == LogicValue.HIGH
                    || expectedBit == Value.UNKNOWN && actualBit == LogicValue.UNKNOWN
                    || expectedBit == Value.ERROR && actualBit == LogicValue.ERROR)) {
                throw new AssertionError(title + " pin " + index + ": desktop=" + value.toBinaryString()
                    + " mobile=" + actual.getDisplay());
            }
        }
    }

    static void geometry(String name, InstanceFactory factory, Map<String, String> values) {
        var registry = new dev.logisim.mobile.core.ComponentRegistry(
            dev.logisim.mobile.core.PlexersLibrary.INSTANCE.getDefinitions());
        var definition = registry.getDefinitions().stream().filter(d -> d.getFileName().equals(name)).findFirst().orElseThrow();
        var attrs = factory.createAttributeSet();
        for (var pair : values.entrySet()) {
            if (attrs.getAttribute(pair.getKey()) != null) attribute(attrs, pair.getKey(), pair.getValue());
        }
        var expected = factory.createComponent(Location.create(100, 200, true), attrs);
        var component = registry.create(definition.getKind(), "sample", new dev.logisim.mobile.core.GridPoint(100, 200), values, "");
        var actual = registry.layout(component);
        String context = name + values;
        var bounds = expected.getBounds();
        var other = actual.getBounds();
        if (bounds.getX() != other.getLeft() || bounds.getY() != other.getTop()
                || bounds.getWidth() != other.getWidth() || bounds.getHeight() != other.getHeight()) {
            throw new AssertionError(context + " bounds desktop=" + bounds + " mobile=" + other);
        }
        if (expected.getEnds().size() != actual.getPorts().size()) throw new AssertionError(context + " port count differs");
        for (int i = 0; i < expected.getEnds().size(); i++) {
            var end = expected.getEnd(i);
            var port = actual.getPorts().get(i);
            int direction = switch (port.getDirection()) { case INPUT -> 1; case OUTPUT -> 2; case INOUT -> 3; };
            if (end.getLocation().getX() != port.getPosition().getX()
                    || end.getLocation().getY() != port.getPosition().getY()
                    || end.getWidth().getWidth() != port.getWidth() || end.getType() != direction) {
                throw new AssertionError(context + " port " + i + " desktop=" + end.getLocation() + ":"
                    + end.getWidth() + ":" + end.getType() + " mobile=" + port);
            }
        }
        layoutsCompared++;
    }

    static void geometries() {
        var factories = Map.<String, InstanceFactory>of("Multiplexer", new Multiplexer(),
            "Demultiplexer", new Demultiplexer(), "Decoder", new Decoder(),
            "Priority Encoder", new PriorityEncoder(), "BitSelector", new BitSelector());
        for (var facing : new String[] {"east", "west", "north", "south"})
            for (var location : new String[] {"bl", "tr"})
                for (int select : new int[] {1, 2, 3, 8})
                    for (int width : new int[] {1, 8, 64})
                        for (boolean enable : new boolean[] {false, true})
                            for (var size : new String[] {"20", "40"}) {
                                var values = Map.of("facing", facing, "selloc", location, "select", "" + select,
                                    "width", "" + width, "enable", "" + enable, "size", size, "group", "3");
                                for (var entry : factories.entrySet()) geometry(entry.getKey(), entry.getValue(), values);
                            }
    }

    public static void main(String[] args) {
        Main.headless = true;
        for (int width : new int[] {1, 8, 32, 64}) {
            for (int selectBits : new int[] {1, 2, 3, 8}) {
                int n = 1 << selectBits;
                for (int sample = 0; sample < 40; sample++) {
                    boolean hasEnable = sample % 2 == 0;
                    boolean connected = sample % 3 != 0;
                    boolean tristate = sample % 2 == 1;
                    boolean floating = sample % 3 == 1;
                    var disabled = floating ? PlexerDisabledOutput.FLOATING : PlexerDisabledOutput.ZERO;
                    var select = sample % 5 < 3 ? known(selectBits, RANDOM.nextInt(n)) : signal(selectBits, sample % 5 == 3 ? 2 : 3);
                    var enable = signal(1, sample % 4);
                    var inputs = new ArrayList<LogicVector>();
                    var pins = new HashMap<Integer, Value>();
                    for (int i = 0; i < n; i++) {
                        var data = signal(width, -1);
                        inputs.add(data.mobile);
                        pins.put(i, data.desktop);
                    }
                    pins.put(n, select.desktop);
                    pins.put(n + 1, enable.desktop);
                    var mux = new Multiplexer();
                    var attrs = mux.createAttributeSet();
                    attribute(attrs, "width", "" + width);
                    attribute(attrs, "select", "" + selectBits);
                    attribute(attrs, "enable", "" + hasEnable);
                    attribute(attrs, "disabled", floating ? "Z" : "0");
                    check("mux", desktop(mux, attrs, pins, n + 1, connected), n + (hasEnable ? 2 : 1),
                        PlexersLogic.INSTANCE.multiplexer(inputs, select.mobile, hasEnable ? enable.mobile : null, connected, disabled));

                    var data = signal(width, -1);
                    pins = new HashMap<>();
                    pins.put(n, select.desktop);
                    pins.put(n + 1, enable.desktop);
                    pins.put(n + (hasEnable ? 2 : 1), data.desktop);
                    var demux = new Demultiplexer();
                    attrs = demux.createAttributeSet();
                    attribute(attrs, "width", "" + width);
                    attribute(attrs, "select", "" + selectBits);
                    attribute(attrs, "enable", "" + hasEnable);
                    attribute(attrs, "disabled", floating ? "Z" : "0");
                    attribute(attrs, "tristate", "" + tristate);
                    var expected = desktop(demux, attrs, pins, n + 1, connected);
                    var outputs = PlexersLogic.INSTANCE.demultiplexer(data.mobile, select.mobile, n,
                        hasEnable ? enable.mobile : null, connected, tristate, disabled);
                    for (int i = 0; i < n; i++) check("demux", expected, i, outputs.get(i));

                    var decoder = new Decoder();
                    attrs = decoder.createAttributeSet();
                    attribute(attrs, "select", "" + selectBits);
                    attribute(attrs, "enable", "" + hasEnable);
                    attribute(attrs, "disabled", floating ? "Z" : "0");
                    attribute(attrs, "tristate", "" + tristate);
                    expected = desktop(decoder, attrs, pins, n + 1, connected);
                    outputs = PlexersLogic.INSTANCE.decoder(select.mobile, n, hasEnable ? enable.mobile : null,
                        connected, tristate, disabled);
                    for (int i = 0; i < n; i++) check("decoder", expected, i, outputs.get(i));
                }
            }
        }

        for (int bits = 1; bits <= 8; bits++) {
            int n = 1 << bits;
            for (int sample = 0; sample < 40; sample++) {
                var pins = new HashMap<Integer, Value>();
                var data = new ArrayList<LogicVector>();
                for (int i = 0; i < n; i++) {
                    var signal = signal(1, -1);
                    pins.put(i, signal.desktop);
                    data.add(signal.mobile);
                }
                var enable = signal(1, sample % 4);
                pins.put(n + 1, enable.desktop);
                boolean floating = sample % 2 == 0;
                var factory = new PriorityEncoder();
                var attrs = factory.createAttributeSet();
                attribute(attrs, "select", "" + bits);
                attribute(attrs, "disabled", floating ? "Z" : "0");
                var expected = desktop(factory, attrs, pins, n + 1, true);
                var actual = PlexersLogic.INSTANCE.priorityEncoder(data, bits, enable.mobile,
                    floating ? PlexerDisabledOutput.FLOATING : PlexerDisabledOutput.ZERO);
                check("priority", expected, n, actual.getValue());
                check("priority-en", expected, n + 2, actual.getEnableOut());
                check("priority-gs", expected, n + 3, actual.getGroupSelect());
            }
        }

        for (int width : new int[] {1, 8, 32, 64}) for (int group : new int[] {1, 3, 8, 64}) {
            int selectBits = PlexersLogic.INSTANCE.bitSelectorSelectBits(width, group);
            for (int sample = 0; sample < 40; sample++) {
                var data = signal(width, -1);
                var select = sample % 5 < 3 ? known(selectBits, RANDOM.nextInt(1 << selectBits))
                    : signal(selectBits, sample % 5 == 3 ? 2 : 3);
                var factory = new BitSelector();
                var attrs = factory.createAttributeSet();
                attribute(attrs, "width", "" + width);
                attribute(attrs, "group", "" + group);
                var expected = desktop(factory, attrs, Map.of(1, data.desktop, 2, select.desktop), -1, true);
                check("bit-selector", expected, 0, PlexersLogic.INSTANCE.bitSelector(data.mobile, select.mobile, group));
            }
        }
        geometries();
        System.out.println("PASS " + compared + " desktop/mobile Plexers outputs and "
            + layoutsCompared + " layouts, seed=31042026");
    }
}
