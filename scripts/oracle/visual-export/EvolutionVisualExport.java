import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.generic.TikZWriter;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Headless copy of Evolution d58a61e0 ExportImage.ExportThread.export and paintExportBackground: bounds are the
 * circuit bounds expanded by BORDER_SIZE 5, the given scale, ImageIO for GIF/JPEG/PNG and TikZWriter for TikZ/SVG.
 * Only change: no Canvas exists, so the circuit is measured with a BufferedImage graphics context and the
 * background is AppPreferences.CANVAS_BG_COLOR (the canvas background in the original).
 * Usage: EvolutionVisualExport file.circ circuit scale printerView(true|false) outDir [shaped|rectangular]
 * The optional last argument sets AppPreferences.GATE_SHAPE (run with an in-memory PreferencesFactory).
 * Prints "bounds x y w h" and then "format width height file" per written file.
 */
public final class EvolutionVisualExport {
    public static void main(String[] args) throws Exception {
        try { run(args); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        Main.headless = true;
        // Before loading: AbstractGate.computeLabel and NotGate.configureLabel read the shape when the instance is created.
        if (args.length > 5) AppPreferences.GATE_SHAPE.set(args[5]);
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        var circuit = file.getCircuit(args[1]);
        var scale = Double.parseDouble(args[2]);
        var printerView = Boolean.parseBoolean(args[3]);
        var out = new File(args[4]);
        out.mkdirs();
        var canvasBackground = new Color(AppPreferences.CANVAS_BG_COLOR.get());
        var measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getGraphics();
        var bounds = circuit.getBounds(measure).expand(5);
        var width = (int) Math.round(bounds.getWidth() * scale);
        var height = (int) Math.round(bounds.getHeight() * scale);
        System.out.println("bounds\t" + bounds.getX() + "\t" + bounds.getY() + "\t" + bounds.getWidth() + "\t" + bounds.getHeight());
        for (var format : new String[] {"png", "gif", "jpg", "tikz", "svg"}) {
            var vector = format.equals("tikz") || format.equals("svg");
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics base;
            if (vector) {
                var writer = new TikZWriter();
                writer.setSvgMode(format.equals("svg"));
                base = writer;
            } else {
                base = image.getGraphics();
            }
            var g = base.create();
            if (!printerView || !vector) {
                g.setColor(printerView ? Color.WHITE : canvasBackground);
                g.fillRect(0, 0, width, height);
            }
            g.setColor(Color.BLACK);
            ((Graphics2D) g).scale(scale, scale);
            g.translate(-bounds.getX(), -bounds.getY());
            CircuitState state = project.getCircuitState(circuit);
            circuit.draw(new ComponentDrawContext(null, circuit, state, base, g, printerView), null);
            var target = new File(out, circuit.getName() + "." + (format.equals("tikz") ? "tex" : format));
            switch (format) {
                case "png" -> ImageIO.write(image, "PNG", target);
                case "gif" -> ImageIO.write(image, "GIF", target);
                case "jpg" -> ImageIO.write(image, "JPEG", target);
                case "tikz" -> ((TikZWriter) g).writeFile(target);
                default -> ((TikZWriter) g).writeSvg(width, height, target);
            }
            g.dispose();
            System.out.println(format + "\t" + width + "\t" + height + "\t" + target.getName());
        }
        project.getSimulator().shutDown();
    }
}
