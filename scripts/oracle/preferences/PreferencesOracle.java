import com.cburch.logisim.circuit.RadixOption;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.fpga.designrulecheck.SimpleDrcContainer;
import com.cburch.logisim.fpga.gui.FpgaReportTabbedPane;
import com.cburch.logisim.fpga.gui.Reporter;
import com.cburch.logisim.fpga.hdlgenerator.Vhdl;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.prefs.PrefMonitor;
import com.cburch.logisim.prefs.PrefMonitorKeyStroke;
import java.awt.Color;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import javax.swing.JComboBox;
import javax.swing.KeyStroke;

/**
 * Preferences campaign (#236, #243): what Evolution d58a61e0 does with each user preference, read from the running
 * JAR with in-memory preferences. Rows are tab-separated: section, key, field, value. The Mobile side
 * (EditorPreferencesEvolutionTest) writes the same rows for its own preferences; compare.py joins them per matrix row.
 *
 * Usage: java -Djava.awt.headless=true -Djava.util.prefs.PreferencesFactory=PreferencesOracle$MemoryPreferencesFactory
 *            -cp classes:EVOLUTION_JAR PreferencesOracle FIXTURES_DIR [all|gate-shape]
 */
public final class PreferencesOracle {
    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static final Map<Object, String> FIELD_NAMES = new IdentityHashMap<>();
    private static final Map<String, PrefMonitor<?>> MONITORS = new TreeMap<>();

    private PreferencesOracle() {}

    private static void row(String section, String key, String field, Object value) {
        OUT.println(section + "\t" + key + "\t" + field + "\t" + String.valueOf(value).replace('\t', ' ').replace('\n', ' '));
    }

    public static void main(String[] args) throws Exception {
        File fixtures = new File(args[0]);
        String mode = args.length > 1 ? args[1] : "all";
        if (mode.equals("gate-shape")) {
            // AppPreferences.getDefaultGateShape() reads the JVM locale (-Duser.language) when the class loads.
            row("locale-default", Locale.getDefault().getLanguage(), "GATE_SHAPE", AppPreferences.GATE_SHAPE.get());
            OUT.flush();
            System.exit(0);
        }
        Locale.setDefault(Locale.ENGLISH);
        collectMonitors();
        dumpMonitors();
        localesAndTemplate();
        panels();
        valueCharacters();
        radixes();
        vhdlKeywords();
        pinMarkers();
        boards();
        drc(fixtures);
        OUT.flush();
        System.exit(0); // Loaded projects leave Evolution's simulator threads running.
    }

    /** PrefMonitor.get() returns a value cached by an asynchronous preference listener; wait for a set() to land. */
    private static <T> void set(PrefMonitor<T> monitor, T value) throws InterruptedException {
        monitor.set(value);
        long deadline = System.currentTimeMillis() + 5000;
        while (!value.equals(monitor.get()) && System.currentTimeMillis() < deadline) Thread.sleep(5);
        if (!value.equals(monitor.get())) throw new IllegalStateException(monitor.getIdentifier() + " did not change to " + value);
    }

    private static void collectMonitors() throws IllegalAccessException {
        for (Field field : AppPreferences.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !PrefMonitor.class.isAssignableFrom(field.getType())) continue;
            PrefMonitor<?> monitor = (PrefMonitor<?>) field.get(null);
            FIELD_NAMES.put(monitor, field.getName());
            MONITORS.put(field.getName(), monitor);
        }
    }

    private static Object declared(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Look in the superclass.
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }
        return null;
    }

    private static String color(int value) {
        return String.format("%06X", value & 0xFFFFFF);
    }

    private static String keyName(int code) {
        for (Field field : KeyEvent.class.getFields()) {
            try {
                if (field.getName().startsWith("VK_") && field.getType() == int.class && field.getInt(null) == code) {
                    return field.getName().substring(3);
                }
            } catch (IllegalAccessException ignored) {
                // Public constant.
            }
        }
        return "code" + code;
    }

    /** "menu" is Evolution's hotkeyMenuMask (Ctrl, or Command on macOS); Mobile's primary modifier. */
    static String keyStroke(KeyStroke stroke) {
        if (stroke == null) return "none";
        int modifiers = stroke.getModifiers();
        List<String> parts = new ArrayList<>();
        if ((modifiers & AppPreferences.hotkeyMenuMask) != 0) parts.add("menu");
        int rest = modifiers & ~AppPreferences.hotkeyMenuMask;
        if ((rest & InputEvent.CTRL_DOWN_MASK) != 0) parts.add("ctrl");
        if ((rest & InputEvent.ALT_DOWN_MASK) != 0) parts.add("alt");
        if ((rest & InputEvent.META_DOWN_MASK) != 0) parts.add("meta");
        if ((rest & InputEvent.SHIFT_DOWN_MASK) != 0) parts.add("shift");
        parts.add(keyName(stroke.getKeyCode()));
        return String.join("+", parts);
    }

    private static String format(String name, Object value) {
        if (value instanceof Integer number && name.endsWith("_COLOR")) return color(number);
        if (value instanceof String text) {
            String home = System.getProperty("user.home");
            return home != null && !home.isEmpty() ? text.replace(home, "~") : text;
        }
        return String.valueOf(value);
    }

    private static void dumpMonitors() {
        for (Map.Entry<String, PrefMonitor<?>> entry : MONITORS.entrySet()) {
            String name = entry.getKey();
            PrefMonitor<?> monitor = entry.getValue();
            row("pref", name, "type", monitor.getClass().getSimpleName());
            if (monitor instanceof PrefMonitorKeyStroke keys) {
                List<String> strokes = new ArrayList<>();
                List<KeyStroke> list = keys.getList();
                if (list != null) for (KeyStroke stroke : list) strokes.add(keyStroke(stroke));
                row("pref", name, "default", strokes.isEmpty() ? "none" : String.join(" | ", strokes));
                continue;
            }
            Object value = monitor.get();
            if (monitor.getClass().getSimpleName().equals("PrefMonitorString") && value instanceof String text && name.endsWith("_CHAR")) {
                value = text.isEmpty() ? "" : text.substring(0, 1);
            }
            row("pref", name, "default", format(name, value));
            Object dark = declared(monitor, "darkDefault");
            if (dark instanceof Integer darkColor) row("pref", name, "dark", color(darkColor));
            Object options = declared(monitor, "opts");
            if (options instanceof String[] values) row("pref", name, "options", String.join(",", values));
        }
    }

    private static void localesAndTemplate() {
        List<String> tags = new ArrayList<>();
        for (Locale locale : com.cburch.logisim.gui.Strings.S.getLocaleOptions()) {
            tags.add(locale.getCountry().isEmpty() ? locale.getLanguage() : locale.getLanguage() + "-" + locale.getCountry());
        }
        row("pref", "LOCALE", "options", String.join(",", tags));
        // TemplateOptions: AppPreferences.TEMPLATE_EMPTY, TEMPLATE_PLAIN, TEMPLATE_CUSTOM.
        String[] templates = {"empty", "plain", "custom"};
        row("template", "TEMPLATE_TYPE", "default", templates[AppPreferences.getTemplateType()]);
        row("template", "TEMPLATE_TYPE", "options", String.join(",", templates));
    }

    /** Which preferences each Preferences tab edits, read from the panel objects Evolution builds. */
    private static void panels() {
        String[] classes = {
            "com.cburch.logisim.gui.prefs.TemplateOptions", "com.cburch.logisim.gui.prefs.IntlOptions",
            "com.cburch.logisim.gui.prefs.WindowOptions", "com.cburch.logisim.gui.prefs.LayoutOptions",
            "com.cburch.logisim.gui.prefs.SimOptions", "com.cburch.logisim.fpga.prefs.FpgaOptions",
            "com.cburch.logisim.gui.prefs.HotkeyOptions",
        };
        for (String className : classes) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            try {
                Class<?> type = Class.forName(className);
                var constructor = type.getConstructors()[0];
                constructor.setAccessible(true);
                Object panel = constructor.newInstance((Object) null);
                TreeSet<String> members = new TreeSet<>();
                scan(panel, members, new IdentityHashMap<>(), 0);
                row("panel", simple, "members", String.join(",", members));
                if (simple.equals("SimOptions")) symbolChoices(panel);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                row("panel", simple, "error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }

    private static void scan(Object target, TreeSet<String> members, Map<Object, Boolean> seen, int depth) {
        if (target == null || depth > 3 || seen.put(target, Boolean.TRUE) != null) return;
        String name = FIELD_NAMES.get(target);
        if (name != null) {
            members.add(name);
            return;
        }
        if (target.getClass().isArray()) {
            if (target.getClass().getComponentType().isPrimitive()) return;
            for (int i = 0; i < Array.getLength(target); i++) scan(Array.get(target, i), members, seen, depth + 1);
            return;
        }
        if (target instanceof Collection<?> collection) {
            for (Object item : collection) scan(item, members, seen, depth + 1);
            return;
        }
        if (target instanceof Map<?, ?> map) {
            for (Object item : map.values()) scan(item, members, seen, depth + 1);
            return;
        }
        String packageName = target.getClass().getName();
        if (!packageName.startsWith("com.cburch.logisim.gui.prefs") && !packageName.startsWith("com.cburch.logisim.fpga.prefs")) return;
        for (Class<?> type = target.getClass(); type != null && type.getName().startsWith("com.cburch"); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    scan(field.get(target), members, seen, depth + 1);
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Module-protected JDK fields are not preferences.
                }
            }
        }
    }

    private static final Map<String, String> CHOICES = new HashMap<>();

    private static void symbolChoices(Object panel) throws IllegalAccessException {
        for (Field field : panel.getClass().getDeclaredFields()) {
            if (!JComboBox.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            JComboBox<?> box = (JComboBox<?>) field.get(panel);
            Object pref = declared(box, "myPref");
            if (pref == null || !FIELD_NAMES.containsKey(pref)) continue;
            StringBuilder choices = new StringBuilder();
            for (int i = 0; i < box.getItemCount(); i++) choices.append(box.getItemAt(i));
            CHOICES.put(FIELD_NAMES.get(pref), choices.toString());
            row("pref", FIELD_NAMES.get(pref), "options", choices);
        }
    }

    /**
     * Value characters: SimOptions' preference listener copies each choice into Value.*CHAR, which every value
     * display uses. The rows show a 4-bit 1010, an unknown and an error vector, and the analysis don't-care.
     */
    private static void valueCharacters() throws Exception {
        String[] names = {"TRUE_CHAR", "FALSE_CHAR", "UNKNOWN_CHAR", "ERROR_CHAR", "DONTCARE_CHAR"};
        String[] fields = {"TRUECHAR", "FALSECHAR", "UNKNOWNCHAR", "ERRORCHAR", "DONTCARECHAR"};
        for (int i = 0; i < names.length; i++) {
            @SuppressWarnings("unchecked") PrefMonitor<String> monitor = (PrefMonitor<String>) MONITORS.get(names[i]);
            String original = monitor.get();
            String choices = CHOICES.getOrDefault(names[i], "");
            Field target = Value.class.getField(fields[i]);
            for (char choice : choices.toCharArray()) {
                monitor.set(choice + " ");
                long deadline = System.currentTimeMillis() + 5000;
                while (target.getChar(null) != choice && System.currentTimeMillis() < deadline) Thread.sleep(10);
                if (target.getChar(null) != choice) {
                    row("char", names[i], String.valueOf(choice), "listener-timeout");
                    continue;
                }
                String shown = Value.createKnown(BitWidth.create(4), 0b1010).toBinaryString()
                    + " " + Value.createUnknown(BitWidth.create(4)).toBinaryString()
                    + " " + Value.createError(BitWidth.create(4)).toBinaryString()
                    + " " + Value.DONTCARECHAR;
                row("char", names[i], String.valueOf(choice), shown);
            }
            monitor.set(original);
            long deadline = System.currentTimeMillis() + 5000;
            while (target.getChar(null) != original.charAt(0) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        }
    }

    /** Wire Poke readings: RadixOption.toString for the saved option names of POKE_WIRE_RADIX1/2. */
    private static void radixes() {
        Map<String, Value> cases = new java.util.LinkedHashMap<>();
        cases.put("w1=1", Value.createKnown(BitWidth.create(1), 1));
        cases.put("w8=A5", Value.createKnown(BitWidth.create(8), 0xA5));
        cases.put("w8=05", Value.createKnown(BitWidth.create(8), 0x05));
        cases.put("w12=F80", Value.createKnown(BitWidth.create(12), 0xF80));
        cases.put("w32=3FC00000", Value.createKnown(BitWidth.create(32), 0x3FC00000L));
        cases.put("w64=C000000000000000", Value.createKnown(BitWidth.create(64), 0xC000000000000000L));
        cases.put("w4=1x0x", Value.createUnsafe(4, 0, 0b0101, 0b1000));
        cases.put("w4=error", Value.createError(BitWidth.create(4)));
        for (RadixOption option : RadixOption.OPTIONS) {
            for (Map.Entry<String, Value> entry : cases.entrySet()) {
                row("radix", option.getSaveString(), entry.getKey(), option.toString(entry.getValue()));
                row("poke", "POKE_WIRE_RADIX1=" + option.getSaveString(), entry.getKey(),
                    pokeCaret(option, RadixOption.decode(AppPreferences.POKE_WIRE_RADIX2.get()), entry.getValue()));
                row("poke", "POKE_WIRE_RADIX2=" + option.getSaveString(), entry.getKey(),
                    pokeCaret(RadixOption.decode(AppPreferences.POKE_WIRE_RADIX1.get()), option, entry.getValue()));
            }
        }
    }

    /** The text of PokeTool.WireCaret.draw at d58a61e0. */
    private static String pokeCaret(RadixOption radix1, RadixOption radix2, Value v) {
        if (radix1 == null) radix1 = RadixOption.RADIX_2;
        var text = radix1.toString(v);
        if (radix2 != null && v.getWidth() > 1) text += " / " + radix2.toString(v);
        final var w = v.getWidth();
        if (w == 8 || w == 16 || w == 32 || w == 64) text += " / " + RadixOption.RADIX_FLOAT.toString(v);
        return text;
    }

    private static void vhdlKeywords() throws InterruptedException {
        boolean original = AppPreferences.VhdlKeywordsUpperCase.getBoolean();
        TreeMap<String, String> rows = new TreeMap<>();
        for (String keyword : Vhdl.VHDL_KEYWORDS) {
            set(AppPreferences.VhdlKeywordsUpperCase, true);
            String upper = Vhdl.getVhdlKeyword(keyword);
            set(AppPreferences.VhdlKeywordsUpperCase, false);
            rows.put(keyword, upper + " " + Vhdl.getVhdlKeyword(keyword));
        }
        set(AppPreferences.VhdlKeywordsUpperCase, original);
        rows.forEach((keyword, value) -> row("vhdl", keyword, "upper lower", value));
    }

    private static final class MarkerContext extends ComponentDrawContext {
        MarkerContext(java.awt.Graphics graphics) { super(null, null, null, graphics, graphics); }
        void mark(int x, int y) { drawPinMarker(x, y); }
    }

    /** Pin dots: the pixels ComponentDrawContext.drawPinMarker fills around a terminal at (20, 20). */
    private static void pinMarkers() throws InterruptedException {
        String original = AppPreferences.PinAppearance.get();
        String[] options = (String[]) declared(AppPreferences.PinAppearance, "opts");
        for (String option : options) {
            set(AppPreferences.PinAppearance, option);
            BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.BLACK);
            new MarkerContext(graphics).mark(20, 20);
            graphics.dispose();
            int minX = 40, minY = 40, maxX = -1, maxY = -1;
            for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) continue;
                minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
            // fillOval(x - offset, y - offset, d, d): report d and where the oval starts relative to the terminal.
            row("pin", option, "diameter offset", (maxX - minX + 1) + " " + (20 - minX));
        }
        set(AppPreferences.PinAppearance, original);
    }

    private static void boards() {
        // FpgaBoards' constructor selects the first built-in board when SelectedBoard is unset or unknown.
        row("board", "SelectedBoard", "after-start", AppPreferences.SelectedBoard.get());
        row("board", "SelectedBoard", "built-in", String.join(",", AppPreferences.Boards.getBoardNames()));
    }

    /** Records the DRC messages Reporter would show in the FPGA Commander. */
    private static final class Recorder extends FpgaReportTabbedPane {
        final List<String> warnings = new ArrayList<>();
        Recorder() { super(null); }
        @Override public void addInfo(Object message) {}
        @Override public void addWarning(Object message) { warnings.add(text(message)); }
        @Override public void addErrors(Object message) { warnings.add("error:" + text(message)); }
        @Override public void addConsole(String message) {}
        private static String text(Object message) {
            if (message instanceof SimpleDrcContainer container) {
                Object value = declared(container, "message");
                return String.valueOf(value);
            }
            return String.valueOf(message);
        }
    }

    private static void drc(File fixtures) throws Exception {
        sun.misc.Unsafe unsafe;
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        Recorder recorder = (Recorder) unsafe.allocateInstance(Recorder.class);
        Field warnings = Recorder.class.getDeclaredField("warnings");
        warnings.setAccessible(true);
        warnings.set(recorder, new ArrayList<String>());
        Reporter.report.setGuiLogger(recorder);
        File[] files = fixtures.listFiles((dir, name) -> name.startsWith("drc-") && name.endsWith(".circ"));
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File file : files) {
            for (boolean gated : new boolean[] {false, true}) {
                for (boolean open : new boolean[] {false, true}) {
                    set(AppPreferences.SuppressGatedClockWarnings, gated);
                    set(AppPreferences.SuppressOpenPinWarnings, open);
                    recorder.warnings.clear();
                    var logisimFile = new Loader(null).openLogisimFile(file);
                    var circuit = logisimFile.getCircuit("main");
                    int result = circuit.getNetList().designRuleCheckResult(true, new ArrayList<>());
                    TreeMap<String, Integer> counts = new TreeMap<>();
                    for (String warning : recorder.warnings) counts.merge(drcKey(warning), 1, Integer::sum);
                    row("drc", file.getName(), "gated=" + gated + ",open=" + open, "result=" + result + " " + counts);
                }
            }
        }
        set(AppPreferences.SuppressGatedClockWarnings, false);
        set(AppPreferences.SuppressOpenPinWarnings, false);
    }

    private static final String[] DRC_KEYS = {
        "NetList_UnconnectedInputs", "NetList_UnconnectedInput", "NetList_UnconnectedOutput", "NetList_CircuitGatedNotGated",
        "NetList_CircuitNotGated", "NetList_CircuitGated", "NetList_GatedClock", "NetList_GatedClockSink", "NetList_GatedClockInt",
        "NetList_GatedClockSource", "NetList_PossibleGatedClock", "NetList_NoClockConnection", "NetList_TraceListBegin",
        "NetList_TraceListEnd",
    };

    private static String drcKey(String message) {
        String best = null;
        for (String key : DRC_KEYS) {
            String text = com.cburch.logisim.fpga.Strings.S.get(key);
            if (message.equals(text) || message.equals("error:" + text)) return (message.startsWith("error:") ? "error:" : "") + key;
            if (best == null && message.contains(text)) best = key;
        }
        return best != null ? best : "other:" + message;
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
