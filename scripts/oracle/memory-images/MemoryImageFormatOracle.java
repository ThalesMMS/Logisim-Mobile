import com.cburch.logisim.Main;
import com.cburch.logisim.gui.hex.HexFile;
import com.cburch.logisim.std.memory.MemContents;
import dev.logisim.mobile.core.MemoryImage;
import dev.logisim.mobile.core.MemoryImageCodec;
import dev.logisim.mobile.core.MemoryImageFormat;
import dev.logisim.mobile.core.MemoryImageImportResult;
import dev.logisim.mobile.core.MemoryImageLimits;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class MemoryImageFormatOracle {
    public static void main(String[] args) throws Exception {
        Main.headless = true;
        Path directory = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("memory-image-oracle-");
        Files.createDirectories(directory);
        Method boxUnsigned = Class.forName("kotlin.ULong").getDeclaredMethod("box-impl", long.class);
        Method readWord = MemoryImage.class.getMethod("get-I7RO_PI", long.class);
        Method open = HexFile.class.getDeclaredMethod("open", MemContents.class, File.class, String.class);
        open.setAccessible(true);
        MemoryImageLimits limits = new MemoryImageLimits();
        int comparisons = 0;
        for (int width : new int[] {1, 3, 8, 10, 16, 32, 64}) {
            long mask = width == 64 ? -1L : (1L << width) - 1;
            MemContents source = MemContents.create(4, width, false);
            Map<Long, Object> cells = new HashMap<>();
            for (long address = 0; address < 16; address++) {
                long value = (0x9e3779b97f4a7c15L * (address + 1)) & mask;
                source.set(address, value);
                cells.put(address, boxUnsigned.invoke(null, value));
            }
            MemoryImage image = (MemoryImage) MemoryImage.class
                    .getConstructor(int.class, int.class, Map.class).newInstance(4, width, cells);
            for (MemoryImageFormat format : MemoryImageFormat.values()) {
                Path desktop = directory.resolve("desktop-" + width + "-" + format.name());
                HexFile.save(desktop.toFile(), source, format.getDescription());
                MemoryImageImportResult result = MemoryImageCodec.INSTANCE.decode(
                        Files.readAllBytes(desktop), 4, width, format, limits);
                if (!(result instanceof MemoryImageImportResult.Success success)) {
                    throw new AssertionError("Desktop image rejected: " + width + "/" + format + ": " + result);
                }
                for (long address = 0; address < 16; address++) {
                    long actual = (long) readWord.invoke(success.getImage(), address);
                    requireEqual(source.get(address), actual, "desktop -> mobile", width, format, address);
                    comparisons++;
                }
                Path mobile = directory.resolve("mobile-" + width + "-" + format.name());
                Files.write(mobile, MemoryImageCodec.INSTANCE.encode(image, format, limits));
                MemContents restored = MemContents.create(4, width, false);
                if (!(boolean) open.invoke(null, restored, mobile.toFile(), format.getDescription())) {
                    throw new AssertionError("Desktop rejected mobile image: " + mobile);
                }
                for (long address = 0; address < 16; address++) {
                    requireEqual(source.get(address), restored.get(address), "mobile -> desktop", width, format, address);
                    comparisons++;
                }
            }
        }
        String result = "PASS " + comparisons + " desktop/mobile memory-word comparisons across 11 formats and 7 widths";
        Files.writeString(directory.resolve("result.txt"), result + "\n");
        System.out.println(result);
        System.out.println("Images: " + directory.toAbsolutePath());
    }

    private static void requireEqual(long expected, long actual, String direction,
            int width, MemoryImageFormat format, long address) {
        if (expected != actual) {
            throw new AssertionError(direction + " " + width + "/" + format + " @" + address
                    + ": expected " + Long.toUnsignedString(expected) + ", got " + Long.toUnsignedString(actual));
        }
    }
}
