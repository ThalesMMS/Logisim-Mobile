import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.test.TestBench;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.Instance;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Runs the branches of Evolution's Startup.run() for -w, -b and -n without the Swing initialisation
 * that precedes them (which fails under java.awt.headless=true). Each branch calls the same Evolution
 * methods Startup.run() calls, in the same order, and exits with the same code.
 *
 *   vector FILE CIRCUIT VECTOR  ->  ProjectActions.doOpenNoWindow + Project.doTestVector; Startup exits 0
 *                                   (the failure count is ignored there); "failed=N" is added on stderr
 *   bench FILE                  ->  TestBench.startTestBench() minus the QuestaSim start; prints and exits
 *                                   as Startup (0 / -1)
 *   convert IN OUT              ->  Loader.openLogisimFile(IN) + ProjectActions.doSave(OUT)
 *                                   (Startup uses doOpen, which also opens a window; the file is identical)
 */
public final class EvolutionAutomationOracle {
  public static void main(String[] args) throws Exception {
    // Startup.parseArgs sets this flag only for -t and -f. With it, messages that the GUI shows in a
    // dialog (the original-Logisim compatibility notice) go to the log instead; nothing else changes.
    com.cburch.logisim.Main.headless = true;
    switch (args[0]) {
      case "vector" -> {
        Project proj = ProjectActions.doOpenNoWindow(null, new File(args[1]));
        int failed = proj.doTestVector(args[3], args[2]);
        System.err.println("failed=" + failed);
        System.exit(0);
      }
      case "bench" -> {
        // TestBench.startTestBench() without its VhdlSimulatorTop.enable() call: that call launches
        // QuestaSim's vsim (asking for its folder in a dialog when unset), which this host lacks and the
        // fixture does not use. The remaining steps are the same calls, in the same order.
        TestBench bench = new TestBench(args[1], null, new HashMap<>());
        Project proj = (Project) field(bench, "proj");
        Circuit circuit = proj.getLogisimFile().getCircuit("logisim_test_verif");
        proj.setCurrentCircuit(circuit);
        boolean passed = false;
        if (circuit != null) {
          Value[] val = {Value.createKnown(1, 1), Value.createKnown(1, 1)};
          proj.getSimulator().setAutoPropagation(true);
          Method search = TestBench.class.getDeclaredMethod("searchMatchingPins", Circuit.class);
          search.setAccessible(true);
          if ((Boolean) search.invoke(bench, circuit)) {
            passed = circuit.doTestBench(proj, (Instance[]) field(bench, "pinsOutput"), val);
          }
        }
        if (passed) {
          System.out.println("Test bench pass\n");
          System.exit(0);
        } else {
          System.out.println("Test bench fail\n");
          System.exit(-1);
        }
      }
      case "convert" -> {
        Loader loader = new Loader(null);
        Project proj = new Project(loader.openLogisimFile(new File(args[1]), new HashMap<>()));
        System.exit(ProjectActions.doSave(proj, new File(args[2])) ? 0 : -1);
      }
      default -> throw new IllegalArgumentException(args[0]);
    }
  }

  private static Object field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }
}
