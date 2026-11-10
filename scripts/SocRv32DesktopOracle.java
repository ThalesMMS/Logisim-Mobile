import com.cburch.logisim.soc.rv32im.*;
import com.cburch.logisim.soc.util.AssemblerExecutionInterface;
import java.lang.reflect.Field;
import java.util.Random;
import sun.misc.Unsafe;

/** Executes desktop instruction units without constructing their Swing debugger. */
public final class SocRv32DesktopOracle {
  private static void field(Object object, String name, Object value) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }
  public static void main(String[] args) throws Exception {
    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    Unsafe unsafe = (Unsafe) unsafeField.get(null);
    Random random = new Random(0x534F43);
    for (int operation = 0; operation < 18; operation++) {
      int funct3 = operation < 8 ? operation : operation < 16 ? operation - 8 : operation == 16 ? 0 : 5;
      int funct7 = operation < 8 ? 0 : operation < 16 ? 1 : 32;
      int instruction = funct7 << 25 | 2 << 20 | 1 << 15 | funct3 << 12 | 3 << 7 | 0x33;
      for (int sample = 0; sample < 16; sample++) {
        int a = random.nextInt();
        int b = random.nextInt();
        RV32imState.ProcessorState cpu = (RV32imState.ProcessorState) unsafe.allocateInstance(RV32imState.ProcessorState.class);
        field(cpu, "registers", new int[31]);
        field(cpu, "registers_valid", new Boolean[31]);
        field(cpu, "csrs", new int[19]);
        cpu.writeRegister(1, a); cpu.writeRegister(2, b);
        AssemblerExecutionInterface unit = funct7 == 1 ? new RV32im_M_ExtensionInstructions() : new RV32imIntegerRegisterRegisterOperations();
        if (!unit.setBinInstruction(instruction) || !unit.execute(cpu, null)) throw new IllegalStateException("Rejected instruction");
        System.out.printf("%08x,%08x,%08x,%08x%n", instruction, a, b, cpu.getRegisterValue(3));
      }
    }
  }
}
