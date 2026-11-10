import com.cburch.logisim.fpga.data.BoardInformation;
import com.cburch.logisim.fpga.data.DriveStrength;
import com.cburch.logisim.fpga.data.IoStandards;
import com.cburch.logisim.fpga.file.BoardReaderClass;
import com.cburch.logisim.fpga.file.BoardWriterClass;
import dev.logisim.mobile.core.FpgaBoardCodec;
import dev.logisim.mobile.core.FpgaBoardImportResult;
import dev.logisim.mobile.core.FpgaBoardPicture;
import dev.logisim.mobile.core.FpgaBoardWriter;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FPGA > Board Editor: both editors save a board they have loaded. Evolution's BoardWriterClass and Mobile's
 * FpgaBoardWriter write the same board; Evolution's BoardReaderClass then reads both files back and the
 * board information and the decoded picture must be identical.
 */
public final class BoardEditorOracle {
    static String describe(BoardInformation board) {
        var out = new StringBuilder(board.getBoardName()).append('\n');
        var fpga = board.fpga;
        out.append(fpga.getVendor()).append(' ').append(fpga.getTechnology()).append(' ').append(fpga.getPart()).append(' ')
            .append(fpga.getPackage()).append(' ').append(fpga.getSpeedGrade()).append(" jtag=").append(fpga.getFpgaJTAGChainPosition())
            .append(" flash=").append(fpga.getFlashName()).append('@').append(fpga.getFlashJTAGChainPosition())
            .append(" usbtmc=").append(fpga.isUsbTmcDownloadRequired()).append(" unused=").append((int) fpga.getUnusedPinsBehavior())
            .append(" clock=").append(fpga.getClockPinLocation()).append('/').append(fpga.getClockFrequency())
            .append('/').append(IoStandards.getIoString(fpga.getClockStandard())).append("/").append((int) fpga.getClockPull()).append('\n');
        for (var c : board.getAllComponents()) {
            var r = c.getRectangle();
            out.append(c.getType()).append(" '").append(c.getLabel()).append("' ")
                .append(r == null ? "-" : r.getXpos() + "," + r.getYpos() + "," + r.getWidth() + "," + r.getHeight())
                .append(" io=").append(IoStandards.getIoString(c.getIoStandard())).append(" pull=").append((int) c.getPullBehavior())
                .append(" drive=").append(DriveStrength.getDriveString(c.getDrive())).append(" active=").append((int) c.getActivityLevel());
            int pins = c.getExternalPinCount() > 0 ? c.getExternalPinCount() : c.getNrOfPins();
            for (int i = 0; i < pins; i++) out.append(' ').append(c.getPinLocation(i));
            out.append('\n');
        }
        return out.toString();
    }

    static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        var out = Files.createDirectories(Path.of(args[0]));
        int failed = 0;
        for (int i = 1; i < args.length; i++) {
            var source = Path.of(args[i]);
            var name = source.getFileName().toString().replace(".xml", "");
            var desktop = new BoardReaderClass(source.toAbsolutePath().toString()).getBoardInformation();
            var evolutionFile = out.resolve(name + "-evolution.xml");
            new BoardWriterClass(desktop, desktop.getImage()).printXml(evolutionFile.toString());

            var xml = Files.readString(source);
            var read = (FpgaBoardImportResult.Success) FpgaBoardCodec.INSTANCE.read(xml);
            var written = FpgaBoardWriter.INSTANCE.write(read.getBoard(), FpgaBoardPicture.Companion.read(xml));
            if (!(written instanceof FpgaBoardImportResult.Success success)) throw new AssertionError("Mobile writer rejected " + name + ": " + written);
            var mobileFile = out.resolve(name + "-mobile.xml");
            Files.writeString(mobileFile, success.getBoard().getOriginalXml());

            var fromEvolution = new BoardReaderClass(evolutionFile.toString()).getBoardInformation();
            var fromMobile = new BoardReaderClass(mobileFile.toString()).getBoardInformation();
            var a = describe(fromEvolution);
            var b = describe(fromMobile);
            var original = describe(desktop);
            System.out.println("INFO " + name + " evolution-writer-preserves-source=" + a.equals(original)
                + " mobile-writer-preserves-source=" + b.equals(original)
                + " evolution-writer-preserves-pixels=" + samePixels(desktop.getImage(), fromEvolution.getImage())
                + " mobile-writer-preserves-pixels=" + samePixels(desktop.getImage(), fromMobile.getImage()));
            boolean pixels = samePixels(fromEvolution.getImage(), fromMobile.getImage());
            var resources = fromEvolution.getAllComponents().size();
            // Evolution's writer re-encodes the picture (pixels change on some boards); the INFO line reports it.
            if (a.equals(b) && samePixels(desktop.getImage(), fromMobile.getImage())) {
                System.out.println("SAME " + name + ": " + resources + " resources, "
                    + fromEvolution.getImage().getWidth() + "x" + fromEvolution.getImage().getHeight() + " picture");
            } else {
                failed++;
                System.out.println("DIFF " + name + " pixels=" + pixels + "\n--- evolution\n" + a + "--- mobile\n" + b);
            }
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
