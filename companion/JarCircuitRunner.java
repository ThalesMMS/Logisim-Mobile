// Runs one staged circuit inside the companion's OS sandbox.
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.CircuitWires;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class JarCircuitRunner {
  private static final String PREFIX = "LOGISIM_JAR_V1";

  static final class UploadedLibraryLoader extends URLClassLoader {
    UploadedLibraryLoader(Path jar) throws Exception {
      super(new java.net.URL[] {jar.toUri().toURL()}, JarCircuitRunner.class.getClassLoader());
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException {
      if (name.startsWith("java.") || name.startsWith("javax.")
          || name.startsWith("com.cburch.logisim.")) {
        return super.loadClass(name, resolve);
      }
      var found = findLoadedClass(name);
      if (found == null) {
        try {
          found = findClass(name);
        } catch (ClassNotFoundException ignored) {
          return super.loadClass(name, resolve);
        }
      }
      if (resolve) resolveClass(found);
      return found;
    }
  }

  static final class CircuitLoader extends Loader {
    private final String descriptor;
    private final Library library;
    private boolean used;

    CircuitLoader(String descriptor, Library library) {
      super(null);
      this.descriptor = descriptor;
      this.library = library;
    }

    @Override
    public Library loadLibrary(String name) {
      if (descriptor.equals(name)) {
        used = true;
        return library;
      }
      if (name.startsWith("#")) return super.loadLibrary(name);
      throw new IllegalArgumentException("The circuit references an unstaged library: " + name);
    }

    @Override
    public void showError(String message) {
      throw new IllegalArgumentException(message);
    }
  }

  private static Value parseBits(String bits) {
    var values = new Value[bits.length()];
    for (int index = 0; index < bits.length(); index++) {
      values[index] = switch (bits.charAt(bits.length() - index - 1)) {
        case '0' -> Value.FALSE;
        case '1' -> Value.TRUE;
        case '?' -> Value.UNKNOWN;
        case 'E' -> Value.ERROR;
        default -> throw new IllegalArgumentException("Invalid input bit.");
      };
    }
    return Value.create(values);
  }

  private static String decode(String encoded) {
    return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
  }

  private static String encode(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void record(String kind, String... values) {
    System.out.print(PREFIX + "\t" + kind);
    for (var value : values) System.out.print("\t" + encode(value));
    System.out.println();
  }

  public static void main(String[] args) {
    try {
      run(args);
      System.exit(0);
    } catch (DriverTraceException error) {
      record("driver-error", error.getMessage());
      System.exit(0);
    } catch (Exception | LinkageError error) {
      // Report what the library constructor or loader threw, not the reflective wrapper.
      Throwable cause = error;
      while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
      record("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
      System.exit(0);
    }
  }

  private record ObservedInstance(
      String id, String path, CircuitState state, Component component, List<EndData> terminals) {}

  private static Set<ComponentFactory> uploadedFactories(Library library) {
    Set<ComponentFactory> factories = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<Library> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    var pending = new ArrayList<>(List.of(library));
    while (!pending.isEmpty()) {
      var next = pending.removeLast();
      if (!seen.add(next)) continue;
      for (var tool : next.getTools()) {
        if (tool instanceof AddTool add) factories.add(add.getFactory());
      }
      pending.addAll(next.getLibraries());
    }
    return factories;
  }

  private static void collectInstances(CircuitState state, Set<ComponentFactory> factories,
      String path, int depth, List<ObservedInstance> instances, Set<String> identities) {
    if (depth > 24 || identities.size() >= 8192) {
      throw new IllegalArgumentException("The JAR trace hierarchy exceeds its limit.");
    }
    var components = new ArrayList<>(state.getCircuit().getNonWires());
    components.sort(Comparator.comparingInt((Component item) -> item.getLocation().getX())
        .thenComparingInt(item -> item.getLocation().getY())
        .thenComparing(item -> item.getFactory().getName()));
    for (var component : components) {
      var factory = component.getFactory();
      if (!factories.contains(factory) && !(factory instanceof SubcircuitFactory)) continue;
      var location = component.getLocation();
      var id = path + "/" + location.getX() + "," + location.getY();
      if (!identities.add(id) || identities.size() > 8192) {
        throw new IllegalArgumentException("Duplicate or excessive JAR trace locations.");
      }
      if (factories.contains(factory)) {
        if (instances.size() >= 128) throw new IllegalArgumentException("Too many JAR instances to trace.");
        instances.add(new ObservedInstance(id, path, state, component, List.copyOf(component.getEnds())));
      } else {
        if (!(state.getData(component) instanceof CircuitState child)) {
          throw new IllegalArgumentException("A child circuit state is unavailable.");
        }
        collectInstances(child, factories, id, depth + 1, instances, identities);
      }
    }
  }

  private static void describeInstances(List<ObservedInstance> instances, int frameCount) {
    var terminals = 0;
    for (var instance : instances) {
      var component = instance.component();
      var bounds = component.getBounds();
      var location = component.getLocation();
      record("instance", instance.id(), instance.path(), instance.state().getCircuit().getName(),
          component.getFactory().getName(), "" + location.getX(), "" + location.getY(),
          "" + bounds.getX(), "" + bounds.getY(), "" + bounds.getWidth(), "" + bounds.getHeight());
      var index = 0;
      for (var end : instance.terminals()) {
        var width = end.getWidth().getWidth();
        if ((!end.isInput() && !end.isOutput()) || width < 1 || width > 64
            || ++terminals * frameCount > 4096) {
          throw new IllegalArgumentException("Unsupported JAR terminal or too many trace samples.");
        }
        var direction = end.isInput() ? (end.isOutput() ? "inout" : "input") : "output";
        record("terminal", instance.id(), "" + index++, "" + end.getLocation().getX(),
            "" + end.getLocation().getY(), direction, "" + width, "" + end.isExclusive());
      }
    }
  }

  private static final class DriverTraceException extends Exception {
    DriverTraceException(String reason) { super(reason); }
  }

  /** The fixed Evolution runtime stores each driver separately in its settled wire state. */
  private static final class DriverAccess {
    private final Method getWireData;
    private final Field busAt;
    private final Field width;
    private final Field connections;

    DriverAccess() throws DriverTraceException {
      try {
        getWireData = CircuitState.class.getDeclaredMethod("getWireData");
        var busType = Class.forName(CircuitWires.class.getName() + "$ValuedBus");
        busAt = getWireData.getReturnType().getDeclaredField("busAt");
        width = busType.getDeclaredField("width");
        connections = busType.getDeclaredField("connections");
        if (!getWireData.trySetAccessible() || !busAt.trySetAccessible() || !width.trySetAccessible()
            || !connections.trySetAccessible()) {
          throw new DriverTraceException("The Evolution runtime does not expose driver trace data.");
        }
      } catch (ReflectiveOperationException | SecurityException error) {
        throw new DriverTraceException("The Evolution runtime does not expose driver trace data.");
      }
    }

    String value(ObservedInstance instance, EndData end) throws DriverTraceException {
      try {
        var wireState = getWireData.invoke(instance.state());
        var bus = wireState == null ? null : ((Map<?, ?>) busAt.get(wireState)).get(end.getLocation());
        var bits = end.getWidth().getWidth();
        if (bus == null || width.getInt(bus) != bits) {
          throw new DriverTraceException("The JAR output has no valid wire at " + end.getLocation() + ".");
        }
        for (var link : (CircuitWires.BusConnection[]) connections.get(bus)) {
          if (link.component != instance.component() || !link.location.equals(end.getLocation())) continue;
          if (link.isSink || link.isBidirectional != end.isInput()) {
            throw new DriverTraceException("The JAR terminal is not a compatible driver.");
          }
          var drive = link.drivenValue;
          if (drive == null || drive == Value.NIL) return "Z";
          if (drive.getWidth() != bits) {
            throw new DriverTraceException("The JAR output drive has a different width.");
          }
          return signalBits(drive);
        }
        throw new DriverTraceException("The approved JAR output is absent from its wire.");
      } catch (ReflectiveOperationException | ClassCastException error) {
        throw new DriverTraceException("The Evolution runtime driver trace is incompatible.");
      }
    }
  }

  private static String signalBits(Value value) {
    var bits = new StringBuilder(value.getWidth());
    for (int bit = value.getWidth() - 1; bit >= 0; bit--) {
      var signal = value.get(bit);
      if (signal == Value.TRUE) bits.append('1');
      else if (signal == Value.FALSE) bits.append('0');
      else if (signal == Value.UNKNOWN) bits.append('?');
      else if (signal == Value.ERROR) bits.append('E');
      else throw new IllegalArgumentException("The JAR signal has an unknown representation.");
    }
    return bits.toString();
  }

  private static void recordInstanceSamples(List<ObservedInstance> instances, int frameIndex,
      DriverAccess drivers) throws DriverTraceException {
    for (var instance : instances) {
      if (!instance.terminals().equals(instance.component().getEnds())) {
        throw new IllegalArgumentException("JAR terminal geometry changed during the trace.");
      }
      var index = 0;
      for (var end : instance.terminals()) {
        var width = end.getWidth().getWidth();
        var resolved = instance.state().getValue(end.getLocation());
        if (resolved.getWidth() != width && resolved.getWidth() != 0) {
          throw new IllegalArgumentException("The resolved JAR terminal width changed.");
        }
        var bits = resolved.getWidth() == 0 ? "?".repeat(width) : signalBits(resolved);
        record("terminal-value", "" + frameIndex, instance.id(), "" + index, bits);
        if (drivers != null && end.isOutput()) {
          record("driver-value", "" + frameIndex, instance.id(), "" + index, drivers.value(instance, end));
        }
        index++;
      }
    }
  }

  private static void run(String[] args) throws Exception {
    var projectFile = Path.of(args[0]);
    var className = args[2];
    try (var libraries = new UploadedLibraryLoader(Path.of(args[1]));
         var xml = Files.newInputStream(projectFile)) {
      var libraryClass = libraries.loadClass(className);
      if (libraryClass.getClassLoader() != libraries || !Library.class.isAssignableFrom(libraryClass)) {
        throw new IllegalArgumentException("The uploaded JAR does not supply the requested library class.");
      }
      var library = (Library) libraryClass.getConstructor().newInstance();
      var loader = new CircuitLoader(args[3], library);
      var file = loader.openLogisimFile(xml);
      if (file == null || !loader.used) throw new IllegalArgumentException("The circuit did not load the staged JAR.");
      var circuit = args[4].isEmpty() ? file.getMainCircuit() : file.getCircuit(args[4]);
      if (circuit == null) throw new IllegalArgumentException("The requested circuit does not exist.");
      var project = new Project(file);
      var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
      var inputs = new LinkedHashMap<String, Instance>();
      var outputs = new LinkedHashMap<String, Instance>();
      for (var component : circuit.getNonWires()) {
        if (component.getFactory() != Pin.FACTORY) continue;
        var pin = Instance.getInstanceFor(component);
        var name = pin.getAttributeValue(StdAttr.LABEL);
        if (name.isBlank()) throw new IllegalArgumentException("Every external pin needs a unique label.");
        if (inputs.containsKey(name) || outputs.containsKey(name)) {
          throw new IllegalArgumentException("Duplicate pin label: " + name);
        }
        var input = Pin.FACTORY.isInputPin(pin);
        (input ? inputs : outputs).put(name, pin);
        record("port", name, input ? "input" : "output", "" + pin.getAttributeValue(StdAttr.WIDTH).getWidth());
      }
      if (outputs.isEmpty()) throw new IllegalArgumentException("The circuit needs an output pin.");
      var frameIndex = 0;
      var observed = new ArrayList<ObservedInstance>();
      var trace = args.length > 6 && Boolean.parseBoolean(args[6]);
      var drivers = args.length > 8 && Boolean.parseBoolean(args[8]) ? new DriverAccess() : null;
      if (drivers != null && !trace) throw new IllegalArgumentException("Driver tracing requires instance tracing.");
      var live = args[5].equals("-");
      var staged = live ? List.<String>of() : Files.readAllLines(Path.of(args[5]), StandardCharsets.UTF_8);
      var maxFrames = live ? Integer.parseInt(args[7]) : staged.size();
      if (live) record("ready", className, library.getName(), circuit.getName());
      var stdin = live ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
      while (true) {
        var frame = live ? stdin.readLine() : frameIndex < staged.size() ? staged.get(frameIndex) : null;
        if (frame == null) {
          if (live) throw new IllegalArgumentException("The live JAR stream ended before finishing.");
          break;
        }
        if (live && frame.equals("!finish")) break;
        if (frameIndex >= maxFrames) throw new IllegalArgumentException("Too many JAR input frames.");
        Map<String, String> values = new LinkedHashMap<>();
        if (!frame.isEmpty()) {
          for (var assignment : frame.split("\t", -1)) {
            var parts = assignment.split("=", 2);
            values.put(decode(parts[0]), parts[1]);
          }
        }
        if (!inputs.keySet().equals(values.keySet())) {
          throw new IllegalArgumentException("Each frame must assign every input pin exactly once.");
        }
        for (var entry : inputs.entrySet()) {
          var pin = entry.getValue();
          var bits = values.get(entry.getKey());
          if (bits.length() != pin.getAttributeValue(StdAttr.WIDTH).getWidth()) {
            throw new IllegalArgumentException("Wrong input width: " + entry.getKey());
          }
          Pin.FACTORY.driveInputPin(state.getInstanceState(pin), parseBits(bits));
          state.markComponentAsDirty(pin.getComponent());
        }
        var propagation = state.getPropagator();
        propagation.propagate();
        if (propagation.isOscillating()) throw new IllegalArgumentException("The circuit oscillates.");
        if (trace && frameIndex == 0) {
          collectInstances(state, uploadedFactories(library), "", 0, observed, new HashSet<>());
          describeInstances(observed, maxFrames);
        }
        if (trace) recordInstanceSamples(observed, frameIndex, drivers);
        for (var entry : outputs.entrySet()) {
          var pin = entry.getValue();
          var value = Pin.FACTORY.getValue(state.getInstanceState(pin));
          var bits = value.getWidth() == 0 ? "?".repeat(pin.getAttributeValue(StdAttr.WIDTH).getWidth())
              : signalBits(value);
          record("output", "" + frameIndex, entry.getKey(), bits);
        }
        if (live) record("frame-complete", "" + frameIndex);
        frameIndex++;
      }
      record("complete", className, library.getName(), circuit.getName(), "" + frameIndex);
    }
  }
}
