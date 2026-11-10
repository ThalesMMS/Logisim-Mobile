import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;

/** Executes the unmodified SAP-1 fixture using the desktop simulator. */
public class Sap1DesktopOracle {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        int result = 0;
        try {
            if (args.length > 1 && args[1].equals("ports")) {
                for (var circuit : file.getCircuits()) {
                    for (var component : circuit.getNonWires()) {
                        var line = new StringBuilder(circuit.getName()).append('\t')
                                .append(component.getFactory().getName()).append('\t')
                                .append(component.getLocation()).append('\t').append(component.getBounds());
                        for (var port : component.getEnds()) {
                            line.append('\t').append(port.getLocation()).append(':')
                                    .append(port.getWidth()).append(':').append(port.getType());
                        }
                        System.out.println(line);
                    }
                }
            } else {
                var circuit = file.getMainCircuit();
                var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
                var propagator = state.getPropagator();
                var components = new ArrayList<Component>(circuit.getNonWires());
                components.sort(Comparator.comparingInt(component ->
                        component.getLocation().getY() * 100000 + component.getLocation().getX()));
                propagator.propagate();
                var reset = components.stream().filter(component ->
                        "Reset".equals(component.getAttributeSet().getValue(StdAttr.LABEL))).findFirst().orElseThrow();
                Pin.FACTORY.driveInputPin(state.getInstanceState(reset), Value.TRUE);
                state.markComponentAsDirty(reset);
                propagator.propagate();
                Pin.FACTORY.driveInputPin(state.getInstanceState(reset), Value.FALSE);
                state.markComponentAsDirty(reset);
                propagator.propagate();
                System.out.print("tick\tdisplay");
                for (var component : components) {
                    if (component.getFactory() instanceof Pin) {
                        System.out.print("\t" + component.getLocation() + "/"
                                + component.getAttributeSet().getValue(StdAttr.LABEL));
                    }
                }
                System.out.println();
                for (int tick = 0; tick <= 160; tick++) {
                    System.out.print(tick + "\t" + state.getValue(Location.create(600, 340, true)).toHexString());
                    for (var component : components) {
                        if (component.getFactory() instanceof Pin) {
                            System.out.print("\t" + Pin.FACTORY.getValue(state.getInstanceState(component)).toHexString());
                        }
                    }
                    System.out.println();
                    if (propagator.isOscillating()) throw new IllegalStateException("Oscillating at tick " + tick);
                    propagator.toggleClocks();
                    propagator.propagate();
                }
            }
        } catch (Exception failure) {
            failure.printStackTrace();
            result = 1;
        } finally {
            project.getSimulator().shutDown();
        }
        // AWT threads remain alive after a headless load.
        System.exit(result);
    }
}
