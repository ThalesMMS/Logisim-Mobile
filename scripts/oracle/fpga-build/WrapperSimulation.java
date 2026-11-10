import dev.logisim.mobile.core.FpgaBoardCodec;
import dev.logisim.mobile.core.FpgaBoardDefinition;
import dev.logisim.mobile.core.FpgaBoardImportResult;
import dev.logisim.mobile.core.FpgaBuildPlan;
import dev.logisim.mobile.core.FpgaDesignArtifact;
import dev.logisim.mobile.core.FpgaDesignPort;
import dev.logisim.mobile.core.FpgaFlow;
import dev.logisim.mobile.core.FpgaHdlLanguage;
import dev.logisim.mobile.core.FpgaPinBinding;
import dev.logisim.mobile.core.FpgaPinDirection;
import dev.logisim.mobile.core.FpgaPlanResult;
import dev.logisim.mobile.core.FpgaProjectGenerator;
import dev.logisim.mobile.core.FpgaSignalBit;
import dev.logisim.mobile.core.FpgaSourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes designs and benches around wrappers produced by the compiled mobile core. */
class WrapperSimulation {
    private static FpgaDesignPort port(String name, FpgaPinDirection direction, int width) {
        return new FpgaDesignPort(name, direction, width, null, null, false, null);
    }

    private static void bind(Map<FpgaSignalBit, FpgaPinBinding> bindings,
            String port, int bit, String location) {
        bindings.put(new FpgaSignalBit(port, bit), new FpgaPinBinding.Physical(location, null, null, null));
    }

    private static String external(FpgaBuildPlan plan, String port, int bit) {
        return Objects.requireNonNull(plan.getMappedPins().get(new FpgaSignalBit(port, bit)),
                "Generated plan omitted " + port + "[" + bit + "]");
    }

    private static FpgaBuildPlan generate(FpgaBoardDefinition board, Path directory,
            FpgaHdlLanguage language, List<FpgaDesignPort> ports,
            Map<FpgaSignalBit, FpgaPinBinding> bindings, String source) throws Exception {
        String extension = language == FpgaHdlLanguage.VERILOG ? ".v" : ".vhd";
        var design = new FpgaDesignArtifact("user_top", language,
                List.of(new FpgaSourceFile("user_top" + extension, source)), ports, true);
        var result = FpgaProjectGenerator.INSTANCE.generate(design, board, bindings, FpgaFlow.OPEN_ECP5);
        if (!(result instanceof FpgaPlanResult.Success success)) {
            throw new AssertionError("Project generation failed: " + result);
        }
        FpgaBuildPlan plan = success.getPlan();
        if (!plan.getCanSynthesize() || plan.getProgramsHardware()) {
            throw new AssertionError("Expected a valid offline plan");
        }
        Files.createDirectories(directory);
        for (var file : plan.getFiles()) Files.writeString(directory.resolve(file.getPath()), file.getContent());
        for (var entry : bindings.entrySet()) {
            String name = external(plan, entry.getKey().getPort(), entry.getKey().getBit());
            String location = ((FpgaPinBinding.Physical) entry.getValue()).getLocation();
            String constraint = "LOCATE COMP \"" + name + "\" SITE \"" + location + "\";";
            String constraints = plan.getFiles().stream().filter(file -> file.getPath().equals("pins.lpf"))
                    .findFirst().orElseThrow().getContent();
            if (!constraints.contains(constraint)) throw new AssertionError("Missing physical mapping: " + constraint);
        }
        return plan;
    }

    private static void combinational(FpgaBoardDefinition board, Path directory,
            FpgaHdlLanguage language) throws Exception {
        var bindings = new LinkedHashMap<FpgaSignalBit, FpgaPinBinding>();
        bind(bindings, "a", 0, "D19");
        bind(bindings, "a", 1, "D17");
        bind(bindings, "y", 0, "D14");
        String source = language == FpgaHdlLanguage.VERILOG ? """
                module user_top(input wire [1:0] a, output wire y);
                  assign y = &a;
                endmodule
                """ : """
                library ieee;
                use ieee.std_logic_1164.all;
                entity user_top is
                  port (a: in std_logic_vector(1 downto 0); y: out std_logic);
                end entity;
                architecture rtl of user_top is
                begin
                  y <= a(0) and a(1);
                end architecture;
                """;
        var plan = generate(board, directory, language,
                List.of(port("a", FpgaPinDirection.INPUT, 2), port("y", FpgaPinDirection.OUTPUT, 1)),
                bindings, source);
        String a0 = external(plan, "a", 0);
        String a1 = external(plan, "a", 1);
        String y = external(plan, "y", 0);
        String bench = language == FpgaHdlLanguage.VERILOG ? """
                `timescale 1ns/1ps
                module tb;
                  reg [1:0] physical_a;
                  wire physical_y;
                  %s dut(.%s(physical_a[0]), .%s(physical_a[1]), .%s(physical_y));
                  initial begin
                    for (integer pattern = 0; pattern < 4; pattern = pattern + 1) begin
                      physical_a = pattern[1:0];
                      #1;
                      if (physical_y !== (physical_a[0] | physical_a[1]))
                        $fatal(1, "combinational: a=%%b y=%%b", physical_a, physical_y);
                    end
                    $display("PASS Verilog combinational: 4 physical states, y = a0 | a1");
                    $finish;
                  end
                endmodule
                """.formatted(plan.getTopLevelName(), a0, a1, y) : """
                library ieee;
                use ieee.std_logic_1164.all;
                use ieee.numeric_std.all;
                use std.env.all;
                entity tb is end entity;
                architecture test of tb is
                  signal physical_a: std_logic_vector(1 downto 0);
                  signal physical_y: std_logic;
                begin
                  dut: entity work.%s port map (%s => physical_a(0), %s => physical_a(1), %s => physical_y);
                  process
                  begin
                    for pattern in 0 to 3 loop
                      physical_a <= std_logic_vector(to_unsigned(pattern, 2));
                      wait for 1 ns;
                      assert physical_y = (physical_a(0) or physical_a(1))
                        report "combinational: physical output differs for pattern " & integer'image(pattern)
                        severity failure;
                    end loop;
                    report "PASS VHDL combinational: 4 physical states, y = a0 | a1" severity note;
                    stop;
                    wait;
                  end process;
                end architecture;
                """.formatted(plan.getTopLevelName(), a0, a1, y);
        Files.writeString(directory.resolve(language == FpgaHdlLanguage.VERILOG ? "tb.sv" : "tb.vhd"), bench);
    }

    private static void inout(FpgaBoardDefinition board, Path directory,
            FpgaHdlLanguage language) throws Exception {
        var bindings = new LinkedHashMap<FpgaSignalBit, FpgaPinBinding>();
        bind(bindings, "drive", 0, "C1");
        bind(bindings, "drive", 1, "C2");
        bind(bindings, "enable", 0, "B2");
        bind(bindings, "io", 0, "C3");
        bind(bindings, "io", 1, "C4");
        bind(bindings, "sampled", 0, "D5");
        bind(bindings, "sampled", 1, "C5");
        String source = language == FpgaHdlLanguage.VERILOG ? """
                module user_top(input wire [1:0] drive, input wire enable,
                    inout wire [1:0] io, output wire [1:0] sampled);
                  assign io = enable ? drive : 2'bzz;
                  assign sampled = io;
                endmodule
                """ : """
                library ieee;
                use ieee.std_logic_1164.all;
                entity user_top is
                  port (drive: in std_logic_vector(1 downto 0); enable: in std_logic;
                    io: inout std_logic_vector(1 downto 0); sampled: out std_logic_vector(1 downto 0));
                end entity;
                architecture rtl of user_top is
                begin
                  io <= drive when enable = '1' else (others => 'Z');
                  sampled <= io;
                end architecture;
                """;
        var plan = generate(board, directory, language,
                List.of(port("drive", FpgaPinDirection.INPUT, 2), port("enable", FpgaPinDirection.INPUT, 1),
                        port("io", FpgaPinDirection.INOUT, 2), port("sampled", FpgaPinDirection.OUTPUT, 2)),
                bindings, source);
        String bench = language == FpgaHdlLanguage.VERILOG ? """
                `timescale 1ns/1ps
                module tb;
                  reg [1:0] drive;
                  reg enable;
                  reg [1:0] external_drive;
                  tri [1:0] pad;
                  wire [1:0] sampled;
                  assign pad = external_drive;
                  %s dut(.%s(drive[0]), .%s(drive[1]), .%s(enable),
                    .%s(pad[0]), .%s(pad[1]), .%s(sampled[0]), .%s(sampled[1]));
                  task check(input reg [1:0] expected);
                    #1;
                    if (pad !== expected || sampled !== expected)
                      $fatal(1, "inout: expected=%%b pad=%%b sampled=%%b", expected, pad, sampled);
                  endtask
                  initial begin
                    enable = 0; drive = 0; external_drive = 2'bzz;
                    check(2'bzz);
                    enable = 1;
                    for (integer pattern = 0; pattern < 4; pattern = pattern + 1) begin
                      drive = pattern[1:0];
                      check(pattern[1:0]);
                    end
                    enable = 0;
                    for (integer pattern = 0; pattern < 4; pattern = pattern + 1) begin
                      external_drive = pattern[1:0];
                      check(pattern[1:0]);
                    end
                    enable = 1; drive = 2'b01; external_drive = 2'b10;
                    check(2'bxx);
                    $display("PASS Verilog inout: 10 cases, drive/receive/ZZ/XX and bit order");
                    $finish;
                  end
                endmodule
                """.formatted(plan.getTopLevelName(), external(plan, "drive", 0), external(plan, "drive", 1),
                        external(plan, "enable", 0), external(plan, "io", 0), external(plan, "io", 1),
                        external(plan, "sampled", 0), external(plan, "sampled", 1)) : """
                library ieee;
                use ieee.std_logic_1164.all;
                use ieee.numeric_std.all;
                use std.env.all;
                entity tb is end entity;
                architecture test of tb is
                  signal drive: std_logic_vector(1 downto 0);
                  signal enable: std_logic;
                  signal external_drive: std_logic_vector(1 downto 0);
                  signal pad: std_logic_vector(1 downto 0);
                  signal sampled: std_logic_vector(1 downto 0);
                begin
                  pad <= external_drive;
                  dut: entity work.%s port map (%s => drive(0), %s => drive(1), %s => enable,
                    %s => pad(0), %s => pad(1), %s => sampled(0), %s => sampled(1));
                  process
                    procedure check(expected: std_logic_vector(1 downto 0)) is
                    begin
                      wait for 1 ns;
                      assert pad = expected and sampled = expected
                        report "inout: expected=" & to_string(expected) & " pad=" & to_string(pad)
                          & " sampled=" & to_string(sampled) severity failure;
                    end procedure;
                  begin
                    enable <= '0'; drive <= "00"; external_drive <= "ZZ";
                    check("ZZ");
                    enable <= '1';
                    for pattern in 0 to 3 loop
                      drive <= std_logic_vector(to_unsigned(pattern, 2));
                      check(std_logic_vector(to_unsigned(pattern, 2)));
                    end loop;
                    enable <= '0';
                    for pattern in 0 to 3 loop
                      external_drive <= std_logic_vector(to_unsigned(pattern, 2));
                      check(std_logic_vector(to_unsigned(pattern, 2)));
                    end loop;
                    enable <= '1'; drive <= "01"; external_drive <= "10";
                    check("XX");
                    report "PASS VHDL inout: 10 cases, drive/receive/ZZ/XX and bit order" severity note;
                    stop;
                    wait;
                  end process;
                end architecture;
                """.formatted(plan.getTopLevelName(), external(plan, "drive", 0), external(plan, "drive", 1),
                        external(plan, "enable", 0), external(plan, "io", 0), external(plan, "io", 1),
                        external(plan, "sampled", 0), external(plan, "sampled", 1));
        Files.writeString(directory.resolve(language == FpgaHdlLanguage.VERILOG ? "tb.sv" : "tb.vhd"), bench);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: WrapperSimulation.java board.xml output-directory");
        var result = FpgaBoardCodec.INSTANCE.read(Files.readString(Path.of(args[0])));
        if (!(result instanceof FpgaBoardImportResult.Success success)) {
            throw new AssertionError("Board import failed: " + result);
        }
        var board = success.getBoard();
        for (String location : List.of("D19", "D17", "D14")) {
            var pin = board.getPins().stream().filter(candidate -> candidate.getLocation().equals(location))
                    .findFirst().orElseThrow();
            if (!pin.getElectrical().getActiveLow()) throw new AssertionError("Expected an active-low pin: " + location);
        }
        Path output = Path.of(args[1]);
        for (var language : FpgaHdlLanguage.values()) {
            String name = language == FpgaHdlLanguage.VERILOG ? "verilog" : "vhdl";
            combinational(board, output.resolve("combinational").resolve(name), language);
            inout(board, output.resolve("inout").resolve(name), language);
        }
        System.out.println("Generated four wrapper projects through FpgaProjectGenerator using " + board.getName());
    }
}
