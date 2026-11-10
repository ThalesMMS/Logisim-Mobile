import com.cburch.logisim.file.Loader;
import com.cburch.logisim.fpga.download.Download;
import com.cburch.logisim.fpga.file.BoardReaderClass;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import java.io.File;

/**
 * Runs Evolution's FPGA Commander "Generate HDL only" path ({@code Download}, as the commander and
 * {@code --test-fpga ... HDLONLY} build it) on a circuit with a saved board map, in Verilog.
 *
 * <p>Usage: {@code FpgaCommanderOracle project.circ circuit board.xml workspace}.
 */
public final class FpgaCommanderOracle {
    public static void main(String[] args) {
        int status = 2;
        try {
            com.cburch.logisim.Main.headless = true;
            AppPreferences.HdlType.set("Verilog");
            AppPreferences.FPGA_Workspace.set(new File(args[3]).getAbsolutePath());
            Project project = new Project(new Loader(null).openLogisimFile(new File(args[0])));
            var board = new BoardReaderClass(new File(args[2]).getAbsolutePath()).getBoardInformation();
            var download = new Download(project, args[1], 1.0, board, null, false, false, true, 1.0, 1.0);
            status = download.runTty() ? 0 : 1;
        } catch (Throwable error) {
            error.printStackTrace();
        }
        System.exit(status);
    }
}
