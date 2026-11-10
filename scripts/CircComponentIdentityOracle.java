import com.cburch.logisim.Main;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies mobile component identities are desktop-readable, not desktop-persisted metadata. */
public final class CircComponentIdentityOracle {
    private static final String DESKTOP_SHA256 =
        "0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01";
    private static final String ID_ATTRIBUTE = "logisim-mobile-id";
    private static final Set<String> EXPECTED_IDS = Set.of(
        "pin: entrada.&\"β\"", "gate<opaque>", "constant.42", "child instance:β", "child:pin");
    private static final String FIXTURE = """
        <project source="5.1.0" version="1.0">
          <lib name="w" desc="#Wiring"/><lib name="g" desc="#Gates"/>
          <main name="main"/>
          <circuit name="main" id="main-stable">
            <comp lib="w" name="Pin" loc="(100,100)" logisim-mobile-id="pin: entrada.&amp;&quot;β&quot;">
              <a name="appearance" val="classic"/><a name="facing" val="north"/><a name="width" val="8"/>
            </comp>
            <comp lib="g" name="NOT Gate" loc="(200,100)" logisim-mobile-id="gate&lt;opaque&gt;">
              <a name="facing" val="west"/><a name="width" val="8"/><a name="size" val="30"/>
            </comp>
            <comp lib="w" name="Constant" loc="(300,100)" logisim-mobile-id="constant.42">
              <a name="width" val="8"/><a name="value" val="0x2a"/>
            </comp>
            <comp name="Child" loc="(400,100)" logisim-mobile-id="child instance:β"/>
          </circuit>
          <circuit name="Child" id="child-stable">
            <a name="appearance" val="classic"/>
            <comp lib="w" name="Pin" loc="(100,100)" logisim-mobile-id="child:pin">
              <a name="appearance" val="classic"/><a name="facing" val="east"/><a name="width" val="8"/>
            </comp>
          </circuit>
        </project>
        """;

    private record Port(int x, int y, int width, int type, boolean exclusive) {}
    private record Geometry(String factory, int x, int y, int left, int top,
        int width, int height, List<Port> ports) {}

    private static Map<String, List<Geometry>> geometry(LogisimFile file) {
        var result = new LinkedHashMap<String, List<Geometry>>();
        for (var circuit : file.getCircuits()) {
            var components = new ArrayList<Geometry>();
            for (var component : circuit.getNonWires()) {
                var ports = component.getEnds().stream().map(end -> new Port(
                    end.getLocation().getX(), end.getLocation().getY(), end.getWidth().getWidth(),
                    end.getType(), end.isExclusive())).toList();
                var bounds = component.getBounds();
                components.add(new Geometry(component.getFactory().getName(),
                    component.getLocation().getX(), component.getLocation().getY(),
                    bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), ports));
            }
            components.sort(Comparator.comparing(Geometry::x).thenComparing(Geometry::y)
                .thenComparing(Geometry::factory));
            result.put(circuit.getName(), List.copyOf(components));
        }
        return result;
    }

    private static LogisimFile open(Path path) throws Exception {
        var file = new Loader(null).openLogisimFile(path.toFile());
        if (file == null) throw new AssertionError("Desktop did not open " + path.getFileName());
        return file;
    }

    private static void sameGeometry(Map<String, List<Geometry>> expected, LogisimFile actual,
        String stage) {
        var geometry = geometry(actual);
        if (!expected.equals(geometry)) {
            throw new AssertionError(stage + " changed geometry\nexpected=" + expected + "\nactual=" + geometry);
        }
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(1);
        });
        Main.headless = true;
        Path desktopJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(desktopJar)));
        if (!DESKTOP_SHA256.equals(hash)) throw new AssertionError("Unqualified desktop JAR SHA-256: " + hash);
        Path directory = Files.createTempDirectory("circ-component-identities-");
        var opened = new ArrayList<LogisimFile>();
        try {
            Path baselinePath = directory.resolve("baseline.circ");
            Path annotatedPath = directory.resolve("annotated.circ");
            Files.writeString(baselinePath, FIXTURE.replaceAll(" " + ID_ATTRIBUTE + "=\"[^\"]*\"", ""));
            Files.writeString(annotatedPath, FIXTURE);
            var baseline = open(baselinePath);
            opened.add(baseline);
            var expected = geometry(baseline);
            if (expected.size() != 2 || expected.values().stream().mapToInt(List::size).sum() != 5) {
                throw new AssertionError("Desktop baseline omitted fixture components: " + expected);
            }
            var annotated = open(annotatedPath);
            opened.add(annotated);
            sameGeometry(expected, annotated, "Desktop read of opaque component identities");
            System.out.println("PASS desktop SHA-256 " + hash);
            System.out.println("PASS desktop accepts 5 opaque component identities across 2 circuits with unchanged geometry");

            Path savedPath = directory.resolve("desktop-saved.circ");
            if (!annotated.getLoader().save(annotated, savedPath.toFile())) {
                throw new AssertionError("Desktop save failed");
            }
            if (Files.readString(savedPath).contains(ID_ATTRIBUTE)) {
                throw new AssertionError("Pinned desktop unexpectedly persisted mobile identity attributes");
            }
            var saved = open(savedPath);
            opened.add(saved);
            sameGeometry(expected, saved, "Desktop save/reload");
            System.out.println("PASS desktop save/reload retains geometry and discards logisim-mobile-id; desktop identity persistence is not guaranteed");

            var imported = CircCodec.INSTANCE.readProject(FIXTURE);
            if (!(imported instanceof CircProjectImportResult.Success success)) {
                throw new AssertionError("Mobile did not import identity fixture: " + imported);
            }
            var ids = success.getProject().getCircuits().stream()
                .flatMap(circuit -> circuit.getComponents().stream()).map(component -> component.getId())
                .collect(java.util.stream.Collectors.toSet());
            if (!EXPECTED_IDS.equals(ids)) throw new AssertionError("Mobile changed opaque identities: " + ids);
            String exported = CircCodec.INSTANCE.writeProject(success.getProject());
            if (exported.split(ID_ATTRIBUTE + "=", -1).length - 1 != EXPECTED_IDS.size()) {
                throw new AssertionError("Mobile export omitted component identity attributes");
            }
            var reimported = CircCodec.INSTANCE.readProject(exported);
            if (!(reimported instanceof CircProjectImportResult.Success restored)) {
                throw new AssertionError("Mobile did not reopen its identity export: " + reimported);
            }
            var restoredIds = restored.getProject().getCircuits().stream()
                .flatMap(circuit -> circuit.getComponents().stream()).map(component -> component.getId())
                .collect(java.util.stream.Collectors.toSet());
            if (!EXPECTED_IDS.equals(restoredIds)) {
                throw new AssertionError("Mobile export/reimport changed opaque identities: " + restoredIds);
            }
            Path exportedPath = directory.resolve("mobile-export.circ");
            Files.writeString(exportedPath, exported);
            var mobileExport = open(exportedPath);
            opened.add(mobileExport);
            sameGeometry(expected, mobileExport, "Desktop read of mobile export");
            System.out.println("PASS mobile import/export retains 5 opaque component identities and desktop geometry");
        } finally {
            for (var file : opened) file.stopAutosaveThread(true);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.exit(0);
    }
}
