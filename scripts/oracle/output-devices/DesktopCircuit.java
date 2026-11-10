package com.cburch.logisim.circuit;

import com.cburch.logisim.Main;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opens a .circ in Evolution's real simulator and drives its labelled input pins. */
public final class DesktopCircuit implements AutoCloseable {
    public final Project project;
    public final CircuitState state;
    private final Map<String, Component> inputs = new LinkedHashMap<>();

    public DesktopCircuit(File file) throws Exception {
        Main.headless = true;
        var logisimFile = new Loader(null).openLogisimFile(file);
        project = new Project(logisimFile);
        state = CircuitState.createRootState(project, logisimFile.getMainCircuit(), Thread.currentThread());
        for (var component : state.getCircuit().getNonWires()) {
            if (component.getFactory() instanceof Pin && Pin.FACTORY.isInputPin(Instance.getInstanceFor(component))) {
                var label = component.getAttributeSet().getValue(StdAttr.LABEL);
                if (label != null && !label.isEmpty()) inputs.put(label, component);
            }
        }
        state.getPropagator().propagate();
    }

    public Component component(String factoryName) {
        return state.getCircuit().getNonWires().stream()
            .filter(component -> component.getFactory().getName().equals(factoryName)).findFirst().orElseThrow();
    }

    public void drive(String label, Value value) {
        var pin = inputs.get(label);
        if (pin == null) throw new IllegalArgumentException("Unknown input " + label);
        Pin.FACTORY.driveInputPin(state.getInstanceState(pin), value);
        state.markComponentAsDirty(pin);
    }

    public void propagate() {
        state.getPropagator().propagate();
    }

    public Value value(Location location) {
        return state.getValue(location);
    }

    public Object data(Component component) {
        return state.getData(component);
    }

    @Override
    public void close() {
        project.getSimulator().shutDown();
    }
}
