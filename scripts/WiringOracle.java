import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.BitExtender;
import com.cburch.logisim.std.wiring.Transistor;
import com.cburch.logisim.std.wiring.TransmissionGate;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import dev.logisim.mobile.core.WiringValues;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class WiringOracle {
  private static final Value[] BITS = {Value.FALSE, Value.TRUE, Value.UNKNOWN, Value.ERROR};
  private static int comparisons;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void attribute(AttributeSet attrs, String name, String value) {
    Attribute attr = attrs.getAttribute(name);
    if (attr == null) throw new AssertionError("Missing desktop attribute " + name);
    attrs.setValue(attr, attr.parse(value));
  }

  private static Value output(InstanceFactory factory, AttributeSet attrs, Value... inputs) {
    Value[] out = {null};
    InstanceState state = (InstanceState) Proxy.newProxyInstance(
        InstanceState.class.getClassLoader(), new Class<?>[]{InstanceState.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getAttributeValue" -> attrs.getValue((Attribute<?>) args[0]);
          case "getAttributeSet" -> attrs;
          case "getPortValue" -> inputs[(Integer) args[0]];
          case "setPort" -> {
            if ((Integer) args[0] == 0) out[0] = (Value) args[1];
            yield null;
          }
          default -> throw new AssertionError("Unexpected desktop call " + method);
        });
    factory.propagate(state);
    if (out[0] == null) throw new AssertionError("Desktop did not drive port 0");
    return out[0];
  }

  private static LogicVector mobile(Value value) {
    List<LogicValue> bits = new ArrayList<>();
    for (Value bit : value.getAll()) {
      bits.add(bit == Value.TRUE ? LogicValue.HIGH : bit == Value.FALSE ? LogicValue.LOW :
          bit == Value.UNKNOWN ? LogicValue.UNKNOWN : LogicValue.ERROR);
    }
    return LogicVector.Companion.fromBits(bits);
  }

  private static void same(String description, Value expected, LogicVector actual) {
    if (!mobile(expected).equals(actual)) {
      throw new AssertionError(description + ": desktop=" + expected.toBinaryString() + " mobile=" + actual.getDisplay());
    }
    comparisons++;
  }

  public static void main(String[] args) {
    Random random = new Random(410041);
    Transistor transistor = new Transistor();
    TransmissionGate transmission = new TransmissionGate();
    for (int width : new int[]{1, 4, 8, 32, 64}) {
      AttributeSet transistorAttrs = transistor.createAttributeSet();
      AttributeSet transmissionAttrs = transmission.createAttributeSet();
      transistorAttrs.setValue(StdAttr.WIDTH, BitWidth.create(width));
      transmissionAttrs.setValue(StdAttr.WIDTH, BitWidth.create(width));
      for (int sample = 0; sample < 400; sample++) {
        Value[] bits = new Value[width];
        for (int bit = 0; bit < width; bit++) bits[bit] = BITS[random.nextInt(BITS.length)];
        Value input = Value.create(bits);
        for (Value gate : BITS) {
          for (boolean pType : new boolean[]{false, true}) {
            attribute(transistorAttrs, "type", pType ? "p" : "n");
            same("transistor/" + width + "/" + pType, output(transistor, transistorAttrs, Value.UNKNOWN, input, gate),
                WiringValues.INSTANCE.transistor(mobile(input), mobile(gate), pType, false));
          }
          for (Value secondGate : BITS) {
            same("transmission/" + width, output(transmission, transmissionAttrs, Value.UNKNOWN, input, gate, secondGate),
                WiringValues.INSTANCE.transmissionGate(mobile(input), mobile(gate), mobile(secondGate)));
          }
        }
        for (String type : new String[]{"zero", "one", "sign", "input"}) {
          for (int outWidth : new int[]{1, 8, 32, 64}) {
            AttributeSet attrs = BitExtender.FACTORY.createAttributeSet();
            attribute(attrs, "in_width", Integer.toString(width));
            attribute(attrs, "out_width", Integer.toString(outWidth));
            attribute(attrs, "type", type);
            Value extend = BITS[random.nextInt(BITS.length)];
            same("extend/" + type + "/" + width + "/" + outWidth,
                output(BitExtender.FACTORY, attrs, Value.UNKNOWN, input, extend),
                WiringValues.INSTANCE.extend(mobile(input), outWidth, type, mobile(extend)));
          }
        }
      }
    }
    System.out.println("PASS: " + comparisons + " Wiring desktop/mobile comparisons");
  }
}
