import com.cburch.logisim.Main;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.base.ImageAttributes;
import dev.logisim.mobile.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reopens real mobile .circ output in the original desktop implementation. */
public final class AnnotationDesktopOracle {
    private static CircProjectImportResult.Success read(String xml, ComponentRegistry registry) {
        var result = CircCodec.INSTANCE.readProject(xml, registry);
        if (!(result instanceof CircProjectImportResult.Success success)) throw new AssertionError(result);
        return success;
    }

    private static List<String> desktopPorts(Component component) {
        var result = new ArrayList<String>();
        for (var end : component.getEnds()) result.add(end.getLocation().getX() + ":" + end.getLocation().getY() + ":" + end.getWidth().getWidth() + ":" + end.getType());
        result.sort(String::compareTo);
        return result;
    }

    private static List<String> mobilePorts(CircuitComponent component, ComponentRegistry registry) {
        var result = new ArrayList<String>();
        for (var port : ComponentGeometryKt.layout(component, registry).getPorts()) result.add(port.getPosition().getX() + ":" + port.getPosition().getY() + ":" + port.getWidth() + ":" + switch (port.getDirection()) { case INPUT -> 1; case OUTPUT -> 2; case INOUT -> 3; });
        result.sort(String::compareTo);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
        Main.headless = true;
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        var registry = ComponentCatalog.INSTANCE.getRegistry().withDefinitions(AnnotationLibrary.INSTANCE.definitions());
        var project = read(Files.readString(source), registry).getProject();
        var mobileOutput = output.resolve("mobile-annotations.circ");
        Files.writeString(mobileOutput, CircCodec.INSTANCE.writeProject(project, registry));
        var loader = new Loader(null);
        var desktop = loader.openLogisimFile(mobileOutput.toFile());
        var desktopProject = new Project(desktop);
        try {
            int checked = 0;
            for (var circuit : desktop.getCircuits()) {
                var mobileCircuit = project.circuit(circuit.getName());
                if (mobileCircuit.getComponents().size() != circuit.getNonWires().size()) throw new AssertionError("Component count differs");
                for (var component : circuit.getNonWires()) {
                    var mobile = mobileCircuit.getComponents().stream().filter(item -> item.getPosition().getX() == component.getLocation().getX() && item.getPosition().getY() == component.getLocation().getY()).findFirst().orElseThrow();
                    var actual = desktopPorts(component);
                    var expected = mobilePorts(mobile, registry);
                    if (!actual.equals(expected)) throw new AssertionError(component.getFactory().getName() + " port geometry " + actual + " != " + expected);
                    if (AnnotationLibrary.INSTANCE.isAnnotation(mobile)) {
                        var bounds = AnnotationLibrary.INSTANCE.bounds(mobile);
                        var shown = component.getBounds();
                        if (bounds.getLeft() != shown.getX() || bounds.getTop() != shown.getY() || bounds.getWidth() != shown.getWidth() || bounds.getHeight() != shown.getHeight()) throw new AssertionError("Annotation bounds " + bounds + " != " + shown);
                    }
                    if (component.getAttributeSet() instanceof ImageAttributes image) {
                        var pixels = image.getCachedImage();
                        if (pixels == null || pixels.getWidth() != 2 || pixels.getHeight() != 1 || pixels.getRGB(0, 0) != 0xffff0000 || pixels.getRGB(1, 0) != 0x8000ff00) throw new AssertionError("Desktop image decoder changed embedded RGBA pixels");
                    }
                    checked++;
                }
            }
            var desktopOutput = output.resolve("desktop-annotations.circ");
            if (!loader.save(desktop, desktopOutput.toFile())) throw new AssertionError("Desktop save failed");
            var reimported = read(Files.readString(desktopOutput), registry).getProject();
            for (var original : project.getMainCircuit().getComponents()) {
                if (!AnnotationLibrary.INSTANCE.isAnnotation(original)) continue;
                var restored = reimported.getMainCircuit().getComponents().stream().filter(item -> item.getPosition().equals(original.getPosition()) && item.getKind().equals(original.getKind())).findFirst().orElseThrow();
                var keys = original.getKind().equals(AnnotationLibrary.INSTANCE.getImage()) ? List.of("image", "width", "height", "scale", "attribution") : List.of("text", "font", "color", "halign", "valign");
                for (String key : keys) if (!original.getAttributes().get(key).equals(restored.getAttributes().get(key))) throw new AssertionError("Desktop roundtrip changed " + key);
            }
            System.out.println("PASS desktop reopened and saved " + checked + " components: Text/Image geometry, embedded RGBA, rotated custom ports, annotation attributes");
        } finally { desktopProject.getSimulator().shutDown(); }
        System.exit(0);
    }
}
