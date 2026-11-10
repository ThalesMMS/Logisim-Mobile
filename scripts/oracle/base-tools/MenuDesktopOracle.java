import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.soc.file.ElfHeader;
import com.cburch.logisim.soc.file.ProcessorReadElf;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.MenuExtender;
import com.cburch.logisim.tools.MenuTool;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * Builds Evolution's Menu Tool popups headlessly: MenuTool.MenuComponent plus the component's
 * MenuExtender for each component of menu-tool.circ, and MenuTool.MenuSelection for a multi-selection.
 * Prints one line per popup with the item texts ("|" is a separator). The SoC bus builds a Swing dialog, so this
 * needs a graphics environment (java.awt.headless=false). Each extra "CIRC=ELF" argument loads ELF into the CPU of
 * CIRC through ProcessorReadElf (SocUpMenuProvider's "Read ELF file") and prints that CPU's popup as "<cpu>(loaded)".
 * Usage: MenuDesktopOracle menu-tool.circ [program.circ=program.elf ...]
 */
public final class MenuDesktopOracle {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        try {
            var circuit = file.getMainCircuit();
            // The canvas menu reads the project's circuit state (SocUpMenuProvider needs the CPU's data).
            settle(project, circuit);
            var componentMenu = Class.forName("com.cburch.logisim.tools.MenuTool$MenuComponent")
                .getDeclaredConstructor(Project.class, Circuit.class, Component.class);
            componentMenu.setAccessible(true);
            var selectionMenu = Class.forName("com.cburch.logisim.tools.MenuTool$MenuSelection").getDeclaredConstructor(Project.class);
            selectionMenu.setAccessible(true);
            var lines = new ArrayList<String>();
            for (var component : circuit.getNonWires()) {
                var menu = (JPopupMenu) componentMenu.newInstance(project, circuit, component);
                var extender = (MenuExtender) component.getFeature(MenuExtender.class);
                if (extender != null) extender.configureMenu(menu, project);
                lines.add("component\t" + component.getFactory().getName() + "\t" + items(menu));
            }
            lines.sort(String::compareTo);
            lines.forEach(System.out::println);
            project.setCurrentCircuit(circuit);
            System.out.println("selection(>1)\t-\t" + items((JPopupMenu) selectionMenu.newInstance(project)));
            System.out.println("empty-canvas\t-\t(no menu)");
            for (var index = 1; index < args.length; index++) loaded(args[index].split("=", 2), componentMenu);
        } finally {
            project.getSimulator().shutDown();
            for (var window : Window.getWindows()) window.dispose();
        }
        System.exit(0);
    }

    private static void loaded(String[] paths, java.lang.reflect.Constructor<?> componentMenu) throws Exception {
        var file = new Loader(null).openLogisimFile(new File(paths[0]));
        var project = new Project(file);
        try {
            var circuit = file.getMainCircuit();
            var state = project.getCircuitState();
            settle(project, circuit);
            var cpu = circuit.getNonWires().stream().filter(c -> c.getFactory().getName().equals("Rv32im")
                || c.getFactory().getName().equals("Nios2")).findFirst().orElseThrow();
            var rv32 = cpu.getFactory().getName().equals("Rv32im");
            var reader = new ProcessorReadElf(new File(paths[1]), Instance.getInstanceFor(cpu),
                rv32 ? ElfHeader.EM_RISCV : ElfHeader.EM_INTEL_NIOS2, true);
            if (!reader.canExecute() || !reader.execute(state)) throw new IllegalStateException(reader.getErrorMessage());
            var menu = (JPopupMenu) componentMenu.newInstance(project, circuit, cpu);
            ((MenuExtender) cpu.getFeature(MenuExtender.class)).configureMenu(menu, project);
            System.out.println("component\t" + cpu.getFactory().getName() + "(loaded)\t" + items(menu));
        } finally {
            project.getSimulator().shutDown();
        }
    }

    /** Lets the simulator thread propagate the project's state until every SoC CPU has its data. */
    private static void settle(Project project, Circuit circuit) throws InterruptedException {
        project.getSimulator().nudge();
        var cpus = circuit.getNonWires().stream().filter(c -> c.getFactory().getName().equals("Rv32im")
            || c.getFactory().getName().equals("Nios2")).toList();
        for (var attempt = 0; attempt < 200; attempt++) {
            var state = project.getCircuitState();
            if (cpus.stream().allMatch(c -> state.getData(c) != null)) return;
            Thread.sleep(25);
        }
        throw new IllegalStateException("The SoC CPUs did not initialize.");
    }

    private static String items(JPopupMenu menu) {
        List<String> parts = new ArrayList<>();
        for (var element : menu.getComponents()) {
            if (element instanceof JMenuItem item) parts.add(item.getText() + (item.isEnabled() ? "" : " (disabled)"));
            else parts.add("|");
        }
        return String.join(", ", parts);
    }
}
