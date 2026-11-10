import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectBundleManifest;
import dev.logisim.mobile.core.CircBundleCodec;
import dev.logisim.mobile.core.CircBundleImportResult;
import dev.logisim.mobile.core.CircLibraryCodec;
import dev.logisim.mobile.core.CircLibraryImportResult;
import dev.logisim.mobile.core.CircuitLibraryResolver;
import dev.logisim.mobile.core.CircuitLibrarySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipFile;

/** Opens a mobile-exported external-library bundle with the desktop reader. */
public final class CircBundleDesktopOracle {
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
        Main.headless = true;
        Path directory = Files.createTempDirectory("circ-library-oracle-");
        try {
            String child = "<project source=\"3.8.0-HC\" note=\"library-metadata\"><lib name=\"w\" desc=\"#Wiring\"/><main name=\"Child\" marker=\"keep\"/><circuit name=\"Child\" id=\"child-stable\"><comp lib=\"w\" name=\"Pin\" loc=\"(10,10)\"/></circuit></project>";
            String root = "<project source=\"5.1.0\"><lib name=\"child\" desc=\"file#/private/example/child.circ\"/><main name=\"main\"/><circuit name=\"main\" id=\"root-stable\"><comp lib=\"child\" name=\"Child\" loc=\"(100,100)\"/></circuit></project>";
            CircuitLibraryResolver resolver = (from, reference) -> new CircuitLibrarySource("/private/example/child.circ", child, "1");
            var resolved = ((CircLibraryImportResult.Success) CircLibraryCodec.INSTANCE.read(root, "/private/example/main.circ", resolver)).getResolved();
            var bundle = CircLibraryCodec.INSTANCE.bundle(resolved, resolved.getProject());
            byte[] archive = CircBundleCodec.INSTANCE.write(bundle);
            Path zipped = directory.resolve("portable.lsebdl");
            Files.write(zipped, archive);
            try (ZipFile zip = new ZipFile(zipped.toFile())) {
                var manifest = ProjectBundleManifest.getManifestInfo(zip, null);
                if (manifest == null || !manifest.getMainLogisimFilename().equals("main.circ")) {
                    throw new AssertionError("Desktop did not accept the mobile manifest");
                }
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    Path file = directory.resolve(entry.getName()).normalize();
                    if (!file.startsWith(directory)) throw new AssertionError("Archive escaped its directory");
                    Files.createDirectories(file.getParent());
                    Files.write(file, zip.getInputStream(entry).readAllBytes());
                    if (Files.readString(file).contains("/private/example")) throw new AssertionError("Absolute library paths leaked into archive");
                }
            }
            var desktop = new Loader(null).openLogisimFile(directory.resolve("main.circ").toFile());
            var project = new Project(desktop);
            try {
                var instance = desktop.getMainCircuit().getNonWires().iterator().next();
                if (!(instance.getFactory() instanceof SubcircuitFactory subcircuit) || !subcircuit.getSubcircuit().getName().equals("Child")) {
                    throw new AssertionError("Desktop did not resolve the portable child circuit");
                }
                if (subcircuit.getSubcircuit().getNonWires().size() != 1) throw new AssertionError("Child definition changed");
            } finally {
                project.getSimulator().shutDown();
            }
            var reopened = ((CircBundleImportResult.Success) CircBundleCodec.INSTANCE.read(archive)).getBundle();
            var mobile = ((CircLibraryImportResult.Success) CircLibraryCodec.INSTANCE.read(reopened)).getResolved().getProject();
            if (mobile.getCircuits().size() != 2) throw new AssertionError("Mobile did not reopen both definitions");
            System.out.println("PASS desktop accepted mobile manifest, resolved portable library, and mobile reopened both definitions");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.exit(0);
    }
}
