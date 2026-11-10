import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.DemoCircuits;
import dev.logisim.mobile.core.TestVectorFormat;
import dev.logisim.mobile.core.TestVectorRunSession;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VectorMobileOracle {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--fixtures")) {
            var directory = Path.of(args[1]);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("and.circ"), CircCodec.INSTANCE.write(DemoCircuits.INSTANCE.andGate())
                .replace("Minha_primeira_porta_AND", "AND"));
            Files.writeString(directory.resolve("flip.circ"), CircCodec.INSTANCE.write(DemoCircuits.INSTANCE.flipFlop())
                .replace("Memoria_flip_flop_D", "Flip").replace("val=\"Clock\"", "val=\"Clk\""));
            return;
        }
        var loaded = (CircProjectImportResult.Success) CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[0])));
        var vectors = TestVectorFormat.INSTANCE.read(Files.readString(Path.of(args[1])));
        var runner = new TestVectorRunSession(loaded.getProject(), args[2], vectors, new CircuitSimulator(256));
        for (var row : runner.run(Integer.MAX_VALUE)) {
            var output = new StringBuilder().append(row.getRowIndex()).append('\t').append(row.getPassed() ? "PASS" : "FAIL");
            for (var value : row.getObserved()) output.append('\t').append(value.getDisplay());
            System.out.println(output);
        }
    }
}
