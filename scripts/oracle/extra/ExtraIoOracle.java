import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceDataSingleton;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.std.bfh.BinToBcd;
import com.cburch.logisim.std.bfh.BcdToSevenSegmentDisplay;
import com.cburch.logisim.std.io.extra.PlaRomData;
import com.cburch.logisim.std.io.extra.Slider;
import com.cburch.logisim.std.io.extra.Switch;
import com.cburch.logisim.std.io.extra.TwoPinLed;
import com.cburch.logisim.std.io.extra.TwoWaySwitch;
import dev.logisim.mobile.core.ExtraIoLogic;
import dev.logisim.mobile.core.ExtraSliderState;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import dev.logisim.mobile.core.PlaRomTable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

class ExtraIoOracle {
    private static int comparisons;
    private static final ExtraIoLogic MOBILE = ExtraIoLogic.INSTANCE;

    private static class Fixture implements InvocationHandler {
        final InstanceFactory factory;
        final AttributeSet attributes;
        final Map<Integer, Value> inputs = new HashMap<>();
        final Map<Integer, Value> outputs = new HashMap<>();
        Object data;

        Fixture(InstanceFactory factory) {
            this.factory = factory;
            attributes = factory.createAttributeSet();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void attribute(String name, String value) {
            for (Attribute key : attributes.getAttributes()) {
                if (key.getName().equals(name)) {
                    attributes.setValue(key, key.parse(value));
                    return;
                }
            }
            throw new AssertionError("Missing desktop attribute " + name);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAttributeValue" -> attributes.getValue((Attribute) args[0]);
                case "getAttributeSet" -> attributes;
                case "getPortValue" -> inputs.getOrDefault((Integer) args[0], Value.UNKNOWN);
                case "setPort" -> { outputs.put((Integer) args[0], (Value) args[1]); yield null; }
                case "getData" -> data;
                case "setData" -> { data = args[0]; yield null; }
                case "getFactory" -> factory;
                case "isCircuitRoot", "isPortConnected" -> true;
                case "getTickCount" -> 0;
                case "toString" -> "ExtraIoOracleFixture";
                case "hashCode" -> System.identityHashCode(this);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }

        void evaluate() {
            outputs.clear();
            factory.propagate((InstanceState) Proxy.newProxyInstance(
                InstanceState.class.getClassLoader(), new Class<?>[] {InstanceState.class}, this));
        }
    }

    private static LogicVector mobile(Value value) {
        List<LogicValue> bits = new ArrayList<>();
        for (Value bit : value.getAll()) {
            bits.add(bit == Value.TRUE ? LogicValue.HIGH : bit == Value.FALSE ? LogicValue.LOW
                : bit == Value.ERROR ? LogicValue.ERROR : LogicValue.UNKNOWN);
        }
        return LogicVector.Companion.fromBits(bits);
    }

    private static void check(String name, Value expected, LogicVector actual) {
        comparisons++;
        if (!mobile(expected).equals(actual)) {
            throw new AssertionError(name + " desktop=" + expected.toBinaryString() + " mobile=" + actual.getDisplay());
        }
    }

    private static Value known(int width, long value) { return Value.createKnown(BitWidth.create(width), value); }

    public static void main(String[] args) throws Exception {
        Fixture binary = new Fixture(new BinToBcd());
        for (int width = 4; width <= 13; width++) {
            binary.attribute("binvalue", Integer.toString(width));
            for (int value = 0; value < 1 << width; value++) {
                binary.inputs.put(0, known(width, value));
                binary.evaluate();
                List<LogicVector> result = MOBILE.binaryToBcd(mobile(binary.inputs.get(0)), width);
                for (int index = 0; index < result.size(); index++) check("BinToBcd " + width + "/" + value, binary.outputs.get(index + 1), result.get(index));
            }
            for (Value undefined : List.of(Value.createUnknown(BitWidth.create(width)), Value.createError(BitWidth.create(width)))) {
                binary.inputs.put(0, undefined);
                binary.evaluate();
                List<LogicVector> result = MOBILE.binaryToBcd(mobile(undefined), width);
                for (int index = 0; index < result.size(); index++) check("BinToBcd undefined", binary.outputs.get(index + 1), result.get(index));
            }
        }

        Fixture bcd = new Fixture(new BcdToSevenSegmentDisplay());
        for (int value = 0; value < 16; value++) {
            bcd.inputs.put(7, known(4, value));
            bcd.evaluate();
            List<LogicVector> result = MOBILE.bcdToSegments(mobile(bcd.inputs.get(7)));
            for (int index = 0; index < 7; index++) check("BCD7 " + value, bcd.outputs.get(index), result.get(index));
        }
        for (Value undefined : List.of(Value.createUnknown(BitWidth.create(4)), Value.createError(BitWidth.create(4)))) {
            bcd.inputs.put(7, undefined);
            bcd.evaluate();
            List<LogicVector> result = MOBILE.bcdToSegments(mobile(undefined));
            for (int index = 0; index < 7; index++) check("BCD7 undefined", bcd.outputs.get(index), result.get(index));
        }

        Fixture single = new Fixture(new Switch());
        Fixture twoWay = new Fixture(new TwoWaySwitch());
        single.attribute("width", "8");
        twoWay.attribute("width", "8");
        for (int value = 0; value < 256; value++) {
            Value input = known(8, value);
            for (boolean enabled : new boolean[] {false, true}) {
                single.inputs.put(0, input);
                single.data = new InstanceDataSingleton(enabled);
                single.evaluate();
                check("Switch", single.outputs.get(1), (LogicVector) ExtraIoLogic.class
                    .getMethod("switch", LogicVector.class, boolean.class).invoke(MOBILE, mobile(input), enabled));
                twoWay.attribute("mode", "1in2out");
                twoWay.inputs.put(0, input);
                twoWay.data = new InstanceDataSingleton(enabled);
                twoWay.evaluate();
                List<LogicVector> output = MOBILE.twoWaySwitch(mobile(input), enabled);
                check("TwoWay out1", twoWay.outputs.get(1), output.get(0));
                check("TwoWay out2", twoWay.outputs.get(2), output.get(1));
                twoWay.attribute("mode", "2in1out");
                twoWay.inputs.put(1, input);
                twoWay.inputs.put(2, known(8, 255 - value));
                twoWay.evaluate();
                check("TwoWay selector", twoWay.outputs.get(0), MOBILE.twoWaySelect(mobile(input), mobile(twoWay.inputs.get(2)), enabled));
            }
        }

        Fixture slider = new Fixture(new Slider());
        for (int width = 1; width <= 8; width++) {
            slider.attribute("width", Integer.toString(width));
            for (boolean rightToLeft : new boolean[] {false, true}) {
                slider.attribute("Direction", rightToLeft ? "right_to_left" : "left_to_right");
                for (int position = 0; position < 256; position++) {
                    Slider.SliderValue value = new Slider.SliderValue();
                    value.setDirection(rightToLeft);
                    value.setSliderPosition(position);
                    slider.data = value;
                    slider.evaluate();
                    check("Slider", slider.outputs.get(0), new ExtraSliderState(position, rightToLeft).value(width));
                }
            }
        }

        Fixture led = new Fixture(new TwoPinLed());
        List<Value> bits = List.of(Value.FALSE, Value.TRUE, Value.UNKNOWN, Value.ERROR);
        for (boolean bipolar : new boolean[] {false, true}) {
            led.attribute("bipolar", Boolean.toString(bipolar));
            for (Value anode : bits) for (Value cathode : bits) {
                led.inputs.put(0, anode); led.inputs.put(1, cathode); led.evaluate();
                int expected = (Integer) ((InstanceDataSingleton) led.data).getValue();
                int actual = MOBILE.twoPinLed(mobile(anode).bit(0), mobile(cathode).bit(0), bipolar).ordinal();
                comparisons++;
                if (actual != expected) throw new AssertionError("TwoPinLed desktop=" + expected + " mobile=" + actual);
            }
        }

        Random random = new Random(46);
        for (int width = 1; width <= 6; width++) {
            for (int sample = 0; sample < 100; sample++) {
                int terms = 1 + random.nextInt(5), outputs = 1 + random.nextInt(4);
                PlaRomData desktop = new PlaRomData((byte) width, (byte) outputs, (byte) terms);
                StringBuilder encoded = new StringBuilder();
                for (int row = 0; row < terms; row++) for (int column = 0; column < width; column++) encoded.append(random.nextInt(3)).append(' ');
                for (int row = 0; row < terms; row++) for (int column = 0; column < outputs; column++) encoded.append(random.nextBoolean() ? 1 : 0).append(' ');
                desktop.decodeSavedData(encoded.toString());
                PlaRomTable mobile = PlaRomTable.Companion.decode(width, terms, outputs, encoded.toString());
                Value[] inputBits = new Value[width];
                for (int bit = 0; bit < width; bit++) inputBits[bit] = bits.get(random.nextInt(bits.size()));
                Value input = Value.create(inputBits);
                Value[] reversed = input.getAll();
                for (int i = 0; i < reversed.length / 2; i++) { Value tmp = reversed[i]; reversed[i] = reversed[reversed.length - i - 1]; reversed[reversed.length - i - 1] = tmp; }
                desktop.setInputsValue(reversed);
                check("PlaRom", Value.create(desktop.getOutputValues()), mobile.evaluate(mobile(input), LogicValue.LOW, false));
            }
        }
        System.out.println("PASS " + comparisons + " BFH/I-O-Extra desktop/mobile comparisons");
    }
}
