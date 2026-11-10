import dev.logisim.mobile.core.*;
import java.nio.file.*;
import java.util.*;

class Ice40Clock {
    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[0]);
        String xml = """
            <HX1K_CLOCK><BoardInformation>
              <ClockInformation FPGApin="21" Frequency="12288000" IOStandard="LVCMOS33" PullBehavior="Float"/>
              <FPGAInformation Vendor="OPENFPGA" Family="ice40" Part="hx1k" Package="tq144" Speedgrade="1"/>
            </BoardInformation><IOComponents>
              <LED Label="Result" OutputPinSet="23" FPGAPinIOStandard="LVCMOS33"/>
            </IOComponents></HX1K_CLOCK>
            """;
        var board = ((FpgaBoardImportResult.Success) FpgaBoardCodec.INSTANCE.read(xml)).getBoard();
        var design = new FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            List.of(new FpgaSourceFile("user_top.v", "module user_top(input clk, output reg y=0); always @(posedge clk) y <= ~y; endmodule\n")),
            List.of(new FpgaDesignPort("clk", FpgaPinDirection.INPUT, 1, 12288000L),
                new FpgaDesignPort("y", FpgaPinDirection.OUTPUT, 1, null)));
        var bindings = Map.<FpgaSignalBit, FpgaPinBinding>of(
            new FpgaSignalBit("clk", 0), new FpgaPinBinding.Physical("21", null, null, null),
            new FpgaSignalBit("y", 0), new FpgaPinBinding.Physical("23", null, null, null));
        var result = FpgaProjectGenerator.INSTANCE.generate(design, board, bindings, FpgaFlow.OPEN_ICE40);
        if (!(result instanceof FpgaPlanResult.Success success)) throw new AssertionError(result);
        var plan = success.getPlan();
        Files.createDirectories(directory);
        for (var file : plan.getFiles()) Files.writeString(directory.resolve(file.getPath()), file.getContent());
        Files.write(directory.resolve("project.zip"), FpgaArchiveKt.archive(plan));
    }
}
