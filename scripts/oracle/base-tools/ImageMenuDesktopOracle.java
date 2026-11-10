import com.cburch.logisim.util.ImageUtil;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * The effects of Evolution's Image menu (std.base.Image.getInstanceFeature), through the ImageUtil calls each
 * item makes on the cached image: Reset to Original Size, Optimize Image Size (the decision, and the stored PNG
 * after a confirmed resize) and Make Outer Background Transparent. Optimize asks through JOptionPane, so the
 * oracle takes "yes" in place of the dialog. Writes the stored images to OUT_DIR as <case>.png.
 * Usage: ImageMenuDesktopOracle OUT_DIR badge.png photo.png
 */
public final class ImageMenuDesktopOracle {
    static final String[][] FRAMES = ImageMenuCases.FRAMES;

    public static void main(String[] args) throws Exception {
        var out = Path.of(args[0]);
        Files.createDirectories(out);
        for (var size : ImageMenuCases.CACHED_SIZES) {
            var cached = ImageUtil.enforceMaxDimensions(new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB));
            System.out.println("cached\t" + size[0] + "x" + size[1] + "\t" + cached.getWidth() + "x" + cached.getHeight());
        }
        for (var index = 1; index < args.length; index++) {
            var file = new File(args[index]);
            var name = file.getName().replace(".png", "");
            // ImageAttributes caches ImageUtil.loadBufferedImage of the image attribute (a data URI).
            var source = "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            var image = ImageUtil.loadBufferedImage(source);
            System.out.println("reset\t" + name + "\twidth=" + image.getWidth() + ",height=" + image.getHeight());
            for (var frame : FRAMES) {
                var frameW = Integer.parseInt(frame[0]);
                var frameH = Integer.parseInt(frame[1]);
                var scale = frame[2];
                var target = ImageUtil.getOptimizedTargetDimensions(image, frameW, frameH, scale);
                var line = "optimize\t" + name + "\t" + frameW + "x" + frameH + " " + scale + "\t";
                if (image.getWidth() == target[0] && image.getHeight() == target[1]) {
                    System.out.println(line + "already " + image.getWidth() + "x" + image.getHeight());
                    continue;
                }
                var stored = ImageUtil.bufferedImageToBase64(ImageUtil.optimizeImage(image, frameW, frameH, scale));
                var frameSet = "fit".equalsIgnoreCase(scale) ? " frame=" + target[0] + "x" + target[1] : "";
                System.out.println(line + "resize " + image.getWidth() + "x" + image.getHeight() + "->" + target[0] + "x" + target[1] + frameSet);
                write(stored, out.resolve(ImageMenuCases.caseName(name, frame) + ".png"));
            }
            write(ImageUtil.bufferedImageToBase64(ImageUtil.makeWhiteTransparent(image)), out.resolve(name + "-transparent.png"));
            System.out.println("transparent\t" + name + "\t" + name + "-transparent.png");
        }
    }

    private static void write(String dataUri, Path path) throws Exception {
        Files.write(path, Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1)));
    }
}
