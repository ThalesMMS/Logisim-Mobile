import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.util.SyntaxChecker;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Evolution d58a61e0 side of #321. For each {@code mobile-*.circ} in DIRECTORY it prints the circuit names and the
 * labels Mobile wrote, opens the file twice in Evolution (its XmlReader renames invalid names with a random suffix,
 * so two opens differ when it renames anything), re-saves it and opens the re-saved file. It also checks
 * {@code names.tsv} against XmlReader.generateValidVHDLLabel, XmlReader.labelVHDLInvalid and
 * SyntaxChecker.getErrorMessage(value, "VHDL"). Every {@code result} line must end with true; the exit status is 1
 * otherwise.
 */
public final class EvolutionNamesOracle {
    private static boolean ok = true;

    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        File[] files = directory.listFiles((dir, name) -> name.startsWith("mobile-") && name.endsWith(".circ"));
        Arrays.sort(files);
        for (File file : files) {
            System.out.println("file\t" + file.getName());
            List<String> written = written(file);
            List<String> first = loaded(file);
            List<String> second = loaded(file);
            File resaved = Files.createTempFile("evolution-names", ".circ").toFile();
            LogisimFile reopened = new Loader(null).openLogisimFile(file);
            try (FileOutputStream out = new FileOutputStream(resaved)) {
                reopened.write(out, reopened.getLoader(), resaved);
            }
            List<String> again = loaded(resaved);
            resaved.delete();
            for (String line : written) System.out.println("name\t" + file.getName() + "\t" + line);
            for (String line : first) if (!written.contains(line)) System.out.println("difference\t" + file.getName() + "\tevolution-only\t" + line);
            for (String line : written) if (!first.contains(line)) System.out.println("difference\t" + file.getName() + "\tmobile-only\t" + line);
            result(file.getName(), "names-unchanged-on-open", written.equals(first));
            result(file.getName(), "same-names-on-second-open", first.equals(second));
            result(file.getName(), "names-unchanged-after-resave", first.equals(again));
        }
        // XmlReader is package-private; its static helpers are public.
        Class<?> reader = Class.forName("com.cburch.logisim.file.XmlReader");
        Method generate = reader.getMethod("generateValidVHDLLabel", String.class, String.class);
        Method invalid = reader.getMethod("labelVHDLInvalid", String.class);
        generate.setAccessible(true);
        invalid.setAccessible(true);
        for (String line : Files.readAllLines(new File(directory, "names.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t", -1);
            String sample = unescape(parts[0]);
            String id = parts[0];
            result(id, "generateValidVHDLLabel", generate.invoke(null, sample, parts[1]).equals(unescape(parts[2])));
            result(id, "SyntaxChecker-accepts", Boolean.toString(SyntaxChecker.getErrorMessage(sample, "VHDL") == null).equals(parts[3]));
            result(id, "labelVHDLInvalid", invalid.invoke(null, sample).toString().equals(parts[4]));
        }
        System.exit(ok ? 0 : 1);
    }

    private static void result(String subject, String check, boolean value) {
        ok &= value;
        System.out.println("result\t" + subject + "\t" + check + "\t" + value);
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                out.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else out.append(c);
        }
        return out.toString();
    }

    /** Circuit names and component labels as written in the file, sorted. */
    private static List<String> written(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
        List<String> lines = new ArrayList<>();
        for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element circuit) || !circuit.getTagName().equals("circuit")) continue;
            lines.add("circuit\t" + circuit.getAttribute("name"));
            for (Node n = circuit.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element comp) || !comp.getTagName().equals("comp")) continue;
                String label = "";
                for (Node a = comp.getFirstChild(); a != null; a = a.getNextSibling()) {
                    if (a instanceof Element attr && attr.getTagName().equals("a") && attr.getAttribute("name").equals("label")) {
                        label = attr.hasAttribute("val") ? attr.getAttribute("val") : attr.getTextContent();
                    }
                }
                if (!label.isEmpty()) lines.add("label\t" + circuit.getAttribute("name") + "\t" + comp.getAttribute("loc") + "\t" + label);
            }
        }
        lines.sort(null);
        return lines;
    }

    /** The same lines, sorted, as Evolution sees them once it has opened the file. */
    private static List<String> loaded(File file) throws Exception {
        LogisimFile logisim = new Loader(null).openLogisimFile(file);
        List<String> lines = new ArrayList<>();
        for (Circuit circuit : logisim.getCircuits()) {
            lines.add("circuit\t" + circuit.getName());
            for (Component component : circuit.getNonWires()) {
                if (!component.getAttributeSet().containsAttribute(StdAttr.LABEL)) continue;
                String label = component.getAttributeSet().getValue(StdAttr.LABEL);
                if (label != null && !label.isEmpty()) lines.add("label\t" + circuit.getName() + "\t" + component.getLocation() + "\t" + label);
            }
        }
        lines.sort(null);
        return lines;
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
