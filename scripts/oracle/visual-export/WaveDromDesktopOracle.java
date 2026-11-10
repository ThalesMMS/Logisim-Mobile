import com.cburch.logisim.Main;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.log.Model;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * Evolution d58a61e0 WaveDrom export without the chronogram window. The signal model is Evolution's own
 * com.cburch.logisim.gui.log.Model (default selection, clock mode, coarse); after each half cycle it receives
 * propagationCompleted(true, false, true) as the simulator delivers it. The body of the export below is copied
 * verbatim from ChronoPanel's exportWaveDrom (gui/chrono/ChronoPanel.java lines 405-479), because the anonymous
 * class that owns it is only created with a LogFrame window.
 * Usage: WaveDromDesktopOracle file.circ halfCycles
 */
public final class WaveDromDesktopOracle {
    public static void main(String[] args) throws Exception {
        try { run(args); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        project.getOptions().getAttributeSet().setValue(com.cburch.logisim.file.Options.ATTR_SIM_RAND, 0);
        var state = com.cburch.logisim.circuit.CircuitState.createRootState(project, file.getMainCircuit(), Thread.currentThread());
        var propagator = state.getPropagator();
        propagator.propagate();
        var model = new Model(state);
        var halfCycles = Integer.parseInt(args[1]);
        for (var i = 0; i < halfCycles; i++) {
            propagator.toggleClocks();
            propagator.propagate();
            model.propagationCompleted(true, false, true);
        }
        var text = new StringWriter();
        try (var writer = new PrintWriter(text)) {
            writer.println("{");
            writer.println("  \"signal\": [");
            final var t0 = model.getStartTime();
            final var t1 = model.getEndTime();
            final var timeScale = model.getTimeScale();
            final var numSignals = model.getSignalCount();
            for (int i = 0; i < numSignals; i++) {
              final var signal = model.getSignal(i);
              writer.print("    { \"name\": \"" + signal.info.getDisplayName() + "\", \"wave\": \"");
              final var cur = signal.new Iterator(t0);
              final var wave = new StringBuilder();
              final var dataList = new ArrayList<String>();
              String lastWChar = "";
              String lastValStr = "";
              for (long t = t0; t <= t1; t += timeScale) {
                while (cur.value != null && (cur.time + cur.duration <= t)) {
                  if (!cur.advance()) break;
                }
                final var v = (cur.value != null) ? cur.getFormattedValue() : "x";
                String wChar;
                String wData = null;
                if (v.contains("E") || v.contains("x") || v.contains("U")) {
                  wChar = "x";
                } else if (v.equals(signal.getFormattedMinValue())) {
                  wChar = "0";
                } else if (v.equals(signal.getFormattedMaxValue())) {
                  wChar = "1";
                } else {
                  wChar = "2";
                  wData = v;
                }
                if (wChar.equals(lastWChar) && v.equals(lastValStr)) {
                  wave.append(".");
                } else {
                  wave.append(wChar);
                  if (wData != null) dataList.add(wData);
                  lastWChar = wChar;
                  lastValStr = v;
                }
              }
              writer.print(wave.toString() + "\"");
              if (!dataList.isEmpty()) {
                writer.print(", \"data\": [");
                for (int d = 0; d < dataList.size(); d++) {
                  writer.print("\"" + dataList.get(d) + "\"");
                  if (d < dataList.size() - 1) writer.print(", ");
                }
                writer.print("]");
              }
              if (i < numSignals - 1) writer.println(" },"); else writer.println(" }");
            }
            writer.println("  ]");
            writer.println("}");
        }
        System.out.print(text);
        System.err.println("timeScale=" + model.getTimeScale() + " start=" + model.getStartTime() + " end=" + model.getEndTime()
            + " clockMode=" + model.isClockMode() + " fine=" + model.isFine());
        project.getSimulator().shutDown();
        System.exit(0);
    }
}
