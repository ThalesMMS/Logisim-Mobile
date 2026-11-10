import com.cburch.logisim.std.hdl.BlifParser;
import com.cburch.logisim.vhdl.base.VhdlParser;
import dev.logisim.mobile.core.HdlIpDeclarations;
import dev.logisim.mobile.core.HdlIpDesign;
import dev.logisim.mobile.core.HdlIpPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the same VHDL entity and BLIF sources with Evolution's VhdlParser/BlifParser and
 * Mobile's HdlIpDeclarations, and compares entity name, ports (name, direction, width) and
 * generic defaults. Prints one canonical line per program. A source Evolution rejects and Mobile
 * reads is reported as SUPERSET; any other mismatch is DIFF and makes the exit status 1.
 * Usage: java HdlDeclarationOracle file.vhd|file.blif ...
 */
public final class HdlDeclarationOracle {
    public static void main(String[] args) throws Exception {
        var failures = 0;
        var compared = 0;
        var supersets = 0;
        for (var argument : args) {
            var path = Path.of(argument);
            var source = Files.readString(path);
            var vhdl = argument.endsWith(".vhd") || argument.endsWith(".vhdl");
            String desktop;
            try {
                desktop = vhdl ? desktopVhdl(source) : desktopBlif(source);
            } catch (Exception error) {
                desktop = "REJECT";
                System.out.println("  desktop error " + error);
            }
            HdlIpDesign design = vhdl ? HdlIpDeclarations.INSTANCE.vhdl(source, Map.of())
                : HdlIpDeclarations.INSTANCE.blif(source, Map.of());
            var mobile = design.getDiagnostics().isEmpty() ? canonical(design) : "REJECT";
            compared++;
            var same = desktop.equals(mobile);
            // Evolution rejects the source but Mobile reads it: Mobile accepts a superset of the dialect.
            var superset = desktop.equals("REJECT") && !mobile.equals("REJECT");
            if (superset) supersets++;
            else if (!same) failures++;
            System.out.println((same ? "SAME " : superset ? "SUPERSET " : "DIFF ") + path.getFileName());
            System.out.println("  desktop " + desktop);
            System.out.println("  mobile  " + mobile);
            if (!same && !design.getDiagnostics().isEmpty()) System.out.println("  mobile diagnostics " + design.getDiagnostics());
        }
        System.out.println((failures == 0 ? "PASS" : "FAIL") + ": " + (compared - failures - supersets) + "/" + compared
            + " HDL declarations match Evolution, " + supersets + " accepted only by Mobile, " + failures + " differ");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String desktopVhdl(String source) throws Exception {
        var parser = new VhdlParser(source);
        parser.parse();
        var ports = new ArrayList<String>();
        for (var port : parser.getInputs()) ports.add(port(port.getName(), port.getType(), port.getWidth().getWidth()));
        for (var port : parser.getOutputs()) ports.add(port(port.getName(), port.getType(), port.getWidth().getWidth()));
        var generics = new TreeMap<String, String>();
        for (var generic : parser.getGenerics()) generics.put(generic.getName().toLowerCase(), Integer.toString(generic.getDefaultValue()));
        return parser.getName() + " " + ports + " " + generics;
    }

    private static String desktopBlif(String source) {
        var parser = new BlifParser(source);
        parser.parse();
        var ports = new ArrayList<String>();
        for (var port : parser.getInputs()) ports.add(port(port.getName(), "in", port.getWidth().getWidth()));
        for (var port : parser.getOutputs()) ports.add(port(port.getName(), "out", port.getWidth().getWidth()));
        return parser.getName() + " " + ports + " {}";
    }

    private static String canonical(HdlIpDesign design) {
        var ports = new ArrayList<String>();
        for (var direction : List.of("INPUT", "INOUT", "OUTPUT")) {
            for (HdlIpPort port : design.getPorts()) {
                if (!port.getDirection().name().equals(direction)) continue;
                var type = switch (direction) { case "INPUT" -> "in"; case "OUTPUT" -> "out"; default -> "inout"; };
                ports.add(port(port.getName(), type, port.getWidth()));
            }
        }
        var generics = new TreeMap<String, String>();
        design.getParameters().forEach((name, value) -> generics.put(name.toLowerCase(), value));
        return design.getTopLevel() + " " + ports + " " + generics;
    }

    private static String port(String name, String type, int width) {
        // Evolution's Port constants are "input"/"output"/"inout".
        var direction = switch (type.toLowerCase()) { case "input" -> "in"; case "output" -> "out"; default -> type.toLowerCase(); };
        return name + ":" + direction + ":" + width;
    }
}
