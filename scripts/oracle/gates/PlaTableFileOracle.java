import com.cburch.logisim.std.gates.PlaTable;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Evolution PlaTable file oracle. Saves fixtures/pla/evolution-saved.txt with PlaTable.save and reads every
 * other fixture with PlaTable.parse(File), printing the standard table and valueFor of each input.
 */
public final class PlaTableFileOracle {
  private static final String TABLE =
      "1x0x 101 # first match wins\n"
          + "1xxx 010\n"
          + "0011 111 #  spaced comment  \n"
          + "x1x0 001\n";

  public static void main(String[] args) throws Exception {
    final var directory = new File(args[0]);
    PlaTable.parse(TABLE).save(new File(directory, "evolution-saved.txt"));
    final var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    for (final var name : new String[] {"evolution-saved.txt", "mobile-saved.txt", "flexible.txt"}) {
      final var table = PlaTable.parse(new File(directory, name));
      out.println("file\t" + name + "\t" + table.inSize() + "\t" + table.outSize());
      for (final var line : table.toStandardString().split("\n")) out.println("row\t" + name + "\t" + line);
      for (long input = 0; input < (1L << table.inSize()); input++)
        out.println("value\t" + name + "\t" + input + "\t" + table.valueFor(input));
    }
  }
}
