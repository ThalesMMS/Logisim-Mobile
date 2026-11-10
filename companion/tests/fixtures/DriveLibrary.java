package dev.logisim.fixture;

import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import java.util.List;

public final class DriveLibrary extends Library {
  public static final String _ID = "DriveFixture";
  private final List<AddTool> tools = List.of(
      new AddTool(new Driver("Fixture Output", Port.OUTPUT)),
      new AddTool(new Driver("Fixture Inout", Port.INOUT)));

  @Override
  public List<AddTool> getTools() { return tools; }

  private static final class Driver extends InstanceFactory {
    Driver(String name, String direction) {
      super(name);
      setOffsetBounds(Bounds.create(-30, -5, 30, 20));
      setPorts(new Port[] {
          new Port(-30, 0, Port.INPUT, 1),
          new Port(-30, 10, Port.INPUT, 1),
          new Port(0, 0, direction, 1)
      });
    }

    @Override
    public void paintInstance(InstancePainter painter) {
      painter.drawBounds();
      painter.drawPorts();
    }

    @Override
    public void propagate(InstanceState state) {
      var enabled = state.getPortValue(1);
      var drive = enabled == Value.TRUE ? state.getPortValue(0)
          : enabled == Value.FALSE ? Value.NIL
          : enabled == Value.ERROR ? Value.ERROR : Value.UNKNOWN;
      state.setPort(2, drive, 1);
    }
  }
}
