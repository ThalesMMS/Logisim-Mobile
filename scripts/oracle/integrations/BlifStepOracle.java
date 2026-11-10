import com.cburch.logisim.std.hdl.BlifParser;
import com.cburch.logisim.std.hdl.DenseLogicCircuit;
import dev.logisim.mobile.core.HdlIpDeclarations;
import dev.logisim.mobile.core.HdlIpPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs a deterministic input sequence through Evolution's own BLIF simulator (BlifParser +
 * DenseLogicCircuit, as BlifCircuitComponent.propagate does) and prints a JSON document with the
 * Mobile-declared ports (HdlIpDeclarations.blif), the steps and Evolution's outputs per step.
 * Values are MSB first: 0, 1, ? (no driver / unknown) and E (error).
 * Usage: java BlifStepOracle file.blif
 */
public final class BlifStepOracle {
    public static void main(String[] args) throws Exception {
        var source = Files.readString(Path.of(args[0]));
        var design = HdlIpDeclarations.INSTANCE.blif(source, Map.of());
        if (!design.getDiagnostics().isEmpty()) throw new IllegalStateException("Mobile rejects " + args[0] + ": " + design.getDiagnostics());
        var parser = new BlifParser(source);
        parser.parse();
        var circuit = parser.compile();
        var cells = circuit.newCells();
        var aux = circuit.newAuxData();

        var inputs = parser.getInputs();
        var outputs = parser.getOutputs();
        var bits = 0;
        for (var port : inputs) bits += port.getWidthInt();
        var steps = new ArrayList<Map<String, String>>();
        var random = new Random(331);
        if (bits <= 5) {
            var total = (int) Math.pow(3, bits);
            for (var n = 0; n < total; n++) steps.add(assign(inputs, n, 3, null));
        } else {
            for (var n = 0; n < (1 << bits); n++) steps.add(assign(inputs, n, 2, null));
            for (var n = 0; n < 64; n++) steps.add(assign(inputs, 0, 3, random));
        }
        var json = new StringBuilder();
        json.append("{\"top\":\"").append(design.getTopLevel()).append("\",\"ports\":[");
        var first = true;
        for (HdlIpPort port : design.getPorts()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":\"").append(port.getName()).append("\",\"direction\":\"")
                .append(port.getDirection().name().equals("INPUT") ? "in" : port.getDirection().name().equals("OUTPUT") ? "out" : "inout")
                .append("\",\"width\":").append(port.getWidth()).append('}');
        }
        json.append("],\"steps\":[");
        for (var s = 0; s < steps.size(); s++) {
            var step = steps.get(s);
            for (var port : inputs) {
                var value = step.get(port.getName());
                var width = port.getWidthInt();
                for (var j = 0; j < width; j++) {
                    var symbol = parser.getPinDlcSymbol(port, j, false);
                    var cell = circuit.symbolTable.get(symbol);
                    if (cell == null) continue;
                    var c = value.charAt(width - 1 - j);
                    var level = c == '0' ? DenseLogicCircuit.LEV_LOW : c == '1' ? DenseLogicCircuit.LEV_HIGH : DenseLogicCircuit.LEV_NONE;
                    circuit.setCell(cell, (byte) level, cells, aux);
                }
            }
            circuit.simulate(cells, aux);
            var observed = new LinkedHashMap<String, String>();
            for (var port : outputs) {
                var width = port.getWidthInt();
                var text = new StringBuilder();
                for (var j = width - 1; j >= 0; j--) {
                    var cell = circuit.symbolTable.get(parser.getPinDlcSymbol(port, j, true));
                    var level = cell == null ? DenseLogicCircuit.LEV_NONE : cells[cell];
                    text.append(switch (level) { case DenseLogicCircuit.LEV_LOW -> '0'; case DenseLogicCircuit.LEV_HIGH -> '1';
                        case DenseLogicCircuit.LEV_ERR -> 'E'; default -> '?'; });
                }
                observed.put(port.getName(), text.toString());
            }
            if (s > 0) json.append(',');
            json.append("{\"inputs\":").append(object(step)).append(",\"desktop\":").append(object(observed)).append('}');
        }
        json.append("]}");
        System.out.println(json);
    }

    private static Map<String, String> assign(List<com.cburch.hdl.HdlModel.PortDescription> ports, int n, int radix, Random random) {
        var step = new LinkedHashMap<String, String>();
        for (var port : ports) {
            var text = new StringBuilder();
            for (var j = 0; j < port.getWidthInt(); j++) {
                var digit = random != null ? random.nextInt(radix) : n % radix;
                if (random == null) n /= radix;
                text.append(digit == 0 ? '0' : digit == 1 ? '1' : 'U');
            }
            step.put(port.getName(), text.toString());
        }
        return step;
    }

    private static String object(Map<String, String> values) {
        var text = new StringBuilder("{");
        var first = true;
        for (var entry : values.entrySet()) {
            if (!first) text.append(',');
            first = false;
            text.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
        }
        return text.append('}').toString();
    }
}
