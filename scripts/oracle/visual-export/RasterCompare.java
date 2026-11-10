import dev.logisim.mobile.core.VisualGif;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Compares a Mobile raster export of fixtures/visual-export/qualification.circ (the PNG saved by the mounted
 * Android/iOS editor, whose pixels feed the GIF and JPEG encoders too) with Evolution's GIF or JPEG of the same
 * circuit at the same scale. Both rasters are mapped to world coordinates: Mobile's frame starts at the contract
 * bounds minus the margin (world -15 at scale 0.25), Evolution's at its circuit bounds expanded by 5 (world -5).
 *
 * Checks, all printed as TSV "check<TAB>name<TAB>PASS|FAIL<TAB>detail":
 *  - every contract ink region (2 texts, 72 symbols, 4 frame edges) has at least minimumInkPixels ink pixels in both;
 *  - ink outside every region (grown by 4 px; symbol regions also cover their label, 100 world units to each side and
 *    60 above; plus the embedded image box) is at most 0.1% of the image in both;
 *  - the embedded image's red and green interior samples keep their colour in both (channel tolerance 40), and the
 *    GIF round trip reports how far the Mobile palette moves them;
 *  - with "--gif-roundtrip", Mobile's VisualGif.encode of the Mobile pixels decodes with ImageIO to the same size,
 *    each channel within its RGB 3-3-2 quantisation step, and keeps every region's ink.
 * Usage: RasterCompare contract.json mobile.png evolution.(gif|jpg) evolutionOriginX evolutionOriginY [--gif-roundtrip]
 */
public final class RasterCompare {
    record Region(String id, int[] world, int minimum) {}

    static final double SCALE = 0.25;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        var contract = Files.readString(new File(args[0]).toPath());
        var regions = new ArrayList<Region>();
        var matcher = Pattern.compile("\\{\"id\": \"([^\"]+)\", \"kind\": \"[a-z]+\", \"worldBounds\": \\[(-?\\d+), (-?\\d+), (-?\\d+), (-?\\d+)\\], "
            + "\"pixelBounds\": \\[[^\\]]*\\], \"minimumInkPixels\": (\\d+)\\}").matcher(contract);
        while (matcher.find()) {
            regions.add(new Region(matcher.group(1), new int[] {Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5))}, Integer.parseInt(matcher.group(6))));
        }
        var mobile = ImageIO.read(new File(args[1]));
        var evolution = ImageIO.read(new File(args[2]));
        var evolutionOrigin = new double[] {Double.parseDouble(args[3]), Double.parseDouble(args[4])};
        var mobileOrigin = new double[] {-15, -15};
        var jpeg = args[2].endsWith(".jpg");
        report("regions", "contract", regions.size() == 78, regions.size() + " regions parsed");
        report("size", "mobile", true, mobile.getWidth() + "x" + mobile.getHeight());
        report("size", "evolution", true, evolution.getWidth() + "x" + evolution.getHeight());
        var expectedWidth = (int) Math.round(mobile.getWidth() - (evolutionOrigin[0] - mobileOrigin[0]) * SCALE * 2);
        report("size", "frame", Math.abs(evolution.getWidth() - expectedWidth) <= 1,
            "Evolution width " + evolution.getWidth() + " vs Mobile width minus its extra margin " + expectedWidth);
        compare("mobile", mobile, mobileOrigin, regions, false);
        compare("evolution", evolution, evolutionOrigin, regions, jpeg);
        var evolutionPng = new File(args[2].replaceAll("\\.(gif|jpg)$", ".png"));
        if (args[2].endsWith(".gif") && evolutionPng.isFile()) {
            var exact = ImageIO.read(evolutionPng);
            var worst = 0;
            for (var y = 0; y < exact.getHeight(); y++) for (var x = 0; x < exact.getWidth(); x++) {
                var a = exact.getRGB(x, y);
                var b = evolution.getRGB(x, y);
                for (var shift = 0; shift < 24; shift += 8) worst = Math.max(worst, Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)));
            }
            report("gif", "evolution-palette", true, "Evolution GIF vs the same drawing as PNG: max channel error " + worst
                + " (ImageIO builds an adaptive palette)");
        }
        if (args.length > 5 && args[5].equals("--gif-roundtrip")) {
            var width = mobile.getWidth();
            var height = mobile.getHeight();
            var argb = mobile.getRGB(0, 0, width, height, null, 0, width);
            var bytes = VisualGif.INSTANCE.encode(width, height, argb);
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            report("gif", "mobile-size", decoded.getWidth() == width && decoded.getHeight() == height, decoded.getWidth() + "x" + decoded.getHeight());
            int worstR = 0, worstG = 0, worstB = 0;
            for (var y = 0; y < height; y++) for (var x = 0; x < width; x++) {
                var a = argb[y * width + x];
                var b = decoded.getRGB(x, y);
                worstR = Math.max(worstR, Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)));
                worstG = Math.max(worstG, Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)));
                worstB = Math.max(worstB, Math.abs((a & 255) - (b & 255)));
            }
            report("gif", "mobile-quantisation", worstR <= 37 && worstG <= 37 && worstB <= 86,
                "max channel error R=" + worstR + " G=" + worstG + " B=" + worstB + " (RGB 3-3-2 steps 36.4/36.4/85)");
            compare("mobile-gif", decoded, mobileOrigin, regions, false);
        }
        System.out.println("summary\t" + (failures == 0 ? "PASS" : "FAIL") + "\t" + failures + " failed checks");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void compare(String name, BufferedImage image, double[] origin, List<Region> regions, boolean jpeg) {
        var threshold = jpeg ? 48 : 2; // JPEG ringing stays below 48 away from strokes.
        var width = image.getWidth();
        var height = image.getHeight();
        var covered = new boolean[width * height];
        var regionFailures = new ArrayList<String>();
        var minimumInk = Integer.MAX_VALUE;
        for (var region : regions) {
            var box = pixels(region.world(), origin, width, height);
            var ink = 0;
            for (var y = box[1]; y <= box[3]; y++) for (var x = box[0]; x <= box[2]; x++) if (ink(image.getRGB(x, y), threshold)) ink++;
            minimumInk = Math.min(minimumInk, ink);
            if (ink < region.minimum()) regionFailures.add(region.id() + "=" + ink);
            // Symbol labels sit above the symbol; Evolution draws them wider than the contract box (labelled cells).
            var label = region.id().startsWith("EXPORT34_CELL_") ? new int[] {region.world()[0] - 100, region.world()[1] - 60,
                region.world()[2] + 100, region.world()[3]} : region.world();
            cover(covered, pixels(label, origin, width, height), width, height);
        }
        // The embedded image annotation (contract selection bounds) is checked by its colour samples.
        cover(covered, pixels(new int[] {100, 200, 220, 280}, origin, width, height), width, height);
        report("ink-regions", name, regionFailures.isEmpty(), regions.size() - regionFailures.size() + "/" + regions.size()
            + " regions with ink (least " + minimumInk + " px)" + (regionFailures.isEmpty() ? "" : "; missing " + regionFailures));
        var stray = 0;
        for (var y = 0; y < height; y++) for (var x = 0; x < width; x++)
            if (!covered[y * width + x] && ink(image.getRGB(x, y), threshold)) stray++;
        // Where the stray ink lies, in 100x100 world cells: "worldX,worldY:count" for the five fullest cells.
        var cells = new java.util.TreeMap<String, Integer>();
        for (var y = 0; y < height; y++) for (var x = 0; x < width; x++)
            if (!covered[y * width + x] && ink(image.getRGB(x, y), threshold)) {
                var key = (int) Math.floor((x / SCALE + origin[0]) / 100) * 100 + "," + (int) Math.floor((y / SCALE + origin[1]) / 100) * 100;
                cells.merge(key, 1, Integer::sum);
            }
        var fullest = cells.entrySet().stream().sorted((l, r) -> r.getValue() - l.getValue()).limit(5)
            .map(e -> e.getKey() + ":" + e.getValue()).toList();
        report("ink-outside", name, stray <= width * height / 1000, stray + " ink px outside the regions (limit " + width * height / 1000
            + "); fullest world cells " + fullest);
        // Interior samples of the embedded red / half-alpha green image (contract samples at world 113,209 and 205,209).
        sample(name + "-red", image, origin, 113, 209, 0xff0000);
        sample(name + "-green", image, origin, 205, 209, 0x7fff7f);
    }

    static void sample(String name, BufferedImage image, double[] origin, int worldX, int worldY, int expected) {
        var x = (int) Math.floor((worldX - origin[0]) * SCALE);
        var y = (int) Math.floor((worldY - origin[1]) * SCALE);
        var rgb = image.getRGB(x, y) & 0xffffff;
        var ok = Math.abs(((rgb >> 16) & 255) - ((expected >> 16) & 255)) <= 40 && Math.abs(((rgb >> 8) & 255) - ((expected >> 8) & 255)) <= 40
            && Math.abs((rgb & 255) - (expected & 255)) <= 40;
        report("colour", name, ok, String.format("pixel (%d,%d) = %06x, expected %06x", x, y, rgb, expected));
    }

    static void cover(boolean[] covered, int[] box, int width, int height) {
        for (var y = Math.max(0, box[1] - 4); y <= Math.min(height - 1, box[3] + 4); y++)
            for (var x = Math.max(0, box[0] - 4); x <= Math.min(width - 1, box[2] + 4); x++) covered[y * width + x] = true;
    }

    static int[] pixels(int[] world, double[] origin, int width, int height) {
        return new int[] {
            Math.max(0, (int) Math.floor((world[0] - origin[0]) * SCALE)), Math.max(0, (int) Math.floor((world[1] - origin[1]) * SCALE)),
            Math.min(width - 1, (int) Math.ceil((world[2] - origin[0]) * SCALE)), Math.min(height - 1, (int) Math.ceil((world[3] - origin[1]) * SCALE)),
        };
    }

    static boolean ink(int rgb, int threshold) {
        return 255 - ((rgb >> 16) & 255) > threshold || 255 - ((rgb >> 8) & 255) > threshold || 255 - (rgb & 255) > threshold;
    }

    static void report(String check, String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println(check + "\t" + name + "\t" + (ok ? "PASS" : "FAIL") + "\t" + detail);
    }
}
