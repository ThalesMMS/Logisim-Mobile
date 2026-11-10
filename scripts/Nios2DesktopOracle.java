import com.cburch.logisim.soc.nios2.Nios2Assembler;
import com.cburch.logisim.soc.nios2.Nios2State;
import com.cburch.logisim.soc.nios2.Nios2Support;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Emits deterministic ALU fixtures from the shipped desktop execution units without constructing UI. */
public final class Nios2DesktopOracle {
  private static final int[][] PAIRS = {
      {0, 1}, {1, -1}, {-1, 1}, {Integer.MIN_VALUE, -1},
      {Integer.MAX_VALUE, Integer.MIN_VALUE}, {0x12345678, 0x76543210},
      {0xAAAAAAAA, 31}, {0x55555555, 32}
  };
  private static final int[] R_OPX = {
      0x0e, 0x16, 0x1e, 0x06, 0x31, 0x39, 0x27, 0x18, 0x24,
      0x1f, 0x07, 0x17, 0x20, 0x08, 0x28, 0x10, 0x30,
      0x03, 0x0b, 0x13, 0x3b, 0x1b
  };
  private static final int[] I_OPC = {
      0x0c, 0x14, 0x1c, 0x2c, 0x34, 0x3c, 0x04, 0x24,
      0x20, 0x18, 0x08, 0x28, 0x10, 0x30
  };
  private static final int[] SHIFT_OPX = {0x02, 0x12, 0x3a, 0x1a};
  private static final Unsafe UNSAFE;
  static {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      UNSAFE = (Unsafe) field.get(null);
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  private static void set(Object owner, String field, Object value) throws Exception {
    Field target = owner.getClass().getDeclaredField(field);
    target.setAccessible(true);
    target.set(owner, value);
  }
  public static void main(String[] args) throws Exception {
    Nios2Assembler assembler = new Nios2Assembler();
    if (args.length > 0 && args[0].equals("--control")) {
      emitControlFixtures(assembler);
      return;
    }
    for (int opx : R_OPX) for (int[] pair : PAIRS)
      emit(assembler, Nios2Support.getRTypeInstructionCode(2, 3, 4, opx), pair[0], pair[1]);
    for (int opc : I_OPC) for (int[] pair : PAIRS)
      emit(assembler, Nios2Support.getITypeInstructionCode(2, 4, pair[1], opc), pair[0], pair[1]);
    for (int opx : SHIFT_OPX) for (int[] pair : PAIRS)
      emit(assembler, Nios2Support.getRTypeInstructionCode(2, 0, 4, opx, pair[1] & 31), pair[0], pair[1]);
  }
  private static void emitControlFixtures(Nios2Assembler assembler) throws Exception {
    int[] branches = {0x0e, 0x2e, 0x16, 0x36, 0x26, 0x1e};
    for (int opc : branches) for (int[] pair : PAIRS)
      emitControl(assembler, Nios2Support.getITypeInstructionCode(2, 3, -20, opc), pair[0], pair[1]);
    int[] instructions = {
      Nios2Support.getITypeInstructionCode(0, 0, -4, 0x06),
      Nios2Support.getJTypeInstructionCode(0x400 >> 2, 0x00),
      Nios2Support.getJTypeInstructionCode(0x400 >> 2, 0x01),
      Nios2Support.getRTypeInstructionCode(2, 0, 31, 0x1d),
      Nios2Support.getRTypeInstructionCode(31, 0, 0, 0x05),
      Nios2Support.getRTypeInstructionCode(2, 0, 0, 0x0d),
      Nios2Support.getRTypeInstructionCode(0, 0, 29, 0x2d, 7),
      Nios2Support.getRTypeInstructionCode(29, 30, 0, 0x01),
      Nios2Support.getRTypeInstructionCode(0, 0, 30, 0x34, 3),
      Nios2Support.getRTypeInstructionCode(30, 0, 30, 0x09)
    };
    for (int instruction : instructions) emitControl(assembler, instruction, 0x12345678, -1);
    for (int index = 0; index < 6; index++)
      emitControl(assembler, Nios2Support.getRTypeInstructionCode(0, 0, 4, 0x26, index), -1, 1);
    for (int index = 0; index < 4; index++)
      emitControl(assembler, Nios2Support.getRTypeInstructionCode(2, 0, 0, 0x2e, index), -1, 1);
  }
  private static void emitControl(Nios2Assembler assembler, int instruction, int a, int b) throws Exception {
    Nios2State outer = (Nios2State) UNSAFE.allocateInstance(Nios2State.class);
    set(outer, "exceptionVector", 0x80);
    set(outer, "breakVector", 0x100);
    Nios2State.ProcessorState cpu = (Nios2State.ProcessorState)
        UNSAFE.allocateInstance(Nios2State.ProcessorState.class);
    set(cpu, "this$0", outer);
    Field appContext = java.awt.Component.class.getDeclaredField("appContext");
    long contextOffset = UNSAFE.objectFieldOffset(appContext);
    UNSAFE.putObject(cpu, contextOffset, UNSAFE.getObject(new javax.swing.JPanel(), contextOffset));
    int[] registers = new int[32];
    registers[1] = a; registers[2] = b;
    registers[28] = 0x204; registers[29] = 0x304; registers[30] = 0x404;
    Boolean[] known = new Boolean[32];
    java.util.Arrays.fill(known, true);
    set(cpu, "registers", registers);
    set(cpu, "registers_valid", known);
    set(cpu, "pc", 0x20);
    set(cpu, "status", 0x800001);
    set(cpu, "estatus", 0xA5000001);
    set(cpu, "bstatus", 0x5A000001);
    set(cpu, "ienable", 0xAA55AA55);
    set(cpu, "ipending", 0);
    assembler.decode(instruction);
    var execution = assembler.getExeUnit();
    if (execution == null || !execution.execute(cpu, null))
      throw new IllegalStateException(String.format("Cannot execute %08X", instruction));
    if (!execution.performedJump()) cpu.setProgramCounter(cpu.getProgramCounter() + 4);
    System.out.printf("%08X %08X %08X %08X %08X %08X %08X %08X %08X %08X %08X %08X %s%n",
        instruction, a, b, cpu.getProgramCounter(), cpu.getRegisterValue(4),
        cpu.getRegisterValue(29), cpu.getRegisterValue(30), cpu.getRegisterValue(31),
        cpu.getControlRegister(0), cpu.getControlRegister(1), cpu.getControlRegister(2),
        cpu.getControlRegister(3), execution.getAsmInstruction().trim());
  }
  private static void emit(Nios2Assembler assembler, int instruction, int a, int b) throws Exception {
    Nios2State.ProcessorState cpu = (Nios2State.ProcessorState)
        UNSAFE.allocateInstance(Nios2State.ProcessorState.class);
    int[] registers = new int[32];
    registers[1] = a;
    registers[2] = b;
    Boolean[] known = new Boolean[32];
    java.util.Arrays.fill(known, true);
    set(cpu, "registers", registers);
    set(cpu, "registers_valid", known);
    assembler.decode(instruction);
    var execution = assembler.getExeUnit();
    if (execution == null || !execution.execute(cpu, null))
      throw new IllegalStateException(String.format("Cannot execute %08X", instruction));
    System.out.printf("%08X %08X %08X %08X %s%n", instruction, a, b,
        cpu.getRegisterValue(4), execution.getAsmInstruction().trim());
  }
}
