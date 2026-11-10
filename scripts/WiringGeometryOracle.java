import com.cburch.logisim.circuit.SplitterFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.std.wiring.*;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.ComponentBounds;
import dev.logisim.mobile.core.ComponentDefinition;
import dev.logisim.mobile.core.ComponentLayout;
import dev.logisim.mobile.core.ComponentRegistry;
import dev.logisim.mobile.core.ComponentTerminal;
import dev.logisim.mobile.core.GridPoint;
import dev.logisim.mobile.core.PortDirection;
import dev.logisim.mobile.core.WiringLibrary;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WiringGeometryOracle {
  private static final ComponentRegistry REGISTRY = new ComponentRegistry(WiringLibrary.INSTANCE.definitions());
  private static int cases;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void check(ComponentFactory factory, String... entries) {
    AttributeSet attrs = factory.createAttributeSet();
    Map<String, String> raw = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      raw.put(entries[i], entries[i + 1]);
      Attribute attr = attrs.getAttribute(entries[i]);
      if (attr == null) throw new AssertionError("No desktop attribute " + entries[i] + " on " + factory.getName());
    }
    // XmlReader applies attributes in the factory's dynamic order. A splitter's
    // fanout precedes its incoming width, which establishes its default bit map.
    for (int i = 0; i < attrs.getAttributes().size(); i++) {
      Attribute attr = attrs.getAttributes().get(i);
      String stored = raw.get(attr.getName());
      if (stored != null) attrs.setValue(attr, attr.parse(stored));
    }
    Component original = factory.createComponent(Location.create(100, 100, true), attrs);
    ComponentDefinition definition = REGISTRY.resolve("#Wiring", factory.getName(), raw);
    if (definition == null) throw new AssertionError("Missing mobile " + factory.getName());
    CircuitComponent mobile = REGISTRY.create(definition.getKind(), "test", new GridPoint(100, 100), raw, raw.getOrDefault("label", ""));
    ComponentLayout layout = REGISTRY.layout(mobile);
    String prefix = factory.getName() + raw;
    if (original.getEnds().size() != layout.getPorts().size()) throw new AssertionError(prefix + " terminal count");
    for (int i = 0; i < original.getEnds().size(); i++) {
      EndData end = original.getEnds().get(i);
      ComponentTerminal terminal = layout.getPorts().get(i);
      int type = terminal.getDirection() == PortDirection.INPUT ? 1 : terminal.getDirection() == PortDirection.OUTPUT ? 2 : 3;
      if (end.getLocation().getX() != terminal.getPosition().getX() ||
          end.getLocation().getY() != terminal.getPosition().getY() ||
          end.getWidth().getWidth() != terminal.getWidth() || end.getType() != type) {
        throw new AssertionError(prefix + " port " + i + ": desktop=" + end.getLocation() + ":" + end.getWidth().getWidth() + ":" + end.getType() +
            " mobile=" + terminal);
      }
    }
    Bounds b = original.getBounds();
    ComponentBounds m = layout.getBounds();
    if (b.getX() != m.getLeft() || b.getY() != m.getTop() || b.getWidth() != m.getWidth() || b.getHeight() != m.getHeight()) {
      throw new AssertionError(prefix + " bounds: desktop=" + b + " mobile=" + m);
    }
    cases++;
  }

  public static void main(String[] args) {
    for (String facing : new String[]{"east", "north", "west", "south"}) {
      for (String appearance : new String[]{"classic", "NewPins"}) {
        check(Clock.FACTORY, "facing", facing, "appearance", appearance);
        check(Probe.FACTORY, "facing", facing, "appearance", appearance);
        for (String type : new String[]{"input", "output"}) {
          for (String width : new String[]{"1", "8", "16", "64"}) {
            for (String radix : new String[]{"2", "8", "16", "10signed", "10unsigned", "float"}) {
              check(Pin.FACTORY, "facing", facing, "appearance", appearance, "type", type, "width", width, "radix", radix);
            }
          }
        }
      }
      for (String width : new String[]{"1", "8", "64"}) {
        check(Constant.FACTORY, "facing", facing, "width", width);
        check(new Power(), "facing", facing, "width", width);
        check(new Ground(), "facing", facing, "width", width);
      }
      check(PullResistor.FACTORY, "facing", facing);
      for (String label : new String[]{"", "CLK", "Eight bit bus"}) check(Tunnel.FACTORY, "facing", facing, "label", label);
      for (String size : new String[]{"1", "2", "3"}) check(PowerOnReset.FACTORY, "facing", facing, "porsize", size);
      for (String select : new String[]{"tr", "bl"}) {
        check(new Transistor(), "facing", facing, "selloc", select, "width", "8");
        check(new TransmissionGate(), "facing", facing, "selloc", select, "width", "8");
      }
      for (String appear : new String[]{"left", "right", "center", "legacy"}) {
        for (String fanout : new String[]{"2", "3", "8"}) {
          check(SplitterFactory.instance, "facing", facing, "appear", appear, "incoming", "8", "fanout", fanout);
        }
      }
    }
    check(new DoNotConnect());
    for (String type : new String[]{"zero", "one", "sign", "input"}) check(BitExtender.FACTORY, "type", type);
    System.out.println("PASS: " + cases + " Wiring desktop/mobile geometry cases");
  }
}
