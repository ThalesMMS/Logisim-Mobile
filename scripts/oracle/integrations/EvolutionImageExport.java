import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.generic.TikZWriter;
import com.cburch.logisim.proj.Project;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Headless copy of Evolution's ExportImage.ExportThread.export (d58a61e0): same bounds
 * (circuit bounds expanded by BORDER_SIZE 5), scale, white printer-view background and
 * writers (ImageIO GIF/JPEG, TikZWriter TikZ/SVG). The only change is that no Canvas
 * exists, so the circuit is measured with a BufferedImage graphics context.
 * Usage: EvolutionImageExport file.circ circuit scale outDir
 * Prints "format<TAB>width<TAB>height<TAB>file" per written file.
 */
public final class EvolutionImageExport {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        var circuit = file.getCircuit(args[1]);
        var scale = Double.parseDouble(args[2]);
        var out = new File(args[3]);
        out.mkdirs();
        var measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getGraphics();
        var bounds = circuit.getBounds(measure).expand(5);
        var width = (int) Math.round(bounds.getWidth() * scale);
        var height = (int) Math.round(bounds.getHeight() * scale);
        System.out.println("bounds\t" + bounds.getX() + "\t" + bounds.getY() + "\t" + bounds.getWidth() + "\t" + bounds.getHeight());
        for (var format : new String[] {"gif", "jpg", "tikz", "svg"}) {
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
            // ExportImage.paintExportBackground with printerView=true: vector formats keep no background.
            if (!vector) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
            }
            g.setColor(Color.BLACK);
            ((Graphics2D) g).scale(scale, scale);
            g.translate(-bounds.getX(), -bounds.getY());
            CircuitState state = project.getCircuitState(circuit);
            circuit.draw(new ComponentDrawContext(null, circuit, state, base, g, true), null);
            var target = new File(out, circuit.getName() + "." + (format.equals("tikz") ? "tex" : format));
            switch (format) {
                case "gif" -> ImageIO.write(image, "GIF", target);
                case "jpg" -> ImageIO.write(image, "JPEG", target);
                case "tikz" -> ((TikZWriter) g).writeFile(target);
                default -> ((TikZWriter) g).writeSvg(width, height, target);
            }
            g.dispose();
            System.out.println(format + "\t" + width + "\t" + height + "\t" + target.getName());
        }
        project.getSimulator().shutDown();
        System.exit(0);
    }
}
