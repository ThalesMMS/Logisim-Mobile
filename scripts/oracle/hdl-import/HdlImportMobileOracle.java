import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitProject;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.ComponentKind;
import dev.logisim.mobile.core.HdlImportResult;
import dev.logisim.mobile.core.HdlImportOptions;
import dev.logisim.mobile.core.HdlMemoryPolicy;
import dev.logisim.mobile.core.HdlSourceFormat;
import dev.logisim.mobile.core.CompanionJsonCodec;
import dev.logisim.mobile.core.CompanionJsonObject;
import dev.logisim.mobile.core.HdlInputFile;
import dev.logisim.mobile.core.HdlNativeImporter;
import dev.logisim.mobile.core.HdlUndefinedPolicy;
import dev.logisim.mobile.core.TestVectorFormat;
import dev.logisim.mobile.core.TestVectorRunSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Uses the production importer, codec and simulator from the supplied core JAR. */
public final class HdlImportMobileOracle {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("request")) {
            var sources = new ArrayList<HdlInputFile>();
            for (int i = 5; i < args.length; i++) {
                sources.add(new HdlInputFile(args[i], Files.readString(Path.of(args[3], args[i]))));
            }
            var names = sources.stream().map(HdlInputFile::getName)
                .filter(name -> name.endsWith(".v") || name.endsWith(".sv") || name.endsWith(".blif")).toList();
            var defines = args[4].isEmpty() ? List.<String>of() : List.of(args[4].split(","));
            var options = new HdlImportOptions(sources, names, HdlSourceFormat.valueOf(args[1]), args[2], defines, HdlUndefinedPolicy.REJECT, HdlMemoryPolicy.NATIVE);
            var request = options.request();
            System.out.println(CompanionJsonCodec.INSTANCE.write(new CompanionJsonObject(request.getParameters())));
            return;
        }
        if (args[0].equals("roundtrip")) {
            var loaded = CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[1])));
            if (!(loaded instanceof CircProjectImportResult.Success success)) throw new IllegalStateException("Reopen failed: " + loaded);
            Files.writeString(Path.of(args[2]), CircCodec.INSTANCE.writeProject(success.getProject()));
            return;
        }
        if (args[0].equals("import")) {
            var sources = new ArrayList<HdlInputFile>();
            for (int i = 5; i < args.length; i++) {
                sources.add(new HdlInputFile(args[i], Files.readString(Path.of(args[4], args[i]))));
            }
            var result = HdlNativeImporter.INSTANCE.importJson(Files.readString(Path.of(args[1])), sources,
                args[2], HdlUndefinedPolicy.REJECT, null, ComponentCatalog.INSTANCE.getRegistry());
            if (!(result instanceof HdlImportResult.Success success)) {
                throw new IllegalStateException("Import failed: " + result);
            }
            Files.writeString(Path.of(args[3]), CircCodec.INSTANCE.writeProject(success.getProject()));
            success.getModuleNames().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println("M\t" + entry.getKey() + "\t" + entry.getValue()));
            return;
        }
        if (!args[0].equals("observe") || args.length != 4) {
            throw new IllegalArgumentException("Usage: import netlist top output.circ sourcesDir source... OR observe circuit.circ circuitName vectors.txt");
        }
        var result = CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[1])));
        if (!(result instanceof CircProjectImportResult.Success success)) {
            throw new IllegalStateException("Reopen failed: " + result);
        }
        var project = success.getProject();
        structure(project).forEach(System.out::println);
        var table = TestVectorFormat.INSTANCE.read(Files.readString(Path.of(args[3])));
        var runner = new TestVectorRunSession(project, args[2], table,
            new CircuitSimulator(4096, ComponentCatalog.INSTANCE.getRegistry()));
        for (var row : runner.run(Integer.MAX_VALUE)) {
            var line = new StringBuilder("V\t").append(row.getRowIndex()).append('\t')
                .append(row.getPassed() ? "PASS" : "FAIL");
            for (var value : row.getObserved()) line.append('\t').append(value.getDisplay());
            System.out.println(line);
        }
    }

    private static List<String> structure(CircuitProject project) {
        var lines = new ArrayList<String>();
        var registry = ComponentCatalog.INSTANCE.getRegistry();
        for (var circuit : project.getCircuits()) {
            lines.add("C\t" + circuit.getName());
            for (var component : circuit.getComponents()) {
                var p = component.getPosition();
                if (component.getCircuitName() != null) {
                    lines.add("H\t" + circuit.getName() + "\t" + component.getCircuitName() + "\t" + p.getX() + "\t" + p.getY());
                } else if (component.getKind().equals(ComponentKind.INPUT) || component.getKind().equals(ComponentKind.OUTPUT)) {
                    lines.add("P\t" + circuit.getName() + "\t" + component.getLabel() + "\t"
                        + (component.getKind().equals(ComponentKind.INPUT) ? "INPUT" : "OUTPUT")
                        + "\t" + component.getWidth() + "\t" + p.getX() + "\t" + p.getY() + "\t" + component.getFacing());
                } else {
                    var definition = registry.requireDefinition(component.getKind());
                    if (!definition.getFileName().endsWith(" Gate")) continue;
                    var values = registry.attributes(component);
                    lines.add("G\t" + circuit.getName() + "\t" + definition.getFileName() + "\t" + p.getX() + "\t" + p.getY()
                        + "\t" + value(values.string("width")) + "\t" + value(values.string("inputs"))
                        + "\t" + value(values.string("size")) + "\t" + value(values.string("facing")));
                }
            }
        }
        lines.sort(String::compareTo);
        return lines;
    }

    private static String value(String text) { return text == null ? "-" : text; }
}
