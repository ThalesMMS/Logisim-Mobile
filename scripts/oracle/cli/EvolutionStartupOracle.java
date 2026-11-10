import com.cburch.logisim.gui.start.Startup;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.util.LocaleManager;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Model-level oracle for Evolution startup options that only take effect in the GUI.
 * Calls Startup.parseArgs in-process (headless, isolated preferences), applies the template
 * selection exactly as Startup.run() does, and prints the persisted startup profile:
 *   parse=ok|error  splash=true|false  gates=ansi|iec  locale=<stored> session=<lang>  template=empty|plain|custom:<path>
 */
public final class EvolutionStartupOracle {
  public static void main(String[] args) throws Exception {
    // Main.main reads AppPreferences.FollowSystemTheme before parseArgs in the GUI path, which loads
    // AppPreferences and registers its locale listener; do the same so --locale is persisted as there.
    AppPreferences.FollowSystemTheme.get();
    Startup startup = Startup.parseArgs(args);
    if (startup == null) {
      System.out.println("parse=error");
    } else {
      System.out.println("parse=ok");
      boolean splash = (Boolean) field("showSplash").get(startup);
      field("showSplash").set(startup, false);
      Method load = Startup.class.getDeclaredMethod("loadTemplate", com.cburch.logisim.file.Loader.class, File.class, boolean.class);
      load.setAccessible(true);
      load.invoke(startup, null, field("templFile").get(startup), field("templEmpty").get(startup));
      System.out.println("splash=" + splash);
    }
    // Read the persisted store, not AppPreferences' caches (template type is updated by asynchronous
    // preference events). This is the profile a later Evolution start reads.
    java.util.prefs.Preferences store = java.util.prefs.Preferences.userNodeForPackage(com.cburch.logisim.Main.class);
    store.flush();
    String shape = store.get("gateShape", AppPreferences.SHAPE_SHAPED);
    System.out.println("gates=" + (AppPreferences.SHAPE_RECTANGULAR.equals(shape) ? "iec" : AppPreferences.SHAPE_SHAPED.equals(shape) ? "ansi" : shape));
    System.out.println("locale=" + store.get("locale", "") + " session=" + LocaleManager.getLocale().getLanguage());
    int type = store.getInt("templateType", AppPreferences.TEMPLATE_PLAIN);
    String file = store.get("templateFile", "");
    System.out.println("template=" + switch (type) {
      case AppPreferences.TEMPLATE_EMPTY -> "empty";
      case AppPreferences.TEMPLATE_PLAIN -> "plain";
      case AppPreferences.TEMPLATE_CUSTOM -> "custom:" + file;
      default -> "unknown";
    });
    System.exit(0);
  }

  private static Field field(String name) throws Exception {
    Field f = Startup.class.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }
}
