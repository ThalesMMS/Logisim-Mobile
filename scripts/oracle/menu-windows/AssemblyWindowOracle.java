import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.menu.AssemblyWindow;
import com.cburch.logisim.proj.Project;
import dev.logisim.mobile.core.AssemblyListing;
import dev.logisim.mobile.core.AssemblyListingKt;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.CircuitProject;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.SimulationState;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Drives Evolution's {@code AssemblyWindow} (Simulate > Assembly viewer) and Mobile's
 * {@code AssemblyListing} over the same circuit, listing and clock ticks, and compares the
 * highlighted line for every labeled register after every tick.
 *
 * <p>Usage: {@code AssemblyWindowOracle circuit.circ listing.lss ticks}. The window is never shown.
 */
public final class AssemblyWindowOracle {
    public static void main(String[] args) {
        int status;
        try {
            status = run(args);
        } catch (Throwable error) {
            error.printStackTrace();
            status = 2;
        }
        System.exit(status);
    }

    private static int run(String[] args) throws Exception {
        File circuit = new File(args[0]);
        File listingFile = new File(args[1]);
        int ticks = Integer.parseInt(args[2]);

        // Loader warnings go to the log instead of a modal dialog; the window itself is still built.
        com.cburch.logisim.Main.headless = true;
        // Evolution: its own window with the listing loaded as File > Open .lss does.
        Project project = new Project(new Loader(null).openLogisimFile(circuit));
        AssemblyWindow[] holder = new AssemblyWindow[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AssemblyWindow(project));
        AssemblyWindow window = holder[0];
        JEditorPane document = field(window, "document");
        @SuppressWarnings("unchecked") JComboBox<Object> combo = field(window, "combo");
        JLabel status = field(window, "status");
        Method update = AssemblyWindow.class.getDeclaredMethod("updateHighlightLine");
        update.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                document.setPage(listingFile.toURI().toURL());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            window.setVisible(false); // fills the register list, as opening the window does
        });
        List<String> evolutionLabels = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) evolutionLabels.add(combo.getItemAt(i).toString());

        // Mobile: the core listing and register list used by AssemblyViewerTool.
        Object imported = CircCodec.INSTANCE.readProject(Files.readString(circuit.toPath()), ComponentCatalog.INSTANCE.getRegistry());
        CircuitProject mobileProject = ((CircProjectImportResult.Success) imported).getProject();
        List<CircuitComponent> registers = AssemblyListingKt.assemblyRegisters(mobileProject.getMainCircuit());
        List<String> mobileLabels = registers.stream().map(CircuitComponent::getLabel).toList();
        AssemblyListing listing = AssemblyListing.Companion.read(Files.readAllBytes(listingFile.toPath()));
        Method lineAt = null;
        for (Method method : AssemblyListing.class.getMethods()) if (method.getName().startsWith("lineAt")) lineAt = method;
        CircuitSimulator simulator = new CircuitSimulator();
        SimulationState mobile = simulator.settle(mobileProject);

        System.out.println("REGISTERS evolution=" + evolutionLabels + " mobile=" + mobileLabels);
        int same = 0, kept = 0, differ = 0;
        if (!evolutionLabels.equals(mobileLabels)) differ++;
        // The window reads the state it captured when built. Give it one that this thread propagates.
        var evolutionState = com.cburch.logisim.circuit.CircuitState.createRootState(
                project, project.getCurrentCircuit(), Thread.currentThread());
        Field captured = AssemblyWindow.class.getDeclaredField("curCircuitState");
        captured.setAccessible(true);
        captured.set(null, evolutionState);
        evolutionState.getPropagator().propagate();
        String[] lines = document.getText().split("\n", -1);
        for (int tick = 0; tick <= ticks; tick++) {
            for (int index = 0; index < evolutionLabels.size(); index++) {
                String label = evolutionLabels.get(index);
                combo.setSelectedIndex(index);
                status.setText("");
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        update.invoke(window);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                int line = highlightedLine(document);
                // Evolution reports a missing line in the status bar and leaves an undefined value alone.
                String evolution = evolutionValue(project, evolutionState, label) == null ? "kept"
                        : !status.getText().isEmpty() ? "missing" : "line " + line;

                CircuitComponent register = registers.stream().filter(r -> r.getLabel().equals(label)).findFirst().orElse(null);
                Object address = register == null ? null : AssemblyListingKt.assemblyRegisterAddress(mobile, register.getId());
                Integer mobileLine = (Integer) lineAt.invoke(listing, address);
                String mobileResult = address == null ? "undefined" : mobileLine == null ? "missing" : "line " + mobileLine;
                String value = evolutionValue(project, evolutionState, label);
                String verdict;
                if (evolution.equals(mobileResult)) { verdict = "SAME"; same++; }
                else if (evolution.equals("kept") && mobileResult.equals("undefined")) { verdict = "KEPT"; kept++; }
                else { verdict = "DIFF"; differ++; }
                System.out.println(verdict + " tick=" + tick + " register=" + label + " value=" + value
                        + " evolution=" + evolution + " mobile=" + mobileResult
                        + (evolution.startsWith("line ") ? " text=" + lines[line].strip().split("\t")[0] : ""));
            }
            if (tick < ticks) {
                evolutionState.getPropagator().toggleClocks();
                evolutionState.getPropagator().propagate();
                mobile = simulator.settle(mobileProject, mobile, Map.of(), true, Map.of());
            }
        }
        System.out.println("SUMMARY same=" + same + " undefined-kept=" + kept + " differences=" + differ);
        return differ == 0 ? 0 : 1;
    }

    private static String evolutionValue(Project project, com.cburch.logisim.circuit.CircuitState state, String label) {
        for (var component : project.getCurrentCircuit().getNonWires()) {
            if (label.equals(component.getAttributeSet().getValue(com.cburch.logisim.instance.StdAttr.LABEL))
                    && component.getFactory() instanceof com.cburch.logisim.std.memory.Register) {
                var value = state.getInstanceState(component).getPortValue(com.cburch.logisim.std.memory.Register.OUT);
                return value.isFullyDefined() ? value.toHexString() : null;
            }
        }
        return null;
    }

    /** Line of the gray highlight Evolution adds at the match, or -1 before the first match. */
    private static int highlightedLine(JEditorPane document) throws Exception {
        var highlights = document.getHighlighter().getHighlights();
        if (highlights.length == 0) return -1;
        String before = document.getText(0, highlights[0].getStartOffset());
        return (int) before.chars().filter(c -> c == '\n').count();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
}
