import dev.logisim.mobile.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The Mobile side of PokeDesktopOracle and MenuDesktopOracle, through the core calls the editor makes
 * (#345, #347): PinPoke.poke for EditorState.pokePin, and ComponentMenu for the canvas context menu.
 * Prints the same TSV lines as the desktop oracles. The menu reads each component's state from a settled
 * CircuitSimulator; each "CIRC=ELF" argument sends SocLoadExecutableEvent (the canvas "Read ELF file") to that
 * CPU first. "image" runs ImageMenu (#359) on decoded PNGs and writes the stored images to OUT_DIR.
 * Usage: BaseToolsMobileOracle poke FILE.circ | menu FILE.circ [CIRC=ELF ...] | image OUT_DIR PNG...
 */
public final class BaseToolsMobileOracle {
    private static final String[][] PLAN = {
        {"p1", "0", "0", "0", "0"},
        {"t1", "0", "0", "0", "0"},
        {"b4", "0", "0", "2", "2", "3"},
        {"h8", "0", "0", "4", "0"},
        {"t3", "0", "0", "0", "1"},
    };

    public static void main(String[] args) throws Exception {
        if (args[0].equals("image")) { image(args); return; }
        var registry = ComponentCatalog.INSTANCE.getRegistry().withDefinitions(AnnotationLibrary.INSTANCE.definitions());
        var imported = CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[1])), registry);
        if (!(imported instanceof CircProjectImportResult.Success success)) throw new AssertionError(imported);
        var circuit = success.getProject().getMainCircuit();
        if (args[0].equals("poke")) {
            for (var plan : PLAN) {
                var pin = circuit.getComponents().stream().filter(c -> plan[0].equals(c.getLabel())).findFirst().orElseThrow();
                var attributes = pin.getAttributes();
                var tristate = PinPoke.INSTANCE.behavior(attributes).equals("tristate");
                var radix = attributes.getOrDefault("radix", "2");
                // Pin's initial intended value: U for tri-state, the (default 0) initial value otherwise.
                var value = tristate ? LogicVector.Companion.unknown(pin.getWidth()) : LogicVector.Companion.low(pin.getWidth());
                var line = new StringBuilder(plan[0]).append("\tinitial=").append(text(value));
                for (var index = 1; index < plan.length; index++) {
                    value = PinPoke.INSTANCE.poke(value, Integer.parseInt(plan[index]), radix, tristate);
                    line.append("\tpoke(bit ").append(plan[index]).append(")=").append(text(value));
                }
                System.out.println(line);
            }
        } else {
            var states = new CircuitSimulator(256, registry).settle(success.getProject(), new SimulationState(), Map.of(), false, Map.of())
                .getComponentStates();
            var lines = new ArrayList<String>();
            for (var component : circuit.getComponents()) {
                var definition = registry.definition(component.getKind());
                var name = component.getCircuitName() != null ? component.getCircuitName() : definition.getFileName();
                lines.add("component\t" + name + "\t" + ComponentMenu.INSTANCE.describe(
                    ComponentMenu.INSTANCE.component(component, registry, true, states.get(component.getId())), component.getCircuitName()));
            }
            lines.sort(String::compareTo);
            lines.forEach(System.out::println);
            System.out.println("selection(>1)\t-\t" + ComponentMenu.INSTANCE.describe(ComponentMenu.INSTANCE.selection(true), null));
            // CircuitCanvas opens the menu only on a component or wire hit.
            System.out.println("empty-canvas\t-\t(no menu)");
            for (var index = 2; index < args.length; index++) loaded(args[index].split("=", 2), registry);
        }
    }

    private static void loaded(String[] paths, ComponentRegistry registry) throws Exception {
        var imported = CircCodec.INSTANCE.readProject(Files.readString(Path.of(paths[0])), registry);
        if (!(imported instanceof CircProjectImportResult.Success success)) throw new AssertionError(imported);
        var circuit = success.getProject().getMainCircuit();
        var cpu = circuit.getComponents().stream().filter(c -> c.getKind().equals(SocComponents.INSTANCE.getRV32())
            || c.getKind().equals(SocComponents.INSTANCE.getNIOS2())).findFirst().orElseThrow();
        var simulator = new CircuitSimulator(256, registry);
        var state = simulator.settle(circuit, new SimulationState(), Map.of(), false, Map.of());
        state = simulator.settle(circuit, state, Map.of(), false, Map.of(),
            Map.of(cpu.getId(), List.of(new SocLoadExecutableEvent(Files.readAllBytes(Path.of(paths[1]))))));
        var name = registry.definition(cpu.getKind()).getFileName();
        System.out.println("component\t" + name + "(loaded)\t" + ComponentMenu.INSTANCE.describe(
            ComponentMenu.INSTANCE.component(cpu, registry, true, state.getComponentStates().get(cpu.getId())), null));
    }

    /** The Mobile side of ImageMenuDesktopOracle: the platform decodes the PNG, ImageMenu does the rest. */
    private static void image(String[] args) throws Exception {
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        var menu = ImageMenu.INSTANCE;
        for (var size : ImageMenuCases.CACHED_SIZES) {
            var cached = menu.cachedSize(size[0], size[1]);
            System.out.println("cached\t" + size[0] + "x" + size[1] + "\t" + cached.getFirst() + "x" + cached.getSecond());
        }
        for (var index = 2; index < args.length; index++) {
            var file = new File(args[index]);
            var name = file.getName().replace(".png", "");
            var decoded = ImageIO.read(file);
            var image = menu.cached(new ArgbImage(decoded.getWidth(), decoded.getHeight(), ImageMenuCases.argb(decoded)));
            var reset = menu.resetSize(image);
            System.out.println("reset\t" + name + "\twidth=" + reset.get("width") + ",height=" + reset.get("height"));
            for (var frame : ImageMenuCases.FRAMES) {
                var scale = frame[2];
                var line = "optimize\t" + name + "\t" + frame[0] + "x" + frame[1] + " " + scale + "\t";
                var decision = menu.optimization(image, Integer.parseInt(frame[0]), Integer.parseInt(frame[1]), scale);
                if (decision instanceof ImageMenu.Optimization.AlreadyOptimized already) {
                    System.out.println(line + "already " + already.getWidth() + "x" + already.getHeight());
                } else if (decision instanceof ImageMenu.Optimization.Resize resize) {
                    var frameSet = resize.getFrame() == null ? "" : " frame=" + resize.getFrame().getFirst() + "x" + resize.getFrame().getSecond();
                    System.out.println(line + "resize " + resize.getWidth() + "x" + resize.getHeight() + "->" + resize.getTargetWidth() + "x"
                        + resize.getTargetHeight() + frameSet);
                    write(menu.optimize(image, resize.getTargetWidth(), resize.getTargetHeight(), scale),
                        out.resolve(ImageMenuCases.caseName(name, frame) + ".png"));
                }
            }
            write(menu.makeWhiteTransparent(image), out.resolve(name + "-transparent.png"));
            System.out.println("transparent\t" + name + "\t" + name + "-transparent.png");
        }
    }

    private static void write(ArgbImage image, Path path) throws Exception {
        ImageIO.write(ImageMenuCases.image(image.getWidth(), image.getHeight(), image.getPixels()), "png", path.toFile());
    }

    private static String text(LogicVector value) {
        return value.getDisplay().replace('?', 'U');
    }
}
