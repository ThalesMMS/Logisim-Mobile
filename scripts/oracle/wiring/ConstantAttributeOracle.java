import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Constant;
import com.cburch.logisim.std.wiring.Pin;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Literal checks against desktop attribute parsers; no mobile implementation is used. */
public final class ConstantAttributeOracle {
    private static int checks;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.prefs.PreferencesFactory", MemoryPreferencesFactory.class.getName());
        System.out.println("desktopJar=" + Constant.class.getProtectionDomain().getCodeSource().getLocation());
        parsed("constant decimal", Constant.ATTR_VALUE, "10", 10L);
        parsed("constant hex", Constant.ATTR_VALUE, "0x10", 16L);
        parsed("constant octal", Constant.ATTR_VALUE, "010", 8L);
        parsed("constant negative", Constant.ATTR_VALUE, "-1", -1L);
        parsed("constant binary", Constant.ATTR_VALUE, "0b10", 2L);
        parsed("constant unsigned64 decimal", Constant.ATTR_VALUE, "18446744073709551615", -1L);
        parsed("constant unsigned64 hex", Constant.ATTR_VALUE, "0xffffffffffffffff", -1L);
        parsed("constant unsigned64 binary", Constant.ATTR_VALUE, "0b" + "1".repeat(64), -1L);
        parsed("constant signed minimum decimal", Constant.ATTR_VALUE, "-9223372036854775808", Long.MIN_VALUE);
        parsed("constant signed minimum hex", Constant.ATTR_VALUE, "-0x8000000000000000", Long.MIN_VALUE);
        parsed("constant unsigned sign bit", Constant.ATTR_VALUE, "0x8000000000000000", Long.MIN_VALUE);
        parsed("constant zero", Constant.ATTR_VALUE, "0", 0L);
        parsed("constant octal zero", Constant.ATTR_VALUE, "00", 0L);
        parsed("constant uppercase prefix", Constant.ATTR_VALUE, "0XfF", 255L);
        parsed("constant negative binary", Constant.ATTR_VALUE, "-0b10", -2L);
        parsed("constant negative octal", Constant.ATTR_VALUE, "-010", -8L);

        for (String invalid : new String[]{"ff", "08", "", "18446744073709551616",
                "0x10000000000000000", "-9223372036854775809", "-0x8000000000000001",
                "-18446744073709551615", "0b2", " 10 "}) {
            rejected("constant invalid", Constant.ATTR_VALUE, invalid);
        }

        AttributeSet defaults = Constant.FACTORY.createAttributeSet();
        equal("constant default width", 1L, defaults.getValue(StdAttr.WIDTH).getWidth());
        equal("constant default value", 1L, defaults.getValue(Constant.ATTR_VALUE));
        defaults.setValue(StdAttr.WIDTH, BitWidth.create(8));
        equal("constant default width1 to width8", 255L, defaults.getValue(Constant.ATTR_VALUE));
        defaults.setValue(StdAttr.WIDTH, BitWidth.create(64));
        equal("constant default width8 to width64", -1L, defaults.getValue(Constant.ATTR_VALUE));

        configured("decimal constant width8", "10", 8, 10L);
        configured("hex constant width8", "0x10", 8, 16L);
        configured("negative constant width8", "-1", 8, 255L);
        configured("unsigned64 constant width8 truncation", "18446744073709551615", 8, 255L);
        configured("unsigned64 constant width64", "18446744073709551615", 64, -1L);
        configured("signed minimum constant width64", "-9223372036854775808", 64, Long.MIN_VALUE);

        parsed("pin initial decimal", Pin.ATTR_INITIAL, "10", 10L);
        parsed("pin initial hex", Pin.ATTR_INITIAL, "0x10", 16L);
        parsed("pin initial octal", Pin.ATTR_INITIAL, "010", 8L);
        parsed("pin initial binary", Pin.ATTR_INITIAL, "0b10", 2L);
        parsed("pin initial unsigned64", Pin.ATTR_INITIAL, "18446744073709551615", -1L);
        rejected("pin initial bare hex", Pin.ATTR_INITIAL, "ff");
        rejected("pin initial invalid octal", Pin.ATTR_INITIAL, "08");
        System.out.println("CONSTANT_ATTRIBUTE_ORACLE_PASS checks=" + checks);
    }

    private static void parsed(String label, Attribute<Long> attribute, String raw, long expected) {
        equal(label + " raw=\"" + raw + "\"", expected, attribute.parse(raw));
    }

    private static void configured(String label, String raw, int width, long expected) {
        AttributeSet attributes = Constant.FACTORY.createAttributeSet();
        attributes.setValue(StdAttr.WIDTH, BitWidth.create(width));
        attributes.setValue(Constant.ATTR_VALUE, Constant.ATTR_VALUE.parse(raw));
        equal(label, expected, attributes.getValue(Constant.ATTR_VALUE));
    }

    private static void rejected(String label, Attribute<Long> attribute, String raw) {
        try {
            attribute.parse(raw);
        } catch (NumberFormatException expected) {
            checks++;
            System.out.println("PASS " + label + " raw=\"" + raw + "\" rejected");
            return;
        }
        throw new AssertionError(label + " raw=\"" + raw + "\" was accepted");
    }

    private static void equal(String label, long expected, long actual) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected=" + Long.toUnsignedString(expected)
                + " actual=" + Long.toUnsignedString(actual));
        }
        checks++;
        System.out.println("PASS " + label + " value=" + Long.toUnsignedString(actual));
    }

    /** Keep desktop static initialization away from the user's Preferences stores. */
    public static final class MemoryPreferencesFactory implements PreferencesFactory {
        private final Preferences userRoot = new MemoryPreferences(null, "");
        private final Preferences systemRoot = new MemoryPreferences(null, "");
        public Preferences userRoot() { return userRoot; }
        public Preferences systemRoot() { return systemRoot; }
    }

    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { values.clear(); children.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, key -> new MemoryPreferences(this, key));
        }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }
}
