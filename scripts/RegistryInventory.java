import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reads the public catalog without linking this tool to a particular Kotlin compiler. */
public final class RegistryInventory {
  private RegistryInventory() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("RegistryInventory takes no arguments");
    Class<?> catalogType = Class.forName("dev.logisim.mobile.core.ComponentCatalog");
    Object catalog = catalogType.getField("INSTANCE").get(null);
    Object registry = property(catalog, "getRegistry");
    List<Map<String, Object>> definitions = new ArrayList<>();
    TreeSet<String> kinds = new TreeSet<>();
    for (Object definition : (Collection<?>) property(registry, "getDefinitions")) {
      Map<String, Object> entry = new LinkedHashMap<>();
      String kindName = kindName(property(definition, "getKind"));
      if (!kinds.add(kindName)) throw new IllegalArgumentException("Duplicate registry kind: " + kindName);
      entry.put("kind", kindName);
      entry.put("fileName", property(definition, "getFileName"));
      entry.put("library", property(definition, "getLibrary"));
      Object behavior = property(definition, "getBehavior");
      entry.put("hasBehavior", behavior != null);
      entry.put("isCombinational", property(definition, "isCombinational"));
      entry.put("supportsRotation", property(definition, "getSupportsRotation"));
      entry.put("defaultAttributes", sortedMap(property(definition, "getDefaultAttributes")));
      Object schema = property(definition, "getSchema");
      entry.put("schemaDefaults", sortedMap(property(schema, "getDefaults")));
      List<String> keys = new ArrayList<>();
      for (Object key : (Collection<?>) property(schema, "getKeys")) {
        keys.add((String) property(key, "getName"));
      }
      keys.sort(String::compareTo);
      if (new TreeSet<>(keys).size() != keys.size()) {
        throw new IllegalArgumentException("Duplicate schema key for " + kindName);
      }
      entry.put("schemaKeys", keys);
      TreeSet<String> owners = new TreeSet<>();
      for (String getter : List.of("getLayout", "getBehavior", "getPullDrivers", "getMatchesFileAttributes",
          "getDefaultsForVersion", "getCopyAttributes", "getReconfigureAttributes")) {
        Object callable = property(definition, getter);
        if (callable != null) owners.add(ownerClass(callable));
      }
      entry.put("metadataOwners", owners);
      definitions.add(entry);
    }
    definitions.sort(Comparator.comparing(entry -> (String) entry.get("kind")));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema_version", 1);
    result.put("definitions", definitions);
    result.put("kindAliases", aliases());
    System.out.println(json(result));
  }

  private static List<Map<String, String>> aliases() throws Exception {
    Class<?> kindType = Class.forName("dev.logisim.mobile.core.ComponentKind");
    Map<String, String> aliases = new TreeMap<>();
    // Only constant getters from catalog families are allowed. Never inspect arbitrary plugin classes.
    for (String name : List.of("ComponentKind", "ComponentKind$Companion", "WiringLibrary", "GatesLibrary",
        "PlexersLibrary", "MemoryComponents", "SequentialLibrary", "IoKinds", "ExtraIoKinds",
        "ExternalIoComponents", "AnnotationLibrary", "SocComponents", "TclComponents", "HdlIpLibrary")) {
      Class<?> owner = Class.forName("dev.logisim.mobile.core." + name);
      String symbolOwner = name.replace("$Companion", "");
      for (Field field : owner.getFields()) {
        if (Modifier.isStatic(field.getModifiers()) && field.getType() == kindType) {
          addAlias(aliases, symbolOwner + "." + field.getName(), field.get(null));
        }
      }
      Object receiver = null;
      if (name.endsWith("$Companion")) receiver = kindType.getField("Companion").get(null);
      else {
        try { receiver = owner.getField("INSTANCE").get(null); }
        catch (NoSuchFieldException ignored) { /* ComponentKind exposes static fields, not an object instance. */ }
      }
      for (Method method : owner.getMethods()) {
        if (method.getParameterCount() == 0 && method.getReturnType() == kindType
            && method.getName().startsWith("get") && method.getName().length() > 3
            && (receiver != null || Modifier.isStatic(method.getModifiers()))) {
          addAlias(aliases, symbolOwner + "." + method.getName().substring(3), method.invoke(receiver));
        }
      }
    }
    List<Map<String, String>> result = new ArrayList<>();
    aliases.forEach((symbol, kind) -> {
      Map<String, String> entry = new LinkedHashMap<>();
      entry.put("symbol", symbol);
      entry.put("kind", kind);
      result.add(entry);
    });
    return result;
  }

  private static void addAlias(Map<String, String> aliases, String symbol, Object kind) throws Exception {
    String name = kindName(kind);
    String previous = aliases.put(symbol, name);
    if (previous != null && !previous.equals(name)) throw new IllegalArgumentException("Conflicting kind alias " + symbol);
  }

  private static String kindName(Object kind) throws Exception {
    return (String) property(kind, "getName");
  }

  private static Object property(Object instance, String name) throws Exception {
    Method method = instance.getClass().getMethod(name);
    return method.invoke(instance);
  }

  private static Map<String, String> sortedMap(Object value) {
    Map<String, String> result = new TreeMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
      if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)) {
        throw new IllegalArgumentException("Registry attributes must have string keys and values");
      }
      result.put(key, text);
    }
    return result;
  }

  private static String ownerClass(Object callable) {
    // Kotlin/JVM lambda names include runtime-specific addresses after '/'. Keep the declaring class.
    return callable.getClass().getName().split("[/$]", 2)[0];
  }

  private static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof String text) {
      StringBuilder encoded = new StringBuilder("\"");
      for (int i = 0; i < text.length(); i++) {
        char character = text.charAt(i);
        switch (character) {
          case '"' -> encoded.append("\\\"");
          case '\\' -> encoded.append("\\\\");
          case '\b' -> encoded.append("\\b");
          case '\f' -> encoded.append("\\f");
          case '\n' -> encoded.append("\\n");
          case '\r' -> encoded.append("\\r");
          case '\t' -> encoded.append("\\t");
          default -> {
            if (character < 0x20 || character >= 0x7f) {
              encoded.append(String.format("\\u%04x", (int) character));
            } else encoded.append(character);
          }
        }
      }
      return encoded.append('"').toString();
    }
    if (value instanceof Map<?, ?> map) {
      List<String> entries = new ArrayList<>();
      map.forEach((key, item) -> entries.add(json(key) + ":" + json(item)));
      return "{" + String.join(",", entries) + "}";
    }
    if (value instanceof Collection<?> collection) {
      return "[" + String.join(",", collection.stream().map(RegistryInventory::json).toList()) + "]";
    }
    throw new IllegalArgumentException("Cannot encode registry metadata: " + value.getClass().getName());
  }
}
