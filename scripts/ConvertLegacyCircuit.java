import com.cburch.logisim.LogisimVersion;
import com.cburch.logisim.Main;
import com.cburch.logisim.file.Loader;
import java.awt.HeadlessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Resaves a self-contained legacy circuit using Carl Burch's official 2.7.1 reader. */
public final class ConvertLegacyCircuit {
    // The same official binary pinned in examples/compatibility/original-2.7.1/provenance.json.
    private static final String ORIGINAL_JAR_SHA256 =
            "362a78c12ad18c203fed868872c4a01cd9c12141379d92e892bbe2c37e627bc2";

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        try {
            if (args.length != 2) {
                throw new IllegalArgumentException("Usage: java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar "
                        + "scripts/ConvertLegacyCircuit.java input.circ converted.circ");
            }
            verifyOriginalJar();
            convert(Path.of(args[0]), Path.of(args[1]).toAbsolutePath().normalize());
        } catch (HeadlessException error) {
            System.err.println("Conversion stopped on a Logisim dialog. Open the original file in 2.7.1 to review it.");
            System.exit(1);
        } catch (Exception error) {
            System.err.println("Conversion failed: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void verifyOriginalJar() throws Exception {
        var jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        if (!HexFormat.of().formatHex(digest).equals(ORIGINAL_JAR_SHA256)) {
            throw new IllegalArgumentException("Use the official logisim-generic-2.7.1.jar");
        }
    }

    private static void convert(Path input, Path output) throws Exception {
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Output already exists. Choose a new .circ path.");
        }

        // Validate the same bytes that go to the original loader. Its XML parser does not
        // prohibit DOCTYPE, and its library loader can open external files or execute JARs.
        var source = Files.readAllBytes(input);
        requireLegacyProject(source);
        var loader = new Loader(null) {
            @Override public void showError(String description) {
                throw new IllegalArgumentException(description);
            }
        };
        var project = loader.openLogisimFile(new ByteArrayInputStream(source));
        if (project == null) {
            throw new IllegalArgumentException("The original loader could not open the circuit");
        }

        var temporaryDirectory = Files.createTempDirectory(output.getParent(), ".logisim-conversion-");
        var converted = temporaryDirectory.resolve("converted.circ");
        try {
            if (!loader.save(project, converted.toFile())) {
                throw new IOException("The original loader could not save the circuit");
            }
            // No replacement option: the input and existing destinations stay intact.
            Files.move(converted, output);
        } finally {
            Files.deleteIfExists(converted);
            Files.deleteIfExists(temporaryDirectory);
        }
        System.out.println("Converted " + project.getCircuits().size() + " circuit(s), main: "
                + project.getMainCircuit().getName() + " → " + output);
    }

    private static void requireLegacyProject(byte[] source) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(source)).getDocumentElement();
        if (!root.getTagName().equals("project")) {
            throw new IllegalArgumentException("Expected a Logisim .circ project");
        }
        var version = root.getAttribute("source");
        if (!version.matches("[0-9]{1,4}(\\.[0-9]{1,4}){2,3}")
                || LogisimVersion.parse(version).compareTo(LogisimVersion.get(2, 6, 3)) >= 0) {
            throw new IllegalArgumentException("Expected a numeric .circ source version earlier than 2.6.3");
        }
        var libraries = root.getElementsByTagName("lib");
        for (int index = 0; index < libraries.getLength(); index++) {
            var descriptor = ((Element) libraries.item(index)).getAttribute("desc");
            if (!descriptor.startsWith("#")) {
                throw new IllegalArgumentException("External .circ and JAR libraries require manual conversion");
            }
        }
    }
}
