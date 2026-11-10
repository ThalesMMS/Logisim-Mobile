import com.cburch.logisim.file.Loader;
import java.io.File;
import java.io.FileOutputStream;

/** Opens each listed circuit in Evolution and writes the desktop serialization next to it. */
public final class ResaveDesktop {
    public static void main(String[] args) throws Exception {
        var directory = new File(args[0]);
        for (var id : java.nio.file.Files.readAllLines(new File(directory, "index.txt").toPath())) {
            if (id.isBlank()) continue;
            try {
                var loader = new Loader(null);
                var file = loader.openLogisimFile(new File(directory, id + ".circ"));
                try (var out = new FileOutputStream(new File(directory, id + ".desktop.circ"))) {
                    file.write(out, loader);
                }
            } catch (Throwable failure) {
                System.err.println(id + "\t" + failure);
            }
        }
        System.exit(0);
    }
}
