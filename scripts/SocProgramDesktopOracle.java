import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.soc.data.SocBusTransaction;
import com.cburch.logisim.soc.data.SocUpStateInterface;
import com.cburch.logisim.soc.pio.PioState;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Method;

/** Runs a complete sum-and-MMIO program through the desktop CPU, bus, RAM and PIO components. */
public final class SocProgramDesktopOracle {
  private static final int[] RV32_PROGRAM = {
    0x00000093, // addi x1,zero,0
    0x00100113, // addi x2,zero,1
    0x00600193, // addi x3,zero,6
    0x002080B3, // add x1,x1,x2
    0x00110113, // addi x2,x2,1
    0xFE314CE3, // blt x2,x3,pc-8
    0x00001237, // lui x4,0x1
    0x00122023, // sw x1,0(x4)
    0x10102023, // sw x1,0x100(zero)
    0x10002283, // lw x5,0x100(zero)
  };
  private static final int[] NIOS2_PROGRAM = {
    0x00800004, // movi r2,0
    0x00C00044, // movi r3,1
    0x01000184, // movi r4,6
    0x10C5883A, // add r2,r2,r3
    0x18C00044, // addi r3,r3,1
    0x193FFD16, // blt r3,r4,pc-8
    0x01440004, // movi r5,0x1000
    0x28800015, // stw r2,0(r5)
    0x00804015, // stw r2,0x100(zero)
    0x01804017, // ldw r6,0x100(zero)
  };

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected a program-nios2.circ or program-rv32.circ fixture.");
    // The bus constructs a JDialog internally. A graphics environment is required even with no visible window.
    Main.headless = true;
    Thread watchdog = new Thread(() -> {
      try { Thread.sleep(15000); } catch (InterruptedException ignored) { return; }
      System.err.println("Desktop SoC oracle exceeded its 15-second deadline.");
      Runtime.getRuntime().halt(124);
    });
    watchdog.setDaemon(true);
    watchdog.start();
    Project project = null;
    int exit = 0;
    try {
      var file = new Loader(null).openLogisimFile(new File(args[0]));
      project = new Project(file);
      var circuit = file.getMainCircuit();
      var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
      var propagator = state.getPropagator();
      propagator.propagate();
      Component component = circuit.getNonWires().stream().filter(c ->
          c.getFactory().getName().equals("Rv32im") || c.getFactory().getName().equals("Nios2"))
          .findFirst().orElseThrow();
      boolean rv32 = component.getFactory().getName().equals("Rv32im");
      Object cpu = state.getData(component);
      if (cpu == null) throw new IllegalStateException("Desktop CPU did not initialize.");
      Method execute = cpu.getClass().getMethod("execute", CircuitState.class);
      Method register = cpu.getClass().getMethod("getRegisterValue", int.class);
      Method transact = cpu.getClass().getMethod("insertTransaction", SocBusTransaction.class, boolean.class, CircuitState.class);
      SocUpStateInterface processor = (SocUpStateInterface) cpu;
      int[] words = rv32 ? RV32_PROGRAM : NIOS2_PROGRAM;
      for (int i = 0; i < words.length; i++) {
        var transaction = new SocBusTransaction(SocBusTransaction.WRITE_TRANSACTION, i * 4, words[i], SocBusTransaction.WORD_ACCESS, component);
        transact.invoke(cpu, transaction, true, state);
        if (transaction.hasError()) throw new IllegalStateException(transaction.getErrorMessage());
      }
      var initializeResult = new SocBusTransaction(SocBusTransaction.WRITE_TRANSACTION, 0x100, 0, SocBusTransaction.WORD_ACCESS, component);
      transact.invoke(cpu, initializeResult, true, state);
      if (initializeResult.hasError()) throw new IllegalStateException(initializeResult.getErrorMessage());
      processor.getSimState().buttonPressed();
      int sumRegister = rv32 ? 1 : 2;
      int counterRegister = rv32 ? 2 : 3;
      int loadedRegister = rv32 ? 5 : 6;
      var pioComponent = circuit.getNonWires().stream().filter(c -> c.getFactory().getName().equals("SocPio")).findFirst().orElseThrow();
      System.out.println("architecture,step,pc,sum,counter,loaded,pio,ram");
      for (int step = 1; step <= 22; step++) {
        if (!processor.getSimState().canExecute()) throw new IllegalStateException("CPU stopped before instruction " + step);
        execute.invoke(cpu, state);
        propagator.propagate();
        if (!processor.getSimState().canExecute()) throw new IllegalStateException("CPU failed at instruction " + step);
        var pio = (PioState.PioRegState) state.getData(pioComponent);
        var result = new SocBusTransaction(SocBusTransaction.READ_TRANSACTION, 0x100, 0, SocBusTransaction.WORD_ACCESS, component);
        transact.invoke(cpu, result, true, state);
        if (result.hasError()) throw new IllegalStateException(result.getErrorMessage());
        System.out.printf("%s,%d,%08x,%08x,%08x,%08x,%08x,%08x%n", rv32 ? "rv32im" : "nios2", step,
            processor.getProgramCounter(), register.invoke(cpu, sumRegister), register.invoke(cpu, counterRegister),
            register.invoke(cpu, loadedRegister), pio.outputRegister, result.getReadData());
      }
      var memory = new SocBusTransaction(SocBusTransaction.READ_TRANSACTION, 0x100, 0, SocBusTransaction.WORD_ACCESS, component);
      transact.invoke(cpu, memory, true, state);
      if (memory.hasError() || memory.getReadData() != 15 || processor.getProgramCounter() != 40 ||
          ((Integer) register.invoke(cpu, sumRegister)) != 15 || ((Integer) register.invoke(cpu, counterRegister)) != 6 ||
          ((Integer) register.invoke(cpu, loadedRegister)) != 15 || ((PioState.PioRegState) state.getData(pioComponent)).outputRegister != 15)
        throw new AssertionError("Sum program did not produce PC40, sum15, counter6, loaded15, RAM15, PIO15.");
      System.out.println("verified,pc=40,sum=15,counter=6,loaded=15,ram=15,pio=15");
    } catch (Throwable failure) {
      failure.printStackTrace();
      exit = 1;
    } finally {
      if (project != null) project.getSimulator().shutDown();
      for (Window window : Window.getWindows()) window.dispose();
      watchdog.interrupt();
    }
    System.exit(exit);
  }
}
