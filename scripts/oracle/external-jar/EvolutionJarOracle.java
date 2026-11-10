import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/**
 * Opens a project with Evolution's own Loader, which resolves its "jar#path#class" library
 * through Evolution's JAR class loader (no companion code involved), drives the labeled input
 * pins frame by frame and prints one line per frame: "frame\tLABEL=bits ..." for the outputs,
 * sorted by label, with bits written most significant first as 0, 1, ? and E.
 * Usage: java -cp evolution.jar EvolutionJarOracle project.circ frames.tsv
 * (frames.tsv: one line per frame, "LABEL=bits" assignments separated by tabs).
 */
public final class EvolutionJarOracle {
  public static void main(String[] args) throws Exception {
    var loader = new Loader(null) {
      @Override public void showError(String message) { throw new IllegalStateException(message); }
    };
    var file = loader.openLogisimFile(new File(args[0]));
    var circuit = file.getMainCircuit();
    var project = new Project(file);
    var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
    var inputs = new LinkedHashMap<String, Instance>();
    var outputs = new TreeMap<String, Instance>();
    for (var component : circuit.getNonWires()) {
      if (component.getFactory() != Pin.FACTORY) continue;
      var pin = Instance.getInstanceFor(component);
      (Pin.FACTORY.isInputPin(pin) ? inputs : outputs).put(pin.getAttributeValue(StdAttr.LABEL), pin);
    }
    var index = 0;
    for (var line : Files.readAllLines(Path.of(args[1]))) {
      if (line.isBlank()) continue;
      for (var assignment : line.split("\t")) {
        var parts = assignment.split("=", 2);
        var pin = inputs.get(parts[0]);
        Pin.FACTORY.driveInputPin(state.getInstanceState(pin), parse(parts[1]));
        state.markComponentAsDirty(pin.getComponent());
      }
      state.getPropagator().propagate();
      var out = new StringBuilder().append(index++);
      for (var entry : outputs.entrySet()) {
        out.append('\t').append(entry.getKey()).append('=')
            .append(bits(Pin.FACTORY.getValue(state.getInstanceState(entry.getValue()))));
      }
      System.out.println(out);
    }
    // The project's simulator thread is not a daemon.
    System.exit(0);
  }

  private static Value parse(String bits) {
    var values = new Value[bits.length()];
    for (int i = 0; i < bits.length(); i++) {
      values[i] = switch (bits.charAt(bits.length() - i - 1)) {
        case '0' -> Value.FALSE;
        case '1' -> Value.TRUE;
        case 'E' -> Value.ERROR;
        default -> Value.UNKNOWN;
      };
    }
    return Value.create(values);
  }

  private static String bits(Value value) {
    var text = new StringBuilder();
    for (int bit = value.getWidth() - 1; bit >= 0; bit--) {
      var signal = value.get(bit);
      text.append(signal == Value.TRUE ? '1' : signal == Value.FALSE ? '0' : signal == Value.ERROR ? 'E' : '?');
    }
    return text.toString();
  }
}
