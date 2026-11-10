import dev.logisim.mobile.core.VisualGif;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashSet;
import javax.imageio.ImageIO;

/**
 * GIF palette oracle (#350). Evolution's ExportImage writes GIF with ImageIO.write(TYPE_INT_RGB image, "GIF"), whose
 * writer builds the palette with PaletteBuilder. For each Mobile raster (the pixels Mobile's exporter encodes), this
 * encodes the pixels with Mobile's VisualGif and with ImageIO and decodes both.
 * Prints tab-separated "check name PASS|FAIL detail" lines.
 * Usage: GifPalette image.png...
 */
public final class GifPalette {
    public static void main(String[] args) throws Exception {
        var failures = 0;
        for (var path : args) {
            var source = ImageIO.read(new File(path));
            var width = source.getWidth();
            var height = source.getHeight();
            var argb = source.getRGB(0, 0, width, height, null, 0, width);
            var colours = new HashSet<Integer>();
            for (var pixel : argb) colours.add(pixel & 0xffffff);
            var mobile = ImageIO.read(new ByteArrayInputStream(VisualGif.INSTANCE.encode(width, height, argb)));
            var rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            rgb.setRGB(0, 0, width, height, argb, 0, width);
            var buffer = new ByteArrayOutputStream();
            ImageIO.write(rgb, "GIF", buffer);
            var evolution = ImageIO.read(new ByteArrayInputStream(buffer.toByteArray()));
            var different = 0;
            int worstMobile = 0, worstEvolution = 0;
            var decodedColours = new HashSet<Integer>();
            for (var y = 0; y < height; y++) for (var x = 0; x < width; x++) {
                var a = argb[y * width + x];
                var m = mobile.getRGB(x, y);
                var e = evolution.getRGB(x, y);
                decodedColours.add(m & 0xffffff);
                if (m != e) different++;
                worstMobile = Math.max(worstMobile, channelError(a, m));
                worstEvolution = Math.max(worstEvolution, channelError(a, e));
            }
            var name = new File(path).getParentFile().getName();
            var same = different == 0;
            if (!same) failures++;
            System.out.println("gif\t" + name + ":imageio-pixels\t" + (same ? "PASS" : "FAIL") + "\t" + different + " of " + (width * height)
                + " decoded pixels differ from ImageIO's GIF of the same pixels; " + colours.size() + " source colours, "
                + decodedColours.size() + " palette colours used");
            var close = worstMobile == worstEvolution;
            if (!close) failures++;
            System.out.println("gif\t" + name + ":max-channel-error\t" + (close ? "PASS" : "FAIL") + "\tMobile GIF " + worstMobile
                + ", ImageIO GIF " + worstEvolution + " against the source PNG");
        }
        System.out.println("summary\t" + (failures == 0 ? "PASS" : "FAIL") + "\t" + failures + " failed checks");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int channelError(int a, int b) {
        var worst = 0;
        for (var shift = 0; shift < 24; shift += 8) worst = Math.max(worst, Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)));
        return worst;
    }
}
