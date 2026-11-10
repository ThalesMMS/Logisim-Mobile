import com.cburch.logisim.Main;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.ComponentGeometryKt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

/** Checks mobile exports using the desktop reader and its port geometry. */
public final class CircDesktopCompatibility {
    public static void main(String[] paths) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(1);
        });
        Main.headless = true;
        int checked = 0;
        Path temporary = Files.createTempDirectory("circ-mobile-export-");
        try {
            for (String raw : paths) {
                Path source = Path.of(raw);
                var imported = CircCodec.INSTANCE.readProject(Files.readString(source));
                if (!(imported instanceof CircProjectImportResult.Success success)) {
                    throw new AssertionError(source + ": " + imported);
                }
                Path exported = temporary.resolve(source.getFileName());
                Files.writeString(exported, CircCodec.INSTANCE.writeProject(success.getProject()));
                var before = new Loader(null).openLogisimFile(source.toFile());
                var after = new Loader(null).openLogisimFile(exported.toFile());
                var beforeProject = new Project(before);
                var afterProject = new Project(after);
                try {
                    if (!before.getMainCircuit().getName().equals(after.getMainCircuit().getName())) {
                        throw new AssertionError("Main circuit changed: " + source);
                    }
                    if (before.getCircuits().size() != after.getCircuits().size()) {
                        throw new AssertionError("Circuit definitions changed: " + source);
                    }
                    for (var original : before.getCircuits()) {
                        var reopened = after.getCircuit(original.getName());
                        var originalComponents = new ArrayList<Component>(original.getNonWires());
                        var reopenedComponents = new ArrayList<Component>(reopened.getNonWires());
                        var order = Comparator.comparing((Component c) -> c.getLocation().getX())
                            .thenComparing(c -> c.getLocation().getY());
                        originalComponents.sort(order);
                        reopenedComponents.sort(order);
                        if (originalComponents.size() != reopenedComponents.size()) {
                            throw new AssertionError("Component count changed: " + source);
                        }
                        for (int i = 0; i < originalComponents.size(); i++) {
                            var a = originalComponents.get(i);
                            var b = reopenedComponents.get(i);
                            if (!a.getFactory().getName().equals(b.getFactory().getName()) || !a.getEnds().equals(b.getEnds())) {
                                throw new AssertionError("Port geometry changed: " + source + " " + a + " " + a.getEnds() + " -> " + b.getEnds());
                            }
                            var mobileCircuit = success.getProject().circuit(original.getName());
                            var mobile = mobileCircuit.getComponents().stream().filter(c ->
                                c.getPosition().getX() == a.getLocation().getX() &&
                                c.getPosition().getY() == a.getLocation().getY()).findFirst().orElseThrow();
                            var mobileEnds = new ArrayList<String>();
                            var widths = ComponentGeometryKt.portWidths(mobile);
                            for (var point : ComponentGeometryKt.inputPorts(mobile)) {
                                mobileEnds.add(point.getX() + ":" + point.getY() + ":" + widths.get(point) + ":1");
                            }
                            for (var point : ComponentGeometryKt.outputPorts(mobile)) {
                                mobileEnds.add(point.getX() + ":" + point.getY() + ":" + widths.get(point) + ":2");
                            }
                            var desktopEnds = new ArrayList<String>();
                            for (var end : a.getEnds()) {
                                for (int direction : new int[] {1, 2}) if ((end.getType() & direction) != 0) {
                                    desktopEnds.add(end.getLocation().getX() + ":" + end.getLocation().getY() + ":" + end.getWidth().getWidth() + ":" + direction);
                                }
                            }
                            mobileEnds.sort(String::compareTo);
                            desktopEnds.sort(String::compareTo);
                            if (a.getFactory().getName().equals("Tunnel") || a.getFactory().getName().equals("Splitter")) {
                                mobileEnds = new ArrayList<>(mobileEnds.stream().map(value -> value.substring(0, value.lastIndexOf(':'))).distinct().sorted().toList());
                                desktopEnds = new ArrayList<>(desktopEnds.stream().map(value -> value.substring(0, value.lastIndexOf(':'))).distinct().sorted().toList());
                            }
                            if (!mobileEnds.equals(desktopEnds)) {
                                throw new AssertionError("Mobile ports differ: " + source + " " + a.getFactory().getName() + " " + desktopEnds + " -> " + mobileEnds);
                            }
                            checked++;
                        }
                    }
                } finally {
                    beforeProject.getSimulator().shutDown();
                    afterProject.getSimulator().shutDown();
                }
                System.out.println("PASS desktop reopened mobile export: " + source.getFileName());
            }
            System.out.println("PASS " + checked + " desktop component port layouts preserved");
        } finally {
            try (var files = Files.list(temporary)) {
                for (var file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(temporary);
        }
        System.exit(0);
    }
}
