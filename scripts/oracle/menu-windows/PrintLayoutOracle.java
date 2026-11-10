import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.main.Print;
import com.cburch.logisim.proj.Project;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitDocument;
import dev.logisim.mobile.core.CircuitProject;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.PrintHeader;
import dev.logisim.mobile.core.VisualPdf;
import dev.logisim.mobile.core.VisualPdfKt;
import dev.logisim.mobile.core.VisualPdfPage;
import dev.logisim.mobile.core.VisualPdfRaster;
import dev.logisim.mobile.core.VisualPdfSheet;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sun.print.ProxyGraphics2D;

/**
 * Compares File > Print: Evolution's {@code Print.MyPrintable} against Mobile's paged PDF
 * ({@code VisualPdf.encodePages}, {@code PrintHeader}, {@code printableCircuits}).
 *
 * <p>Evolution prints on {@code new PageFormat()} (Letter, one-inch margins). Mobile encodes the same
 * page here ({@code VisualPdfPage(612, 792, 72)}) with a drawing of Evolution's bounds expanded by 4,
 * one pixel per unit, and a header raster of Evolution's header size at Mobile's two pixels per point.
 * For each page the oracle maps the corners of the circuit bounds and of the header line to page
 * points on both sides. The circuit itself is not drawn: Evolution needs a canvas window for that, and
 * the drawing is the one compared by File > Export Image.
 */
public final class PrintLayoutOracle {
    static final double PAGE_WIDTH = 612, PAGE_HEIGHT = 792, MARGIN = 72;

    /** What MyPrintable did to its graphics before drawing the circuit. */
    static final class Log {
        AffineTransform circuit;
        String header;
        AffineTransform headerTransform;
        double headerX, headerY, headerWidth, headerHeight, headerAscent;
    }

    static final class Recorder extends ProxyGraphics2D {
        final Log log;
        Recorder(Graphics2D delegate, Log log) { super(delegate, null); this.log = log; }
        @Override public Graphics create() { return new Recorder((Graphics2D) getDelegate().create(), log); }
        @Override public void drawString(String text, int x, int y) {
            var metrics = getFontMetrics();
            log.header = text;
            log.headerTransform = getTransform();
            log.headerX = x;
            log.headerY = y;
            log.headerWidth = metrics.stringWidth(text);
            log.headerHeight = metrics.getHeight();
            log.headerAscent = metrics.getAscent();
            super.drawString(text, x, y);
        }
        @Override public void setClip(Shape clip) {
            log.circuit = getTransform(); // the last call before the circuit is drawn
            super.setClip(clip);
        }
    }

    record Page(String name, Bounds bounds, Log log) {}

    public static void main(String[] args) {
        int status;
        try {
            status = run(new File(args[0]));
        } catch (Throwable error) {
            error.printStackTrace();
            status = 2;
        }
        System.exit(status);
    }

    static int run(File fixtures) throws Exception {
        com.cburch.logisim.Main.headless = true;
        int differences = 0, same = 0;
        differences += headerTemplates();
        File fixture = new File(fixtures, "print-pages.circ");
        Project project = new Project(new Loader(null).openLogisimFile(fixture));
        // Print.doPrint lists the circuits of CircuitJList(proj, true): those with a drawing, in file order.
        List<Circuit> circuits = project.getLogisimFile().getCircuits().stream()
                .filter(circuit -> circuit.getBounds() != Bounds.EMPTY_BOUNDS).toList();
        Object imported = CircCodec.INSTANCE.readProject(Files.readString(fixture.toPath()), ComponentCatalog.INSTANCE.getRegistry());
        CircuitProject mobileProject = ((CircProjectImportResult.Success) imported).getProject();
        List<String> evolutionNames = circuits.stream().map(Circuit::getName).toList();
        List<String> mobileNames = VisualPdfKt.printableCircuits(mobileProject).stream().map(CircuitDocument::getName).toList();
        boolean sameList = evolutionNames.equals(mobileNames);
        System.out.println((sameList ? "SAME" : "DIFF") + " printable circuits evolution=" + evolutionNames + " mobile=" + mobileNames);
        if (!sameList) differences++;

        Class<?> printableClass = Class.forName("com.cburch.logisim.gui.main.Print$MyPrintable");
        var constructor = printableClass.getDeclaredConstructor(Project.class, List.class, String.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        for (String header : List.of("%n (%p of %P)", "", "Página %p/%P – %n %% %x")) {
            for (boolean rotate : List.of(true, false)) {
                Printable printable = (Printable) constructor.newInstance(project, circuits, header, rotate, true);
                List<Page> pages = new ArrayList<>();
                for (int index = 0; index < circuits.size(); index++) {
                    BufferedImage paper = new BufferedImage((int) PAGE_WIDTH, (int) PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = paper.createGraphics();
                    graphics.setClip(0, 0, (int) PAGE_WIDTH, (int) PAGE_HEIGHT); // a printer page always has a clip
                    Log log = new Log();
                    try {
                        printable.print(new Recorder(graphics, log), new PageFormat(), index);
                    } catch (NullPointerException expected) {
                        // MyPrintable reaches for the project window's canvas only to draw the circuit.
                    }
                    if (log.circuit == null) throw new IllegalStateException("No layout for page " + index);
                    pages.add(new Page(circuits.get(index).getName(), circuits.get(index).getBounds(graphics).expand(4), log));
                    graphics.dispose();
                }
                String pdf = new String(mobilePdf(pages, rotate), StandardCharsets.ISO_8859_1);
                List<String> contents = new ArrayList<>();
                Matcher stream = Pattern.compile("<<  /Length \\d+ >>\\nstream\\n(.*?)\\nendstream", Pattern.DOTALL).matcher(pdf);
                while (stream.find()) contents.add(stream.group(1));
                for (int index = 0; index < pages.size(); index++) {
                    String verdict = comparePage(pages.get(index), contents.get(index), header, index, pages.size());
                    System.out.println(verdict + " header=\"" + header + "\" rotate=" + rotate + " page=" + (index + 1) + " " + pages.get(index).name());
                    if (verdict.startsWith("SAME")) same++;
                    else differences++;
                }
            }
        }
        System.out.println("SUMMARY same=" + same + " differences=" + differences);
        return differences == 0 ? 0 : 1;
    }

    /** Evolution's private Print.format against PrintHeader.expand on edge cases and random templates. */
    static int headerTemplates() throws Exception {
        Method format = Print.class.getDeclaredMethod("format", String.class, int.class, int.class, String.class);
        format.setAccessible(true);
        List<String> templates = new ArrayList<>(List.of("%n (%p of %P)", "", "%", "%%", "%%%", "%x", "100%", "%n%p%P", "p%", "%%n", "%n %"));
        Random random = new Random(343);
        String alphabet = "%%%npPx ab%";
        for (int i = 0; i < 20000; i++) {
            StringBuilder template = new StringBuilder();
            for (int j = random.nextInt(9); j > 0; j--) template.append(alphabet.charAt(random.nextInt(alphabet.length())));
            templates.add(template.toString());
        }
        int differences = 0;
        for (String template : templates) {
            for (String name : List.of("main", "Circuito %P", "")) {
                String evolution = (String) format.invoke(null, template, 2, 7, name);
                String mobile = PrintHeader.INSTANCE.expand(template, name, 2, 7);
                if (!evolution.equals(mobile)) {
                    if (differences++ < 10) System.out.println("DIFF header template=\"" + template + "\" name=\"" + name
                            + "\" evolution=\"" + evolution + "\" mobile=\"" + mobile + "\"");
                }
            }
        }
        System.out.println((differences == 0 ? "SAME" : "DIFF") + " header templates=" + templates.size() * 3 + " differences=" + differences);
        return differences;
    }

    static byte[] mobilePdf(List<Page> pages, boolean rotate) {
        return VisualPdf.INSTANCE.encodePages(pages.size(), new VisualPdfPage(PAGE_WIDTH, PAGE_HEIGHT, MARGIN), rotate, index -> {
            Page page = pages.get(index);
            VisualPdfRaster drawing = raster(page.bounds().getWidth(), page.bounds().getHeight());
            Log log = page.log();
            // Mobile rasterizes the header at two pixels per point (24 sp text shown at 12 pt).
            VisualPdfRaster header = log.header == null ? null
                    : raster((int) Math.round(2 * log.headerWidth), (int) Math.round(2 * log.headerHeight));
            return new VisualPdfSheet(drawing, header, 1.0);
        });
    }

    static VisualPdfRaster raster(int width, int height) {
        return new VisualPdfRaster(width, height, new int[width * height]);
    }

    /** Matrices of a page's content stream: an optional rotation, then header and circuit placements. */
    static String comparePage(Page page, String content, String template, int index, int count) {
        AffineTransform rotation = new AffineTransform();
        Matcher rotated = Pattern.compile("^q\\n(\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) cm\\n(?!/)").matcher(content);
        if (rotated.find()) rotation = matrix(rotated);
        AffineTransform header = null, circuit = null;
        Matcher image = Pattern.compile("q\\n(\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) cm\\n/(Header|Circuit) Do").matcher(content);
        while (image.find()) {
            AffineTransform placed = new AffineTransform(rotation);
            placed.concatenate(matrix(image));
            if (image.group(7).equals("Header")) header = placed;
            else circuit = placed;
        }
        List<String> problems = new ArrayList<>();
        Log log = page.log();
        Bounds bounds = page.bounds();
        // Circuit corners: Evolution's transform against the PDF image placement (unit square, y up).
        double[][] corners = {{bounds.getX(), bounds.getY()}, {bounds.getX() + bounds.getWidth(), bounds.getY() + bounds.getHeight()},
                {bounds.getX() + bounds.getWidth(), bounds.getY()}};
        double worst = 0;
        for (double[] corner : corners) {
            Point2D evolution = log.circuit.transform(new Point2D.Double(corner[0], corner[1]), null);
            Point2D mobile = pdfToPage(circuit, (corner[0] - bounds.getX()) / bounds.getWidth(), 1 - (corner[1] - bounds.getY()) / bounds.getHeight());
            worst = Math.max(worst, evolution.distance(mobile));
        }
        if (worst > 1e-6) problems.add(String.format("circuit corners differ by %.4f pt", worst));
        String expected = PrintHeader.INSTANCE.expand(template, page.name(), index + 1, count);
        if (template.isEmpty() != (log.header == null)) problems.add("header presence");
        double headerOffset = 0;
        if (log.header != null) {
            if (!log.header.equals(expected)) problems.add("header text evolution=\"" + log.header + "\" mobile=\"" + expected + "\"");
            if (header == null) problems.add("no Mobile header");
            else {
                double top = log.headerY - log.headerAscent;
                Point2D evolutionTopLeft = log.headerTransform.transform(new Point2D.Double(log.headerX, top), null);
                Point2D evolutionBottomRight = log.headerTransform.transform(new Point2D.Double(log.headerX + log.headerWidth, top + log.headerHeight), null);
                headerOffset = Math.max(evolutionTopLeft.distance(pdfToPage(header, 0, 1)), evolutionBottomRight.distance(pdfToPage(header, 1, 0)));
                // Evolution rounds the header's left edge to a whole point; Mobile centers it exactly.
                if (headerOffset > 0.5 + 1e-9) problems.add(String.format("header box differs by %.4f pt", headerOffset));
            }
        }
        String scale = String.format("scale=%.6f", Math.sqrt(Math.abs(log.circuit.getDeterminant())));
        String turned = Math.abs(log.circuit.getShearX()) > 1e-9 ? " rotated" : "";
        if (problems.isEmpty()) return String.format("SAME %s%s header-offset=%.3f", scale, turned, headerOffset);
        return "DIFF " + scale + turned + " " + String.join("; ", problems);
    }

    static AffineTransform matrix(Matcher m) {
        return new AffineTransform(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5)), Double.parseDouble(m.group(6)));
    }

    /** A point of a placed PDF image (unit square, y up) in page points measured from the top-left corner. */
    static Point2D pdfToPage(AffineTransform placed, double u, double v) {
        Point2D point = placed.transform(new Point2D.Double(u, v), null);
        return new Point2D.Double(point.getX(), PAGE_HEIGHT - point.getY());
    }
}
