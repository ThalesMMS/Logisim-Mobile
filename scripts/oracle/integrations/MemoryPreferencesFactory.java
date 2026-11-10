import java.util.*;
import java.util.prefs.*;

/** In-memory java.util.prefs backend so Evolution never touches the user's real preferences. */
public final class MemoryPreferencesFactory implements PreferencesFactory {
    private static final Preferences USER = new Node(null, "");
    private static final Preferences SYSTEM = new Node(null, "");

    static {
        // -Dmemprefs.seed="com/cburch/logisim:hdlType=Verilog;other/node:key=value"
        var seed = System.getProperty("memprefs.seed", "");
        for (var entry : seed.split(";")) {
            if (entry.isBlank()) continue;
            var colon = entry.indexOf(':');
            var equals = entry.indexOf('=', colon);
            USER.node(entry.substring(0, colon)).put(entry.substring(colon + 1, equals), entry.substring(equals + 1));
        }
    }
    public Preferences userRoot() { return USER; }
    public Preferences systemRoot() { return SYSTEM; }

    static final class Node extends AbstractPreferences {
        private final Map<String, String> values = new TreeMap<>();
        private final Map<String, Node> children = new TreeMap<>();
        Node(Node parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { }
        protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(new String[0]); }
        protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name, n -> new Node(this, n)); }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }
}
