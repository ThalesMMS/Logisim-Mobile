import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentKind;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.SimulationState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

/** Exports the mobile core's visible pins and output register after each SAP-1 clock step. */
public class Sap1MobileTrace {
    public static void main(String[] args) throws Exception {
        var result = (CircProjectImportResult.Success) CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[0])));
        var project = result.getProject();
        var document = project.getMainCircuit();
        var simulator = new CircuitSimulator();
        var state = simulator.settle(project, new SimulationState(), Map.of(), false, Map.of());
        var reset = document.getComponents().stream().filter(component -> component.getLabel().equals("Reset"))
                .findFirst().orElseThrow();
        state = simulator.settle(project, state, Map.of(reset.getId(), LogicValue.HIGH), false, Map.of());
        state = simulator.settle(project, state, Map.of(reset.getId(), LogicValue.LOW), false, Map.of());
        var components = new ArrayList<CircuitComponent>(document.getComponents());
        components.sort(Comparator.comparingInt(component ->
                component.getPosition().getY() * 100000 + component.getPosition().getX()));
        System.out.print("tick");
        for (var component : components) {
            if (traced(component)) System.out.print("\t" + name(component));
        }
        System.out.println("\tdiagnostics");
        for (int tick = 0; tick <= 160; tick++) {
            System.out.print(tick);
            for (var component : components) {
                if (traced(component)) System.out.print("\t" + state.getVectorValues().get(component.getId()));
            }
            System.out.println("\t" + state.getDiagnostics());
            if (state.getOscillating()) throw new IllegalStateException("Oscillating at tick " + tick);
            state = simulator.settle(project, state, Map.of(), true, Map.of());
        }
    }

    private static boolean traced(CircuitComponent component) {
        return component.getKind().equals(ComponentKind.OUTPUT) || component.getKind().equals(ComponentKind.REGISTER);
    }

    private static String name(CircuitComponent component) {
        var position = component.getPosition();
        return "(" + position.getX() + "," + position.getY() + ")/" + component.getLabel();
    }
}
