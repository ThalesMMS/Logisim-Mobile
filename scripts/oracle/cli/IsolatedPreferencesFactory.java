import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.*;

/**
 * File-backed java.util.prefs store for the CLI oracle. Selected with
 * -Djava.util.prefs.PreferencesFactory=IsolatedPreferencesFactory and
 * -Dlogisim.oracle.prefs=FILE, so Evolution never reads or writes the user's real preferences.
 */
public final class IsolatedPreferencesFactory implements PreferencesFactory {
  private static final Node USER = new Node(null, "");
  private static final Node SYSTEM = new Node(null, "");
  private static final Path FILE = Paths.get(System.getProperty("logisim.oracle.prefs", "/nonexistent/oracle-prefs"));

  static {
    if (System.getProperty("logisim.oracle.prefs") == null) throw new IllegalStateException("logisim.oracle.prefs is required");
    if (Files.exists(FILE)) {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(FILE)) { props.load(in); } catch (IOException e) { throw new UncheckedIOException(e); }
      for (String key : props.stringPropertyNames()) {
        int slash = key.lastIndexOf('\u0001');
        String path = key.substring(0, slash);
        Node node = USER;
        for (String part : path.isEmpty() ? new String[0] : path.substring(1).split("/")) node = (Node) node.node(part);
        node.values.put(key.substring(slash + 1), props.getProperty(key));
      }
    }
    Runtime.getRuntime().addShutdownHook(new Thread(IsolatedPreferencesFactory::save));
  }

  static synchronized void save() {
    Properties props = new Properties();
    collect(USER, props);
    try (OutputStream out = Files.newOutputStream(FILE)) { props.store(out, null); } catch (IOException e) { throw new UncheckedIOException(e); }
  }

  private static void collect(Node node, Properties props) {
    node.values.forEach((k, v) -> props.setProperty(node.absolutePath().equals("/") ? "\u0001" + k : node.absolutePath() + "\u0001" + k, v));
    node.kids.values().forEach(child -> collect(child, props));
  }

  public Preferences userRoot() { return USER; }
  public Preferences systemRoot() { return SYSTEM; }

  static final class Node extends AbstractPreferences {
    final Map<String, String> values = new TreeMap<>();
    final Map<String, Node> kids = new TreeMap<>();
    Node(Node parent, String name) { super(parent, name); }
    protected void putSpi(String key, String value) { values.put(key, value); }
    protected String getSpi(String key) { return values.get(key); }
    protected void removeSpi(String key) { values.remove(key); }
    protected void removeNodeSpi() { ((Node) parent()).kids.remove(name()); }
    protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
    protected String[] childrenNamesSpi() { return kids.keySet().toArray(new String[0]); }
    protected AbstractPreferences childSpi(String name) { return kids.computeIfAbsent(name, n -> new Node(this, n)); }
    protected void syncSpi() {}
    protected void flushSpi() { save(); }
  }
}
