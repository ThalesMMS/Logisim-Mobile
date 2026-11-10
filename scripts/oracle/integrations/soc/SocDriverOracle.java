import com.cburch.logisim.Main;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.soc.data.SocSupport;
import com.cburch.logisim.soc.pio.PioAttributes;
import com.cburch.logisim.soc.pio.PioState;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.SocComponents;
import dev.logisim.mobile.core.SocDriverExport;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Compares the PIO C-driver register maps of Evolution and Mobile for each SocPio of a .circ.
 *
 * Evolution: PioMenu.exportC (d58a61e0) is private and opens a JFileChooser, so this oracle runs
 * its exact sequence of SocSupport.add*Function calls on the loaded PioState into memory and
 * parses `base[index]`. Mobile: SocDriverExport.export, parsing `BASE_ADDRESS + offset`.
 * Each map also lists the register offsets that Evolution's PIO bus decoder accepts
 * (PioState DATA/DIR/IRQ_MASK/EDGE_CAPT/OUT_SET/OUT_CLEAR = 0x0/0x4/0x8/0xC/0x10/0x14).
 * Prints the maps and SAME/DIFF per component; exit 0 always (differences are the result).
 */
public final class SocDriverOracle {
    private static final Map<String, Integer> DECODER = Map.of("OutputValue", 0, "InputValue", 0, "DirectionReg", 4,
        "IrqMaskReg", 8, "EdgeCapturReg", 12, "OutsetReg", 16, "OutclearReg", 20);

    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var desktop = new TreeMap<String, String>();
        for (var component : file.getCircuit("main").getNonWires()) {
            if (!component.getFactory().getName().equals("SocPio")) continue;
            var state = component.getAttributeSet().getValue(PioAttributes.PIO_STATE);
            var label = component.getAttributeSet().getValue(com.cburch.logisim.instance.StdAttr.LABEL);
            desktop.put(label, registers(evolution(state, "X"), Pattern.compile("(get|set)X(\\w+)\\(.*?base\\[(\\d+)\\]", Pattern.DOTALL), 4));
        }
        var registry = ComponentCatalog.INSTANCE.getRegistry().withDefinitions(SocComponents.INSTANCE.definitions());
        var project = ((CircProjectImportResult.Success) CircCodec.INSTANCE.readProject(Files.readString(new File(args[0]).toPath()), registry)).getProject();
        var mobile = new TreeMap<String, String>();
        for (CircuitComponent component : project.getMainCircuit().getComponents()) {
            var files = SocDriverExport.INSTANCE.export(component);
            if (files == null) continue;
            var name = files.getName();
            mobile.put(component.getLabel(), registers(files.getSource(),
                Pattern.compile("(get|set)" + name + "(\\w+)\\(.*?BASE_ADDRESS \\+ (\\d+)u", Pattern.DOTALL), 1));
        }
        var differences = 0;
        for (var label : desktop.keySet()) {
            var same = desktop.get(label).equals(mobile.get(label));
            if (!same) differences++;
            System.out.println((same ? "SAME " : "DIFF ") + label);
            System.out.println("  evolution driver  " + desktop.get(label));
            System.out.println("  mobile driver     " + mobile.get(label));
        }
        System.out.println("evolution PIO decoder offsets " + new TreeMap<>(DECODER));
        System.out.println("RESULT: " + (desktop.size() - differences) + "/" + desktop.size() + " PIO driver register maps match Evolution's exportC");
        System.exit(0);
    }

    /** The calls of PioMenu.exportC (d58a61e0, lines 95-151), with the file chooser removed. */
    private static String evolution(PioState state, String name) {
        var h = new StringWriter();
        var c = new StringWriter();
        var header = new PrintWriter(h);
        var cWriter = new PrintWriter(c);
        int base = state.getStartAddress();
        if (state.getPortDirection() != PioAttributes.PORT_INPUT) {
            SocSupport.addSetterFunction(header, name, "OutputValue", base, 0, true);
            SocSupport.addSetterFunction(cWriter, name, "OutputValue", base, 0, false);
        }
        if (state.getPortDirection() != PioAttributes.PORT_OUTPUT) {
            SocSupport.addGetterFunction(header, name, "InputValue", base, 0, true);
            SocSupport.addGetterFunction(cWriter, name, "InputValue", base, 0, false);
            if (state.getPortDirection() == PioAttributes.PORT_BIDIR) {
                SocSupport.addAllFunctions(header, cWriter, name, "DirectionReg", base, 2);
            }
            if (state.inputGeneratesIrq()) SocSupport.addAllFunctions(header, cWriter, name, "IrqMaskReg", base, 2);
            if (state.inputIsCapturedSynchronisely()) SocSupport.addAllFunctions(header, cWriter, name, "EdgeCapturReg", base, 3);
            if (state.outputSupportsBitManipulations()) {
                SocSupport.addSetterFunction(cWriter, name, "OutsetReg", base, 4, false);
                SocSupport.addSetterFunction(cWriter, name, "OutclearReg", base, 5, false);
            }
        }
        cWriter.flush();
        return c.toString();
    }

    private static String registers(String source, Pattern pattern, int scale) {
        var map = new TreeMap<String, String>();
        // Split per function so the lazy match cannot cross into the next body.
        for (var function : source.split("\\n(?=unsigned int get|void set|uint32_t get)")) {
            var matcher = pattern.matcher(function);
            if (matcher.find()) map.merge(matcher.group(2), matcher.group(1) + "@0x" + Integer.toHexString(Integer.parseInt(matcher.group(3)) * scale),
                (a, b) -> a.compareTo(b) < 0 ? a + "," + b : b + "," + a);
        }
        return map.toString();
    }
}
