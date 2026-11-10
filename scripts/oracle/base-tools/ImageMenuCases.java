import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/**
 * Cases shared by ImageMenuDesktopOracle and the Mobile side, and the pixel comparison of their stored images.
 * Usage: ImageMenuCases DESKTOP_DIR MOBILE_DIR (prints one line per image and "SAME image-menu" or "DIFF image-menu").
 */
public final class ImageMenuCases {
    /** Optimize frames: width, height, scale. */
    static final String[][] FRAMES = {
        {"64", "64", "fit"}, {"40", "30", "fit"}, {"400", "400", "fit"}, {"64", "64", "stretch"},
        {"320", "150", "stretch"}, {"64", "64", "cover"}, {"160", "100", "cover"}, {"30", "60", "cover"},
    };
    /** Sizes for ImageUtil.enforceMaxDimensions. */
    static final int[][] CACHED_SIZES = {{2048, 2048}, {4096, 1000}, {2100, 2100}, {100, 3000}, {2049, 7}};

    static String caseName(String image, String[] frame) {
        return image + "-optimize-" + frame[0] + "x" + frame[1] + "-" + frame[2];
    }

    public static void main(String[] args) throws Exception {
        var desktop = new File(args[0]);
        var mobile = new File(args[1]);
        var names = desktop.list((dir, name) -> name.endsWith(".png"));
        java.util.Arrays.sort(names);
        var failures = new ArrayList<String>();
        for (var name : names) {
            var expected = ImageIO.read(new File(desktop, name));
            var actualFile = new File(mobile, name);
            if (!actualFile.exists()) { failures.add(name + " missing"); System.out.println(name + "\tmissing on Mobile"); continue; }
            var actual = ImageIO.read(actualFile);
            if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
                failures.add(name);
                System.out.println(name + "\tsize " + expected.getWidth() + "x" + expected.getHeight() + " vs " + actual.getWidth() + "x" + actual.getHeight());
                continue;
            }
            var max = 0;
            var differing = 0;
            for (var y = 0; y < expected.getHeight(); y++) for (var x = 0; x < expected.getWidth(); x++) {
                var a = expected.getRGB(x, y);
                var b = actual.getRGB(x, y);
                var pixel = 0;
                for (var shift = 0; shift < 32; shift += 8) pixel = Math.max(pixel, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
                if (pixel > 0) differing++;
                max = Math.max(max, pixel);
            }
            System.out.println(name + "\t" + expected.getWidth() + "x" + expected.getHeight() + "\tmax channel difference " + max
                + " (" + differing + " pixels)");
            if (max > 0) failures.add(name);
        }
        System.out.println(failures.isEmpty() ? "SAME image-menu" : "DIFF image-menu " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** Straight-alpha ARGB pixels of a decoded PNG, as ImageBitmap.readPixels gives them on the device. */
    static int[] argb(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    static BufferedImage image(int width, int height, int[] argb) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }
}
