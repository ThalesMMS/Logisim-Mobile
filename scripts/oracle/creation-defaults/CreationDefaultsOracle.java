import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Evolution d58a61e0 side of #313. With in-memory preferences (the user's real Java preferences are
 * never touched) it applies one creation-defaults profile, builds the scenario of the issue through
 * the same paths as the GUI (File > New template, AddTool attributes, new Circuit) and saves
 * {@code evolution-<profile>.circ}. It then opens that file and Mobile's {@code mobile-<profile>.circ},
 * re-saves Mobile's file, and prints the libraries, toolbar, mouse mappings, circuit attributes and component
 * attributes each file has once Evolution has loaded it. The exit status is 1 when Mobile's file loads differently.
 *
 * Usage: CreationDefaultsOracle DIRECTORY PROFILE, where PROFILE is
 * {@code <classic|evolution|logisim_evolution>-<newIo true|false>-<fixed true|false>-<removeUnused true|false>},
 * or {@code empty} for a new project from the empty template (#339).
 */
public final class CreationDefaultsOracle {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        String profile = args[1];
        // "empty" (#339): Preferences > Template > Empty template. Template.createEmpty() has no libraries or toolbar, so the
        // scenario is File > New and Project > Add Circuit only.
        boolean empty = profile.equals("empty");
        if (empty) {
            AppPreferences.setTemplateType(AppPreferences.TEMPLATE_EMPTY);
        } else {
            String[] parts = profile.split("-");
            AppPreferences.DefaultAppearance.set(parts[0]);
            AppPreferences.NEW_INPUT_OUTPUT_SHAPES.setBoolean(Boolean.parseBoolean(parts[1]));
            AppPreferences.NAMED_CIRCUIT_BOXES_FIXED_SIZE.setBoolean(Boolean.parseBoolean(parts[2]));
            AppPreferences.REMOVE_UNUSED_LIBRARIES.setBoolean(Boolean.parseBoolean(parts[3]));
        }

        LogisimFile file = ProjectActions.createNewFile(null);
        Project project = new Project(file);
        if (!empty) {
            Circuit main = file.getMainCircuit();
            CircuitMutation mutation = new CircuitMutation(main);
            mutation.add(place(file, "D Flip-Flop", 300, 200));
            mutation.add(place(file, "Pin", 100, 200));
            mutation.execute();
        }
        Circuit sub = new Circuit("sub", file, project);
        file.addCircuit(sub);
        File evolution = new File(directory, "evolution-" + profile + ".circ");
        try (FileOutputStream out = new FileOutputStream(evolution)) {
            file.write(out, file.getLoader(), evolution);
        }

        File mobile = new File(directory, "mobile-" + profile + ".circ");
        List<String> expected = dump(evolution);
        List<String> observed = dump(mobile);
        LogisimFile reopened = new Loader(null).openLogisimFile(mobile);
        File resaved = Files.createTempFile("mobile-resaved", ".circ").toFile();
        try (FileOutputStream out = new FileOutputStream(resaved)) {
            reopened.write(out, reopened.getLoader(), resaved);
        }
        List<String> resavedDump = dump(resaved);
        resaved.delete();
        for (String line : expected) System.out.println("evolution\t" + line);
        for (String line : observed) System.out.println("mobile\t" + line);
        // The circuits and components must load identically (#313), and so must the template parts of a new
        // project (#324): libraries, the main circuit's simulationFrequency, the toolbar and the mouse mappings.
        // Evolution's own XmlWriter must keep the libraries of Mobile's project as they loaded (#323).
        boolean same = scoped(expected).equals(scoped(observed));
        boolean template = template(expected).equals(template(observed));
        boolean stable = observed.equals(resavedDump);
        for (String line : expected) if (!observed.contains(line)) System.out.println("difference\tevolution-only\t" + line);
        for (String line : observed) if (!expected.contains(line)) System.out.println("difference\tmobile-only\t" + line);
        for (String line : resavedDump) if (!observed.contains(line)) System.out.println("difference\tresave-only\t" + line);
        System.out.println("result\tcircuits-and-components-match\t" + same);
        System.out.println("result\ttemplate-matches\t" + template);
        System.out.println("result\tevolution-resave-unchanged\t" + stable);
        System.exit(same && template && stable ? 0 : 1);
    }

    /**
     * Places a component with the toolbar tool, as a user does from the default template. XmlReader gives
     * toolbar tools the preferred appearances; a library tool built before the preference changed keeps the
     * factory default (PinAttributes starts as classic), so the toolbar is the path that applies the preferences.
     */
    private static Component place(LogisimFile file, String name, int x, int y) {
        for (Tool tool : file.getOptions().getToolbarData().getContents()) {
            if (tool instanceof AddTool add && add.getFactory().getName().equals(name)) {
                AttributeSet attrs = (AttributeSet) add.getAttributeSet().clone();
                return add.getFactory().createComponent(Location.create(x, y, true), attrs);
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static List<String> template(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("libraries\t") || line.startsWith("toolbar\t") || line.startsWith("mapping\t")) result.add(line);
            java.util.regex.Matcher frequency = java.util.regex.Pattern.compile("simulationFrequency=[^,}]*").matcher(line);
            if (line.startsWith("circuit\t") && frequency.find()) result.add(line.split("\t")[1] + "\t" + frequency.group());
        }
        return result;
    }

    private static List<String> scoped(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("libraries\t") && !line.startsWith("toolbar\t") && !line.startsWith("mapping\t")) {
                result.add(line.replaceAll(", simulationFrequency=[^,}]*", ""));
            }
        }
        return result;
    }

    /** What Evolution sees after loading the file; ordering and XML formatting do not matter. */
    private static List<String> dump(File source) throws Exception {
        Loader loader = new Loader(null);
        LogisimFile file = loader.openLogisimFile(source);
        List<String> lines = new ArrayList<>();
        List<String> libraries = new ArrayList<>();
        for (Library library : file.getLibraries()) libraries.add(loader.getDescriptor(library));
        libraries.sort(null);
        lines.add("libraries\t" + String.join(",", libraries));
        lines.add("main\t" + file.getMainCircuit().getName());
        int index = 0;
        for (Tool tool : file.getOptions().getToolbarData().getContents()) {
            lines.add("toolbar\t" + (index++) + "\t" + (tool == null ? "separator"
                    : tool.getName() + "\t" + (tool.getAttributeSet() == null ? "{}" : attributes(tool.getAttributeSet()))));
        }
        List<String> mappings = new ArrayList<>();
        for (var entry : file.getOptions().getMouseMappings().getMappings().entrySet()) {
            mappings.add("mapping\t" + com.cburch.logisim.util.InputEventUtil.toString(entry.getKey()) + "\t" + entry.getValue().getName());
        }
        mappings.sort(null);
        lines.addAll(mappings);
        for (Circuit circuit : file.getCircuits()) {
            lines.add("circuit\t" + circuit.getName() + "\t" + attributes(circuit.getStaticAttributes()));
            List<String> components = new ArrayList<>();
            for (Component component : circuit.getNonWires()) {
                components.add("component\t" + circuit.getName() + "\t" + component.getFactory().getName() + "\t"
                        + component.getLocation() + "\t" + attributes(component.getAttributeSet()));
            }
            components.sort(null);
            lines.addAll(components);
        }
        return lines;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String attributes(AttributeSet attrs) {
        Map<String, String> values = new TreeMap<>();
        for (Attribute attribute : attrs.getAttributes()) {
            Object value = attrs.getValue(attribute);
            values.put(attribute.getName(), value == null ? "null" : attribute.toStandardString(value));
        }
        return values.toString();
    }

    public static final class MemoryPreferencesFactory implements PreferencesFactory {
        private final Preferences user = new MemoryPreferences(null, "");
        private final Preferences system = new MemoryPreferences(null, "");
        @Override public Preferences userRoot() { return user; }
        @Override public Preferences systemRoot() { return system; }
    }

    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); children.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
        @Override protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n)); }
        @Override protected void syncSpi() {}
        @Override protected void flushSpi() {}
    }
}
