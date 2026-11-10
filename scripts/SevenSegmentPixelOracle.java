/* SPDX-License-Identifier: GPL-3.0-only */
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import javax.imageio.ImageIO;

/** Reads full-resolution screenshot pixels; it never infers a digit from accessibility text. */
public final class SevenSegmentPixelOracle {
  private record Box(int left, int top, int right, int bottom, int pixels) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 6 || (args.length - 1) % 5 != 0) {
      throw new IllegalArgumentException("Usage: java SevenSegmentPixelOracle.java image.png left top right bottom expectedDigit [...]");
    }
    BufferedImage image = ImageIO.read(new File(args[0]));
    if (image == null) throw new IllegalArgumentException("ImageIO could not decode the screenshot");
    for (int i = 1; i < args.length; i += 5) {
      int left = Integer.parseInt(args[i]), top = Integer.parseInt(args[i + 1]);
      int right = Integer.parseInt(args[i + 2]), bottom = Integer.parseInt(args[i + 3]);
      if (left < 0 || top < 0 || right > image.getWidth() || bottom > image.getHeight() || left >= right || top >= bottom) {
        throw new IllegalArgumentException("Digit bounds must lie inside the original image");
      }
      List<Box> segments = redRectangles(image, left, top, right, bottom);
      int minY = segments.stream().mapToInt(Box::top).min().orElseThrow();
      int maxY = segments.stream().mapToInt(Box::bottom).max().orElseThrow();
      int minX = segments.stream().mapToInt(Box::left).min().orElseThrow();
      int maxX = segments.stream().mapToInt(Box::right).max().orElseThrow();
      var observed = new HashSet<Character>();
      for (Box segment : segments) {
        double y = ((segment.top + segment.bottom) / 2.0 - minY) / (maxY - minY);
        char label = segment.right - segment.left > segment.bottom - segment.top
            ? y < .25 ? 'a' : y > .75 ? 'd' : 'g'
            : (segment.left + segment.right) / 2.0 < (minX + maxX) / 2.0
                ? y < .5 ? 'f' : 'e' : y < .5 ? 'b' : 'c';
        if (!observed.add(label)) throw new AssertionError("Multiple red regions mapped to segment " + label);
      }
      String actual = observed.stream().sorted().collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();
      String expected = switch (args[i + 4]) {
        case "0" -> "abcdef";
        case "8" -> "abcdefg";
        case "E" -> "adefg";
        default -> throw new IllegalArgumentException("This SAP-1 oracle supports expected digits 0, 8 and E");
      };
      if (!actual.equals(expected)) throw new AssertionError("Expected " + args[i + 4] + " (" + expected + "), found " + actual);
      System.out.println("PASS digit=" + args[i + 4] + " active=" + actual + " redRegions=" + segments.size());
      segments.sort(Comparator.comparingInt(Box::top).thenComparingInt(Box::left));
      segments.forEach(System.out::println);
    }
  }

  private static List<Box> redRectangles(BufferedImage image, int left, int top, int right, int bottom) {
    var remaining = new HashSet<Integer>();
    int width = image.getWidth();
    for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
      int rgb = image.getRGB(x, y);
      if (((rgb >>> 24) & 255) >= 200 && ((rgb >>> 16) & 255) > 180 && ((rgb >>> 8) & 255) < 50 && (rgb & 255) < 50) remaining.add(y * width + x);
    }
    var result = new ArrayList<Box>();
    while (!remaining.isEmpty()) {
      var queue = new ArrayDeque<Integer>();
      queue.add(remaining.iterator().next());
      remaining.remove(queue.peek());
      int minX = right, minY = bottom, maxX = left, maxY = top, count = 0;
      while (!queue.isEmpty()) {
        int point = queue.remove(), x = point % width, y = point / width;
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y); count++;
        if (x > left && remaining.remove(point - 1)) queue.add(point - 1);
        if (x + 1 < right && remaining.remove(point + 1)) queue.add(point + 1);
        if (y > top && remaining.remove(point - width)) queue.add(point - width);
        if (y + 1 < bottom && remaining.remove(point + width)) queue.add(point + width);
      }
      if (count > 2) result.add(new Box(minX, minY, maxX, maxY, count));
    }
    return result;
  }
}
