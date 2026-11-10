import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.SimulationState;
import dev.logisim.mobile.core.TraceRecorder;
import dev.logisim.mobile.core.TraceSignal;
import dev.logisim.mobile.core.TraceSignals;
import dev.logisim.mobile.core.TraceTimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/**
 * Mobile TraceRecorder.exportWaveDrom on the same circuit. Signals are selected by label in the order given
 * (Evolution's default order); one sample is captured after the initial propagation and after each half cycle,
 * with the half-cycle count as the time (TraceTimeUnit.HALF_CYCLES).
 * Usage: WaveDromMobileOracle file.circ halfCycles label...
 */
public final class WaveDromMobileOracle {
    public static void main(String[] args) throws Exception {
        var project = ((CircProjectImportResult.Success) CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[0])))).getProject();
        var halfCycles = Integer.parseInt(args[1]);
        var available = TraceSignals.INSTANCE.fromProject(project, ComponentCatalog.INSTANCE.getRegistry());
        var selected = new ArrayList<TraceSignal>();
        for (var i = 2; i < args.length; i++) {
            var label = args[i];
            selected.add(available.stream().filter(s -> s.getLabel().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No signal " + label)));
        }
        var recorder = new TraceRecorder(selected, 10_000, TraceTimeUnit.HALF_CYCLES);
        var simulator = new CircuitSimulator(256);
        var state = settle(simulator, project, new SimulationState(), false);
        recorder.capture(state, 0, true);
        for (var i = 1; i <= halfCycles; i++) {
            state = settle(simulator, project, state, true);
            recorder.capture(state, i, true);
        }
        System.out.print(recorder.exportWaveDrom(TraceSignal::getLabel));
        System.out.println();
    }

    private static SimulationState settle(CircuitSimulator simulator, dev.logisim.mobile.core.CircuitProject project,
            SimulationState previous, boolean tick) {
        return simulator.settle(project, previous, Map.of(), tick, Map.of(), Map.of(), previous.getElapsedTimeMillis(),
            Map.of(), false, false, null);
    }
}
