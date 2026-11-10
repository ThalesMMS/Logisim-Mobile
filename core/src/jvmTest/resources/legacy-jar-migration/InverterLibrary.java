// SPDX-License-Identifier: GPL-3.0-only
package org.example;

import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.Collections;
import java.util.List;

/** An external component library for the original Logisim 2.7.1. */
public final class InverterLibrary extends Library {
    private final List<Tool> tools = Collections.singletonList(new AddTool(new BusInverter()));

    @Override
    public List<Tool> getTools() {
        return tools;
    }

    private static final class BusInverter extends InstanceFactory {
        BusInverter() {
            super("Bus Inverter");
            setOffsetBounds(Bounds.create(-20, -10, 20, 20));
            setPorts(new Port[] {
                new Port(-20, 0, Port.INPUT, 4),
                new Port(0, 0, Port.OUTPUT, 4)
            });
        }

        @Override
        public void propagate(InstanceState state) {
            state.setPort(1, state.getPort(0).not(), 1);
        }

        @Override
        public void paintInstance(InstancePainter painter) {
            painter.drawBounds();
            painter.drawPorts();
        }
    }
}
