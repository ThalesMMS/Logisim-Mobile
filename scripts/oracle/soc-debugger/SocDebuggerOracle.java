import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.soc.data.SocUpStateInterface;
import com.cburch.logisim.soc.file.ElfHeader;
import com.cburch.logisim.soc.file.ProcessorReadElf;
import com.cburch.logisim.soc.gui.BreakpointPanel;
import com.cburch.logisim.soc.nios2.Nios2State;
import com.cburch.logisim.soc.rv32im.RV32imState;
import com.cburch.logisim.soc.util.Assembler;
import com.cburch.logisim.std.wiring.Pin;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.CircuitDocument;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.ComponentEvent;
import dev.logisim.mobile.core.ComponentRegistry;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import dev.logisim.mobile.core.SimulationState;
import dev.logisim.mobile.core.SocComponents;
import dev.logisim.mobile.core.SocLoadExecutableEvent;
import dev.logisim.mobile.core.SocStepEvent;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Compares the SoC debugger model of Evolution d58a61e0 with the Mobile core:
 * ELF loading, per-instruction trace text, register view (hex and unknown registers), breakpoints in run mode,
 * and the RV32 assembler. Usage: java SocDebuggerOracle REPO_ROOT OUTPUT_DIR
 */
public final class SocDebuggerOracle {
    static final int STEPS = 22;
    static final StringBuilder LOG = new StringBuilder();
    static final List<String> RESULTS = new ArrayList<>();

    // ------------------------------------------------------------------ Kotlin interop helpers

    /** Calls a Kotlin getter whose name may carry an inline-class mangling suffix. */
    static Object get(Object target, String name) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && (method.getName().equals(name) || method.getName().startsWith(name + "-"))) {
                return method.invoke(target);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    static long unsigned(Object value) {
        if (value instanceof Integer i) return Integer.toUnsignedLong(i);
        if (value instanceof Long l) return l;
        return Long.parseLong(value.toString());
    }

    static String hex(long value) { return String.format("%08x", value & 0xFFFFFFFFL); }

    /** Constructs a Kotlin class whose constructor takes inline-class parameters (hidden from Java). */
    static Object construct(Class<?> type, Object... arguments) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == arguments.length + 1 && parameters[parameters.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) {
                constructor.setAccessible(true);
                Object[] all = java.util.Arrays.copyOf(arguments, arguments.length + 1);
                return constructor.newInstance(all);
            }
            if (parameters.length == arguments.length) {
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            }
        }
        throw new NoSuchMethodException(type.getName());
    }

    static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException next) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static void log(String line) { LOG.append(line).append('\n'); System.out.println(line); }

    // ------------------------------------------------------------------ fixtures

    /** The fixture with an input pin driving the CPU clock, so both programs can run with breakpoints. */
    static File clocked(File fixture, boolean rv32) throws Exception {
        String xml = Files.readString(fixture.toPath());
        int clockY = rv32 ? 650 : 660;
        xml = xml.replace("<lib desc=\"#Soc\" name=\"8\"/>", "<lib desc=\"#Soc\" name=\"8\"/><lib desc=\"#Wiring\" name=\"0\"/>");
        xml = xml.replace("</circuit>", "<comp lib=\"0\" loc=\"(800," + clockY + ")\" name=\"Pin\"><a name=\"facing\" val=\"east\"/>"
            + "<a name=\"label\" val=\"clk\"/></comp></circuit>");
        File file = File.createTempFile("soc-debugger-" + (rv32 ? "rv32" : "nios2") + "-", ".circ");
        file.deleteOnExit();
        Files.writeString(file.toPath(), xml);
        return file;
    }

    // ------------------------------------------------------------------ Evolution

    static final class Evolution implements AutoCloseable {
        final Project project;
        final CircuitState state;
        final Component cpuComponent;
        final Object cpu;
        final SocUpStateInterface processor;
        final boolean rv32;
        Component clock;

        Evolution(File file) throws Exception {
            Main.headless = true;
            var logisim = new Loader(null).openLogisimFile(file);
            project = new Project(logisim);
            state = CircuitState.createRootState(project, logisim.getMainCircuit(), Thread.currentThread());
            state.getPropagator().propagate();
            cpuComponent = logisim.getMainCircuit().getNonWires().stream()
                .filter(c -> c.getFactory().getName().equals("Rv32im") || c.getFactory().getName().equals("Nios2")).findFirst().orElseThrow();
            clock = logisim.getMainCircuit().getNonWires().stream().filter(c -> c.getFactory() instanceof Pin).findFirst().orElse(null);
            rv32 = cpuComponent.getFactory().getName().equals("Rv32im");
            cpu = state.getData(cpuComponent);
            processor = (SocUpStateInterface) cpu;
        }

        boolean loadElf(File elf) {
            var reader = new ProcessorReadElf(elf, Instance.getInstanceFor(cpuComponent),
                rv32 ? ElfHeader.EM_RISCV : ElfHeader.EM_INTEL_NIOS2, true);
            if (!reader.canExecute()) { log("evolution ELF rejected: " + reader.getErrorMessage()); return false; }
            boolean loaded = reader.execute(state);
            if (!loaded) log("evolution ELF load failed: " + reader.getErrorMessage());
            return loaded;
        }

        void step() throws Exception {
            cpu.getClass().getMethod("execute", CircuitState.class).invoke(cpu, state);
            state.getPropagator().propagate();
        }

        void driveClock(boolean high) {
            Pin.FACTORY.driveInputPin(state.getInstanceState(clock), high ? Value.TRUE : Value.FALSE);
            state.markComponentAsDirty(clock);
            state.getPropagator().propagate();
        }

        /** The newest trace row: pc, instruction and the assembly text shown in the trace window. */
        String trace() throws Exception {
            var rows = (java.util.LinkedList<?>) field(cpu, "instrTrace");
            if (rows.isEmpty()) return "(empty)";
            Object row = rows.getFirst();
            return hex(((Integer) field(row, "pc"))) + " " + hex(((Integer) field(row, "instruction"))) + " " + field(row, "asm");
        }

        String registers() {
            StringBuilder result = new StringBuilder("pc=" + hex(processor.getProgramCounter()));
            for (int index = 0; index < 32; index++) {
                String value = processor.getRegisterValueHex(index);
                result.append(' ').append(index).append('=').append(value.startsWith("0x") ? value.substring(2).toLowerCase() : "????????");
            }
            return result.toString();
        }

        void breakpoints(Map<Integer, Integer> addresses) throws Exception {
            String id = (rv32 ? RV32imState.ASSEMBLER : Nios2State.ASSEMBLER).getHighlightStringIdentifier();
            BreakpointPanel panel = new BreakpointPanel(id) {
                @Override public Map<Integer, Integer> getBreakPoints() { return addresses; }
                @Override public void gotoLine(int line) { }
            };
            setField(cpu, "bPanel", panel);
        }

        String simulation() throws Exception {
            var sim = processor.getSimState();
            return sim.canExecute() ? "running" : ((Integer) field(sim, "simulationState") == 2 ? "breakpoint" : "stopped-" + field(sim, "simulationState"));
        }

        @Override public void close() {
            project.getSimulator().shutDown();
            for (Window window : Window.getWindows()) window.dispose();
        }
    }

    // ------------------------------------------------------------------ Mobile

    static final class Mobile {
        final ComponentRegistry registry = ComponentCatalog.INSTANCE.getRegistry().withDefinitions(SocComponents.INSTANCE.definitions());
        final CircuitDocument document;
        final CircuitSimulator simulator = new CircuitSimulator(256, registry);
        final CircuitComponent cpu;
        final String clockId;
        final Map<String, LogicVector> inputs = new HashMap<>();
        SimulationState state;

        Mobile(File file) throws Exception {
            Object result = CircCodec.INSTANCE.readProject(Files.readString(file.toPath()), registry);
            if (!(result instanceof CircProjectImportResult.Success success)) throw new AssertionError("Mobile rejected " + file + ": " + result);
            document = success.getProject().getMainCircuit();
            cpu = document.getComponents().stream().filter(c -> c.getKind().getName().equals("RV32IM") || c.getKind().getName().equals("NIOS2")
                || c.getKind().equals(SocComponents.INSTANCE.getRV32()) || c.getKind().equals(SocComponents.INSTANCE.getNIOS2())).findFirst().orElseThrow();
            clockId = document.getComponents().stream().filter(c -> c.getLabel().equals("clk")).map(CircuitComponent::getId).findFirst().orElse(null);
            if (clockId != null) inputs.put(clockId, LogicVector.Companion.fromBits(List.of(LogicValue.LOW)));
            state = simulator.settle(document, new SimulationState(), Map.of(), false, inputs);
        }

        void send(ComponentEvent... events) {
            state = simulator.settle(document, state, Map.of(), false, inputs, Map.of(cpu.getId(), List.of(events)));
        }

        void driveClock(boolean high) {
            inputs.put(clockId, LogicVector.Companion.fromBits(List.of(high ? LogicValue.HIGH : LogicValue.LOW)));
            state = simulator.settle(document, state, Map.of(), false, inputs);
        }

        Object cpuState() throws Exception { return get(state.getComponentStates().get(cpu.getId()), "getCpu"); }

        Object debugger() throws Exception { return get(state.getComponentStates().get(cpu.getId()), "getDebugger"); }

        String trace() throws Exception {
            List<?> rows = (List<?>) get(cpuState(), "getTrace");
            if (rows.isEmpty()) return "(empty)";
            Object row = rows.get(0); // newest first, like Evolution's trace window
            return hex(unsigned(get(row, "getPc"))) + " " + hex(unsigned(get(row, "getInstruction"))) + " " + get(row, "getAssembly");
        }

        String registers() throws Exception {
            Object cpu = cpuState();
            long known = unsigned(get(cpu, "getKnownRegisters"));
            List<?> registers = (List<?>) get(cpu, "getRegisters");
            StringBuilder result = new StringBuilder("pc=" + hex(unsigned(get(cpu, "getPc"))));
            for (int index = 0; index < 32; index++) {
                boolean valid = index == 0 || ((known >> index) & 1) == 1;
                long value = index == 0 ? 0 : unsigned(registers.get(index));
                result.append(' ').append(index).append('=').append(valid ? hex(value) : "????????");
            }
            return result.toString();
        }

        String simulation() throws Exception {
            Object hit = get(debugger(), "getBreakpointHit");
            boolean stopped = (Boolean) get(cpuState(), "getStopped");
            return hit != null ? "breakpoint" : stopped ? "stopped" : "running";
        }
    }

    // ------------------------------------------------------------------ comparisons

    static String normalizeAsm(String text) {
        return text.trim().replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", " ").toLowerCase();
    }

    static void result(String name, boolean same, String detail) {
        RESULTS.add("{\"comparison\": \"" + name + "\", \"result\": \"" + (same ? "SAME" : "DIFF") + "\", \"detail\": \""
            + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        log((same ? "SAME " : "DIFF ") + name + ": " + detail);
    }

    static void elfTrace(File repo, String architecture) throws Exception {
        boolean rv32 = architecture.equals("rv32");
        File fixture = new File(repo, "fixtures/soc/program-" + architecture + ".circ");
        File elf = new File(repo, "fixtures/soc/sum-" + architecture + ".elf");
        log("\n=== " + architecture + ": load " + elf.getName() + " and step " + STEPS + " instructions");
        int registerDiffs = 0, wordDiffs = 0, textDiffs = 0, normalizedDiffs = 0;
        List<String> examples = new ArrayList<>();
        try (Evolution evolution = new Evolution(fixture)) {
            Mobile mobile = new Mobile(fixture);
            boolean loaded = evolution.loadElf(elf);
            mobile.send(new SocLoadExecutableEvent(Files.readAllBytes(elf.toPath())));
            result(architecture + " elf-load", loaded, "Evolution loaded=" + loaded + "; registers after load: evolution "
                + evolution.registers().substring(0, 12) + ", mobile " + mobile.registers().substring(0, 12));
            boolean sameStart = evolution.registers().equals(mobile.registers());
            result(architecture + " register-view-after-load", sameStart, sameStart ? "pc and 32 registers, unknown registers as ????????"
                : "evolution " + evolution.registers() + " | mobile " + mobile.registers());
            evolution.processor.getSimState().buttonPressed();
            for (int step = 1; step <= STEPS; step++) {
                evolution.step();
                mobile.send(SocStepEvent.INSTANCE);
                String et = evolution.trace(), mt = mobile.trace();
                String er = evolution.registers(), mr = mobile.registers();
                log(String.format("step %2d evolution %-40s | mobile %s", step, et, mt));
                if (!er.equals(mr)) { registerDiffs++; log("        registers evolution " + er + "\n        registers mobile    " + mr); }
                String[] e = et.split(" ", 3), m = mt.split(" ", 3);
                if (!e[0].equals(m[0]) || !e[1].equals(m[1])) wordDiffs++;
                String ea = e.length > 2 ? e[2] : "", ma = m.length > 2 ? m[2] : "";
                if (!ea.equals(ma)) textDiffs++;
                if (!normalizeAsm(ea).equals(normalizeAsm(ma))) {
                    normalizedDiffs++;
                    if (examples.size() < 4) examples.add("'" + ea.trim() + "' vs '" + ma.trim() + "'");
                }
            }
        }
        result(architecture + " register-view-per-step", registerDiffs == 0, registerDiffs + " of " + STEPS + " steps differ (pc, x0..x31, unknown marks)");
        result(architecture + " trace-pc-and-word", wordDiffs == 0, wordDiffs + " of " + STEPS + " trace rows differ in pc or instruction word");
        result(architecture + " trace-assembly-text", normalizedDiffs == 0 && textDiffs == 0, normalizedDiffs + " of " + STEPS
            + " rows differ after normalizing spaces and case (" + textDiffs + " differ byte for byte)" + (examples.isEmpty() ? "" : "; e.g. " + String.join("; ", examples)));
    }

    /** Run mode with a clock pin, a breakpoint at 0x14 (the loop branch) and two resumes. */
    static void breakpoints(File repo, String architecture) throws Exception {
        boolean rv32 = architecture.equals("rv32");
        File fixture = clocked(new File(repo, "fixtures/soc/program-" + architecture + ".circ"), rv32);
        File elf = new File(repo, "fixtures/soc/sum-" + architecture + ".elf");
        log("\n=== " + architecture + ": run with a breakpoint at 0x00000014");
        List<String> evolutionHalts = new ArrayList<>(), mobileHalts = new ArrayList<>();
        try (Evolution evolution = new Evolution(fixture)) {
            Mobile mobile = new Mobile(fixture);
            evolution.loadElf(elf);
            mobile.send(new SocLoadExecutableEvent(Files.readAllBytes(elf.toPath())));
            Map<Integer, Integer> points = new HashMap<>();
            points.put(0x14, 6);
            evolution.breakpoints(points);
            Class<?> breakpoint = Class.forName("dev.logisim.mobile.core.SocSetBreakpointEvent");
            Class<?> running = Class.forName("dev.logisim.mobile.core.SocSetRunningEvent");
            mobile.send((ComponentEvent) construct(breakpoint, 0x14, true));
            for (int resume = 0; resume < 3; resume++) {
                evolution.processor.getSimState().buttonPressed();
                mobile.send((ComponentEvent) construct(running, true));
                String evolutionHalt = null, mobileHalt = null;
                for (int tick = 1; tick <= 40 && (evolutionHalt == null || mobileHalt == null); tick++) {
                    if (evolutionHalt == null) {
                        evolution.driveClock(true);
                        evolution.driveClock(false);
                        if (evolution.simulation().equals("breakpoint")) evolutionHalt = "tick " + tick + " " + evolution.registers().substring(0, 11);
                    }
                    if (mobileHalt == null) {
                        mobile.driveClock(true);
                        mobile.driveClock(false);
                        if (mobile.simulation().equals("breakpoint")) mobileHalt = "tick " + tick + " " + mobile.registers().substring(0, 11);
                    }
                }
                evolutionHalts.add(evolutionHalt + " " + evolution.registers().split(" ")[2]);
                mobileHalts.add(mobileHalt + " " + mobile.registers().split(" ")[2]);
                log("resume " + resume + ": evolution " + evolutionHalts.get(resume) + " | mobile " + mobileHalts.get(resume));
            }
        }
        result(architecture + " breakpoint-run", evolutionHalts.equals(mobileHalts), "halts evolution " + evolutionHalts + " mobile " + mobileHalts);
    }

    // ------------------------------------------------------------------ assembler

    static Map<Long, Integer> evolutionAssemble(String source, StringBuilder errors) throws Exception {
        return evolutionAssemble(source, errors, true);
    }

    static Map<Long, Integer> evolutionAssemble(String source, StringBuilder errors, boolean rv32) throws Exception {
        var unit = rv32 ? RV32imState.ASSEMBLER : Nios2State.ASSEMBLER;
        String id = unit.getHighlightStringIdentifier();
        var factory = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
        factory.putMapping(id, rv32 ? "com.cburch.logisim.soc.rv32im.RV32imSyntaxHighlighter" : "com.cburch.logisim.soc.nios2.Nios2SyntaxHighlighter");
        var area = new RSyntaxTextArea(20, 60);
        area.setSyntaxEditingStyle(id);
        area.setText(source);
        var pane = new RTextScrollPane(area);
        var assembler = new Assembler(unit, pane);
        boolean ok = assembler.assemble();
        Map<Long, Integer> bytes = new TreeMap<>();
        if (!ok) {
            @SuppressWarnings("unchecked")
            var markers = (Map<Object, Object>) field(assembler, "errorMarkers");
            for (var entry : markers.entrySet()) {
                int offset = ((org.fife.ui.rtextarea.GutterIconInfo) entry.getKey()).getMarkedOffset();
                errors.append("line ").append(area.getLineOfOffset(offset) + 1).append(": ")
                    .append(entry.getValue()).append("; ");
            }
            return null;
        }
        var info = field(assembler, "assemblerInfo");
        var sections = field(info, "sections");
        for (Object section : (List<?>) sections.getClass().getMethod("getAll").invoke(sections)) {
            @SuppressWarnings("unchecked")
            var data = (Map<Long, Byte>) field(section, "data");
            data.forEach((address, value) -> bytes.put(address, value & 0xFF));
        }
        return bytes;
    }

    static Map<Long, Integer> mobileAssemble(String source, long address, StringBuilder errors) throws Exception {
        return mobileAssemble(source, address, errors, true);
    }

    static Map<Long, Integer> mobileAssemble(String source, long address, StringBuilder errors, boolean rv32) throws Exception {
        Class<?> type = Class.forName(rv32 ? "dev.logisim.mobile.core.Rv32Assembler" : "dev.logisim.mobile.core.Nios2Assembler");
        Object instance = type.getField("INSTANCE").get(null);
        Method assemble = null;
        for (Method method : type.getMethods()) if (method.getName().startsWith("assemble")) assemble = method;
        Object result = assemble.invoke(instance, source, (int) address);
        if (result.getClass().getSimpleName().equals("Rejected")) {
            errors.append("line ").append(get(result, "getLine")).append(": ").append(get(get(result, "getNotice"), "getKey"));
            return null;
        }
        Map<Long, Integer> bytes = new TreeMap<>();
        Object image = get(result, "getImage");
        for (Object segment : (List<?>) get(image, "getSegments")) {
            long start = unsigned(get(segment, "getAddress"));
            byte[] data = (byte[]) get(segment, "getBytes");
            for (int index = 0; index < data.length; index++) bytes.put(start + index, data[index] & 0xFF);
        }
        return bytes;
    }

    /**
     * Non-zero memory words. Evolution's sections store only non-zero bytes and fill the rest of
     * the section with zeros; Mobile's segments are dense, so both are compared as memory contents.
     */
    static String words(Map<Long, Integer> bytes) {
        if (bytes == null) return "rejected";
        StringBuilder result = new StringBuilder();
        var aligned = new java.util.TreeSet<Long>();
        for (long address : bytes.keySet()) aligned.add(address & ~3L);
        for (long address : aligned) {
            long word = 0;
            for (int index = 3; index >= 0; index--) word = (word << 8) | bytes.getOrDefault(address + index, 0);
            if (word == 0) continue;
            if (result.length() > 0) result.append(' ');
            result.append(hex(address)).append(':').append(hex(word));
        }
        return result.isEmpty() ? "(no data)" : result.toString();
    }

    static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put("sum-program", String.join("\n",
            "start:", "  addi x1, x0, 0", "  addi x2, x0, 1", "  addi x3, x0, 6", "loop:", "  add x1, x1, x2",
            "  addi x2, x2, 1", "  blt x2, x3, loop", "  lui x4, 0x1", "  sw x1, 0(x4)", "  sw x1, 0x100(x0)", "  lw x5, 0x100(x0)", ""));
        SOURCES.put("pseudo-instructions", String.join("\n",
            "  nop", "  li x5, 42", "  mv x6, x5", "  not x7, x6", "  j end", "end:", "  ret", ""));
        SOURCES.put("data-directives", String.join("\n",
            ".text", "  addi x1, x0, 1", ".data", "value:", "  .word 0x12345678", ""));
        SOURCES.put("sections-and-data", String.join("\n",
            ".text", "_start:", "  lui x5, table", "  addi x5, x5, 4*2+1", "  beqz x5, pc+8", "  csrw mtvec, x5", "  csrrsi x6, 0x300, 8",
            ".data", "table:", "  .byte 1, 2, 0xff", "  .half 0x1234", "  .ascii \"ab\\n\"", "  .string \"z\"", "  .zero 3",
            "  .dword -2", ".section .rodata", "  .equ LIMIT 12", "  .4byte 0xCAFEBABE", ".bss", "  .org 0x80", "  .word 5", ""));
        SOURCES.put("defines-and-math", String.join("\n",
            "  .equ STEP 3", "start:", "  addi x1, x0, STEP", "  slli x2, x1, 10>>2", "  andi x3, x2, 0x7f0 % 0x100", "  jal ra, start",
            "  bne x1, x2, start", "  sw x1, -4*2(sp)", "  lbu x9, 1+1(gp)", "  srai x4, x3, 31", ""));
        SOURCES.put("invalid-operand", String.join("\n", "  addi x1, x0, 5000", ""));
        SOURCES.put("unknown-mnemonic", String.join("\n", "  addi x1, x0, 1", "  frob x1, x2", ""));
        // #357: macros (.macro NAME N ... .endm, @1..@N, local labels) and their errors.
        SOURCES.put("macro-inc", String.join("\n", ".macro inc 1", "  addi @1, @1, 1", ".endm", "start:", "  inc x5", ""));
        SOURCES.put("macro-parameters", String.join("\n", ".macro add3 3", "  add @1, @2, @3", "  sub @3, @1, @2", ".endm", "start:", "  add3 x5, x6, x7", "  add3 a0, a1, zero", "  addi x1, x0, 4*2+1", ""));
        SOURCES.put("macro-local-backward", String.join("\n", ".macro countdown 1", "again:", "  addi @1, @1, -1", "  bnez @1, again", ".endm", "start:", "  li x5, 3", "  countdown x5", "  ebreak", ""));
        SOURCES.put("macro-local-forward", String.join("\n", ".macro clamp 2", "  blt @1, @2, done", "  mv @1, @2", "done:", ".endm", "start:", "  nop", "  clamp x5, x6", "  clamp x7, x8", ""));
        SOURCES.put("macro-nested", String.join("\n", ".macro inc 1", "  addi @1, @1, 1", ".endm", ".macro inc2 2", "  inc @1", "  inc @2", "skip:", "  beq @1, @2, skip", ".endm", "start:", "  inc2 x5, x6", "  inc x7", ""));
        SOURCES.put("macro-defined-after-use", String.join("\n", "start:", "  zero3", "  j start", ".macro zero3 0", "  li x5, 0", "  li x6, 0", "  li x7, 0", ".endm", ""));
        SOURCES.put("macro-global-labels", String.join("\n", ".macro callit 1", "  jal ra, @1", "  j start", "  la x5, start", "back:", "  bne x5, x0, back", ".endm", "start:", "  callit func", "func:", "  ret", ""));
        SOURCES.put("macro-pseudo-sizes", String.join("\n", ".macro far 1", "  call @1", "  la x6, @1", "tail:", "  j tail", ".endm", "start:", "  far start", "  far start", ""));
        SOURCES.put("macro-hex-literal", String.join("\n", ".macro mask 1", "  andi @1, @1, 0x7F", "  ori @1, @1, 0x10", ".endm", "start:", "  mask x5", ""));
        SOURCES.put("macro-lui-label-parameter", String.join("\n", ".macro hi 2", "  lui @1, @2", ".endm", "start:", "  hi x5, target", ".data", "  .word 1", "target:", "  .word 2", ""));
        SOURCES.put("macro-lui-body-label", String.join("\n", ".macro hi 1", "  lui @1, target", ".endm", "start:", "  hi x5", ".org 0x2000", "target:", "  nop", ""));
        SOURCES.put("macro-lui-hex-body", String.join("\n", ".macro hi 1", "  lui @1, 0x12", ".endm", "start:", "  hi x5", ""));
        SOURCES.put("macro-lui-decimal-body", String.join("\n", ".macro hi 1", "  lui @1, 18", ".endm", "start:", "  hi x5", ""));
        SOURCES.put("macro-pc-parameter-twice", String.join("\n", ".macro twice 1", "  beq x0, x0, @1", "  beq x0, x0, @1", ".endm", "start:", "  twice pc+8", ""));
        SOURCES.put("macro-parameter-math", String.join("\n", ".macro off 1", "  addi x1, x0, @1+4", ".endm", "start:", "  off 2", ""));
        SOURCES.put("macro-expression-parameter", String.join("\n", ".macro ld 2", "  lw x5, @1(@2)", ".endm", "start:", "  ld 8, sp", ""));
        SOURCES.put("macro-in-data-section", String.join("\n", ".macro two 0", "  nop", "  nop", ".endm", ".text", "start:", "  two", ".data", "  .word 7", ".text", "  two", ""));
        SOURCES.put("macro-duplicate-name", String.join("\n", ".macro m 0", "  addi x1, x0, 1", ".endm", ".macro m 0", "  addi x1, x0, 2", ".endm", "start:", "  m", ""));
        SOURCES.put("macro-unused-garbage", String.join("\n", ".macro junk 0", "  bogus", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-hex-count", String.join("\n", ".macro inc 0x1", "  addi @1, @1, 1", ".endm", "start:", "  inc x5", ""));
        SOURCES.put("macro-global-labels-jumps", String.join("\n", ".macro callit 1", "  jal ra, @1", "  j start", "back:", "  bne x5, x0, back", ".endm", "start:", "  callit func", "func:", "  ret", ""));
        SOURCES.put("macro-pseudo-sizes-accepted", String.join("\n", ".macro far 1", "  li x5, 0x12345", "  la x6, @1", "tail:", "  j tail", ".endm", "start:", "  far start", "  far start", ""));
        SOURCES.put("macro-pc-parameter-once", String.join("\n", ".macro once 1", "  beq x0, x0, @1", ".endm", "start:", "  nop", "  once pc+8", ""));
        SOURCES.put("macro-in-data-section-accepted", String.join("\n", ".macro two 0", "  nop", "  nop", ".endm", ".text", "start:", "  two", ".data", "  two", "  .word 7", ""));
        SOURCES.put("macro-error-garbage-called", String.join("\n", ".macro junk 0", "  bogus", ".endm", "start:", "  junk", ""));
        SOURCES.put("macro-error-missing-endm", String.join("\n", ".macro inc 1", "  addi @1, @1, 1", "start:", "  nop", ""));
        SOURCES.put("macro-error-no-name", String.join("\n", ".macro 1", "  nop", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-no-count", String.join("\n", "start:", "  nop", ".macro foo", "  nop", ".endm", ""));
        SOURCES.put("macro-error-eof-name", String.join("\n", "start:", "  nop", ".macro", ""));
        SOURCES.put("macro-error-directive-inside", String.join("\n", ".macro bad 0", "  .word 5", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-nested-definition", String.join("\n", ".macro outer 0", ".macro inner 0", "  nop", ".endm", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-undefined-parameter", String.join("\n", ".macro bad 2", "  add @1, @2, @3", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-parameter-zero", String.join("\n", ".macro bad 1", "  addi @0, @1, 1", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-bare-at", String.join("\n", ".macro bad 1", "  addi @1, @, 1", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-too-few", String.join("\n", ".macro add3 3", "  add @1, @2, @3", ".endm", "start:", "  add3 x5, x6", ""));
        SOURCES.put("macro-error-too-many", String.join("\n", ".macro inc 1", "  addi @1, @1, 1", ".endm", "start:", "  inc x5, x6", ""));
        SOURCES.put("macro-error-empty-parameter", String.join("\n", ".macro inc 1", "  addi @1, @1, 1", ".endm", "start:", "  inc , x5", ""));
        SOURCES.put("macro-error-recursion", String.join("\n", ".macro loop 0", "  nop", "  loop", ".endm", "start:", "  loop", ""));
        SOURCES.put("macro-error-mutual", String.join("\n", ".macro a 0", "  b", ".endm", ".macro b 0", "  a", ".endm", "start:", "  nop", ""));
        SOURCES.put("macro-error-local-outside", String.join("\n", ".macro m 0", "inner:", "  nop", ".endm", "start:", "  m", "  j inner", ""));
        SOURCES.put("macro-error-shared-local", String.join("\n", ".macro m1 0", "here:", "  nop", ".endm", ".macro m2 0", "here:", "  nop", ".endm", "start:", "  m1", ""));
        SOURCES.put("macro-error-label-name", String.join("\n", "start:", "  nop", ".macro start 0", "  nop", ".endm", ""));
        SOURCES.put("macro-error-stray-endm", String.join("\n", "start:", "  nop", ".endm", ""));
        SOURCES.put("macro-error-parameter-outside", String.join("\n", "start:", "  addi x1, x0, @1", ""));
        SOURCES.put("macro-error-body-register", String.join("\n", ".macro bad 1", "  @1", ".endm", "start:", "  bad x5", ""));
    }

    static final Map<String, String> NIOS2_SOURCES = new LinkedHashMap<>();
    static {
        NIOS2_SOURCES.put("loop-program", String.join("\n",
            "_start:", "  movi r2, 0", "  movi r3, 1", "  movi r4, 6", "loop:", "  add r2, r2, r3", "  addi r3, r3, 1",
            "  blt r3, r4, loop", "  movia r5, 0x12348000", "  stw r2, 0(r5)", "  ldw r6, 0x100(zero)", "  ret", ""));
        NIOS2_SOURCES.put("sections-and-data", String.join("\n",
            ".text", "  call handler", "  br end", "handler:", "  wrctl ctl0, r3", "  rdctl r7, ctl1", "  ret", "end:", "  break",
            ".data", "  .word 0x12345678, -1", "  .asciz \"Nios\"", "  .byte 7", ""));
        NIOS2_SOURCES.put("invalid-register", String.join("\n", "  add r2, r3, r32", ""));
        NIOS2_SOURCES.put("unknown-mnemonic", String.join("\n", "  nop", "  frob r1, r2", ""));
        // #357: macros.
        NIOS2_SOURCES.put("macro-inc", String.join("\n", ".macro inc 1", "  addi @1, @1, 1", ".endm", "_start:", "  inc r5", ""));
        NIOS2_SOURCES.put("macro-local-backward", String.join("\n", ".macro countdown 1", "again:", "  subi @1, @1, 1", "  bne @1, zero, again", ".endm", "_start:", "  movi r5, 3", "  countdown r5", "  break", ""));
        NIOS2_SOURCES.put("macro-local-forward", String.join("\n", ".macro skipz 1", "  beq @1, zero, out", "  addi @1, @1, -1", "out:", ".endm", "_start:", "  skipz r2", "  skipz r3", ""));
        NIOS2_SOURCES.put("macro-nested-movia", String.join("\n", ".macro load 2", "  movia @1, @2", "  ldw @1, 0(@1)", ".endm", ".macro load2 0", "  load r2, value", "  load r3, 0x1234", ".endm", "_start:", "  load2", "  call _start", ".data", "value:", "  .word 9", ""));
        NIOS2_SOURCES.put("macro-call-label", String.join("\n", ".macro go 1", "  call @1", "  br _start", ".endm", "_start:", "  go handler", "handler:", "  ret", ""));
        NIOS2_SOURCES.put("macro-nested-movia-accepted", String.join("\n", ".macro load 2", "  movia @1, @2", "  ldw r4, 0(r5)", ".endm", ".macro load2 0", "  load r2, value", "  load r3, 0x1234", ".endm", "_start:", "  load2", "  call _start", ".data", "value:", "  .word 9", ""));
        NIOS2_SOURCES.put("macro-error-too-few", String.join("\n", ".macro add3 3", "  add @1, @2, @3", ".endm", "_start:", "  add3 r2, r3", ""));
        NIOS2_SOURCES.put("macro-error-recursion", String.join("\n", ".macro loop 0", "  loop", ".endm", "_start:", "  nop", ""));
        NIOS2_SOURCES.put("macro-error-directive-inside", String.join("\n", ".macro bad 0", "  .byte 1", ".endm", "_start:", "  nop", ""));
    }

    // flushp first: Evolution's other-control unit encodes the rA left by its previous instruction.
    static final List<String> NIOS2_STATEMENTS = List.of(
        "flushp", "ldw r2, 4(sp)", "ldh r2, -2(r3)", "ldhu r2, 2(r3)", "ldb r2, -1(r3)", "ldbu r2, 1(r3)", "ldwio r2, 0(r4)", "ldhio r2, 0(r4)",
        "ldhuio r2, 0(r4)", "ldbio r2, 0(r4)", "ldbuio r2, 0(r4)", "stw r2, 32767(r3)", "sth r2, -32768(r3)", "stb r2, 0(r3)",
        "stwio r2, 0(r3)", "sthio r2, 0(r3)", "stbio r2, 0(r3)", "ldw r2, (r3)", "ldw r2, 4(ctl1)",
        "and r2, r3, r4", "or r2, r3, r4", "xor r2, r3, r4", "nor r2, r3, r4", "add r2, r3, r4", "sub r2, r3, r4", "mul r2, r3, r4",
        "div r2, r3, r4", "divu r2, r3, r4", "mulxss r2, r3, r4", "mulxuu r2, r3, r4", "mulxsu r2, r3, r4",
        "andi r2, r3, 0xffff", "ori r2, r3, 1", "xori r2, r3, 2", "andhi r2, r3, 3", "orhi r2, r3, 4", "xorhi r2, r3, 5",
        "addi r2, r3, -32768", "subi r2, r3, 5", "muli r2, r3, -7", "andi r2, r3, -1", "addi r2, r3, 32768",
        "nop", "mov r2, r3", "movhi r2, 0xabcd", "movi r2, -1", "movui r2, 65535", "movia r2, 0x12348765", "movia r2, start",
        "cmpeq r2, r3, r4", "cmpne r2, r3, r4", "cmpge r2, r3, r4", "cmpgeu r2, r3, r4", "cmplt r2, r3, r4", "cmpltu r2, r3, r4",
        "cmpgt r2, r3, r4", "cmpgtu r2, r3, r4", "cmple r2, r3, r4", "cmpleu r2, r3, r4",
        "cmpeqi r2, r3, -5", "cmpnei r2, r3, 5", "cmpgei r2, r3, 5", "cmpgeui r2, r3, 5", "cmplti r2, r3, 5", "cmpltui r2, r3, 5",
        "cmpgti r2, r3, 5", "cmpgtui r2, r3, 5", "cmplei r2, r3, 5", "cmpleui r2, r3, 65535",
        "rol r2, r3, r4", "ror r2, r3, r4", "sll r2, r3, r4", "sra r2, r3, r4", "srl r2, r3, r4",
        "roli r2, r3, 31", "slli r2, r3, 1", "srai r2, r3, 2", "srli r2, r3, 3", "slli r2, r3, 32",
        "callr r5", "ret", "jmp r5", "call start", "jmpi start", "br start", "bge r2, r3, start", "bgeu r2, r3, start",
        "blt r2, r3, start", "bltu r2, r3, start", "beq r2, r3, start", "bne r2, r3, start", "bgt r2, r3, start",
        "bgtu r2, r3, start", "ble r2, r3, start", "bleu r2, r3, start", "br pc+8",
        "trap", "trap 3", "eret", "break", "break 31", "bret", "rdctl r2, ctl3", "wrctl ctl4, r2", "flushd 4(r2)", "flushda -4(r2)",
        "flushi r2", "initd 0(r2)", "initda 8(r2)", "initi r2", "sync", "flushd 0(c1)",
        "custom 5, r2, r3, r4", "custom 255, c1, c2, c3", "custom 256, r2, r3, r4",
        "add at, et, bt", "add gp, sp, fp", "add ea, sstat, ra", "add r1, r0, r31", "ADD r2, r3, r4", "add r2, r3, 0b1");

    /** One statement per case, after a label so branch and jump targets resolve in both assemblers. */
    static final List<String> STATEMENTS = List.of(
        "lui x5, 0xABCDE", "auipc x6, 0x12", "jal x1, start", "jalr x0, 0(x1)", "jalr x0, x1, 0", "jalr x1, x2, 8",
        "beq x1, x2, start", "bne x1, x2, start", "blt x1, x2, start", "bge x1, x2, start", "bltu x1, x2, start", "bgeu x1, x2, start",
        "lb x7, -4(x2)", "lh x7, 2(x2)", "lw x7, 2044(x2)", "lbu x7, 1(x2)", "lhu x7, 2(x2)",
        "sb x7, -1(x2)", "sh x7, 2(x2)", "sw x7, -2048(x2)",
        "addi x8, x9, -1", "slti x8, x9, 5", "sltiu x8, x9, 5", "xori x8, x9, 0x7FF", "ori x8, x9, 1", "andi x8, x9, 255",
        "slli x8, x9, 31", "srli x8, x9, 1", "srai x8, x9, 7",
        "add x10, x11, x12", "sub x10, x11, x12", "sll x10, x11, x12", "slt x10, x11, x12", "sltu x10, x11, x12",
        "xor x10, x11, x12", "srl x10, x11, x12", "sra x10, x11, x12", "or x10, x11, x12", "and x10, x11, x12",
        "mul x10, x11, x12", "mulh x10, x11, x12", "mulhsu x10, x11, x12", "mulhu x10, x11, x12",
        "div x10, x11, x12", "divu x10, x11, x12", "rem x10, x11, x12", "remu x10, x11, x12",
        "fence", "fence.tso", "ecall", "ebreak", "mret",
        "csrrw x5, 0x305, x6", "csrrs x5, 0x300, x0", "csrrc x5, 0x304, x6", "csrrwi x5, 0x305, 3", "csrrsi x5, 0x300, 8", "csrrci x5, 0x304, 1",
        "addi sp, zero, 16", "add a0, a1, a2", "sw ra, 4(sp)", "addi t0, s0, 1", "addi s1, gp, 2", "addi fp, tp, 2", "addi t6, s11, 0",
        "addi x1, x0, -0x10", "addi x1, x0, 0b101", "ADDI X1, X0, 1",
        "nop", "li x5, 42", "li x5, 0x12345", "mv x6, x5", "not x7, x6", "neg x7, x6", "j start", "jr x1", "ret",
        "beqz x1, start", "bnez x1, start", "seqz x1, x2", "snez x1, x2", "call start", "la x5, start");

    static void assembler() throws Exception {
        assembler(true, STATEMENTS, SOURCES);
        assembler(false, NIOS2_STATEMENTS, NIOS2_SOURCES);
    }

    static void assembler(boolean rv32, List<String> statements, Map<String, String> sources) throws Exception {
        String arch = rv32 ? "RV32" : "Nios II";
        String prefix = rv32 ? "asm " : "nios2 asm ";
        String filler = rv32 ? "addi x0, x0, 0" : "nop";
        log("\n=== " + arch + " assembler, one statement per case");
        int same = 0, evolutionOnly = 0, mobileOnly = 0, bothReject = 0, different = 0;
        List<String> evolutionOnlyCases = new ArrayList<>(), mobileOnlyCases = new ArrayList<>(), differentCases = new ArrayList<>();
        for (String statement : statements) {
            String source = "start:\n  " + filler + "\n  " + statement + "\n";
            StringBuilder ee = new StringBuilder(), me = new StringBuilder();
            Map<Long, Integer> evolution, mobile;
            try { evolution = evolutionAssemble(source, ee, rv32); } catch (Exception failure) { evolution = null; ee.append(failure); }
            try { mobile = mobileAssemble(source, 0, me, rv32); } catch (Exception failure) { mobile = null; me.append(failure); }
            String e = words(evolution), m = words(mobile);
            String verdict;
            if (evolution == null && mobile == null) { bothReject++; verdict = "both reject"; }
            else if (evolution == null) { mobileOnly++; mobileOnlyCases.add(statement); verdict = "Mobile only"; }
            else if (mobile == null) { evolutionOnly++; evolutionOnlyCases.add(statement); verdict = "Evolution only"; }
            else if (e.equals(m)) { same++; verdict = "same"; }
            else { different++; differentCases.add(statement + " (" + e + " vs " + m + ")"); verdict = "DIFFERENT"; }
            log(String.format("  %-26s %-14s evolution %s%s | mobile %s%s", statement, verdict, e, ee.length() > 0 ? " [" + ee + "]" : "",
                m, me.length() > 0 ? " [" + me + "]" : ""));
        }
        result(prefix + "statements encoded identically", different == 0, same + " same, " + different + " different " + differentCases);
        result(prefix + "statements accepted by both", evolutionOnly == 0 && mobileOnly == 0, bothReject + " rejected by both; Evolution only "
            + evolutionOnlyCases + "; Mobile only " + mobileOnlyCases);
        log("\n=== " + arch + " assembler: Evolution soc.util.Assembler (headless RSyntaxTextArea) vs Mobile, whole sources");
        for (var entry : sources.entrySet()) {
            StringBuilder evolutionErrors = new StringBuilder(), mobileErrors = new StringBuilder();
            Map<Long, Integer> evolution, mobile;
            try { evolution = evolutionAssemble(entry.getValue(), evolutionErrors, rv32); }
            catch (Exception failure) { evolution = null; evolutionErrors.append("exception ").append(failure); }
            try { mobile = mobileAssemble(entry.getValue(), 0, mobileErrors, rv32); }
            catch (Exception failure) { mobile = null; mobileErrors.append("exception ").append(failure); }
            String e = words(evolution), m = words(mobile);
            log("source " + entry.getKey() + "\n  evolution " + e + (evolutionErrors.length() > 0 ? " [" + evolutionErrors + "]" : "")
                + "\n  mobile    " + m + (mobileErrors.length() > 0 ? " [" + mobileErrors + "]" : ""));
            boolean equal = e.equals(m);
            String errorCheck = "";
            if (equal && evolution == null && entry.getKey().startsWith("macro")) {
                errorCheck = sameError(evolutionErrors.toString(), mobileErrors.toString());
                if (errorCheck.startsWith("DIFFERENT")) equal = false;
                errorCheck = " (" + errorCheck + ")";
            }
            result(prefix + entry.getKey(), equal, equal ? (evolution == null ? "both reject" + errorCheck : "same words: " + e) : !errorCheck.isEmpty()
                ? "error" + errorCheck + " evolution [" + evolutionErrors + "] mobile [" + mobileErrors + "]"
                : "evolution " + e + (evolutionErrors.length() > 0 ? " [" + evolutionErrors + "]" : "") + " | mobile " + m
                    + (mobileErrors.length() > 0 ? " [" + mobileErrors + "]" : ""));
        }
    }

    /** Evolution's English messages that each Mobile macro-error key stands for. */
    static final Map<String, List<String>> MACRO_ERRORS = Map.of(
        "soc_asm_macro_definition", List.of("Expected a name of the macro", "Expected the number of macro parameters",
            "Could not find the end of the macro definition"),
        "soc_asm_macro_directive", List.of("This construct cannot be used inside a macro definition"),
        "soc_asm_macro_parameter", List.of("This macro parameter is not defined, check the number of parameters in your macro definition"),
        "soc_asm_macro_parameters", List.of("Incorrect number of macro parameters specified"),
        "soc_asm_macro_recursion", List.of("Macro is calling itsELF causing a deadlock situation",
            "Macros are calling each other causing a deadlock situation"),
        "soc_asm_target", List.of("This label has not been defined, hence I cannot use it", "Could not determine an address for this label",
            "Could not find a definition of this parameter"));

    /**
     * Evolution marks every error it finds; Mobile stops at its first. The Mobile error must sit on a line
     * Evolution marks and, for the macro keys, carry one of the messages Evolution shows on that line.
     * An Evolution exception (it throws on some inputs) only requires Mobile to reject.
     */
    static String sameError(String evolution, String mobile) {
        if (evolution.startsWith("exception")) return "Evolution throws " + evolution.substring(10).split("\n")[0] + "; Mobile " + mobile;
        var match = java.util.regex.Pattern.compile("line (\\d+): (\\S+)").matcher(mobile);
        if (!match.find()) return "DIFFERENT: no Mobile line";
        String line = match.group(1), key = match.group(2);
        List<String> messages = new ArrayList<>();
        for (String part : evolution.split("; ")) if (part.startsWith("line " + line + ": ")) messages.add(part.substring(part.indexOf(": ") + 2));
        if (messages.isEmpty()) return "DIFFERENT: Evolution marks no error on line " + line;
        var expected = MACRO_ERRORS.get(key);
        if (expected != null && messages.stream().noneMatch(expected::contains))
            return "DIFFERENT: line " + line + " " + key + " vs " + messages;
        return "same line " + line + ": " + key + " / " + messages;
    }

    /** Evolution's getAsmInstruction() for many RV32 words vs Mobile's Rv32Disassembler. */
    static void disassembly() throws Exception {
        log("\n=== RV32 trace text for decoded words: Evolution getAsmInstruction() vs Mobile Rv32Disassembler");
        Object disassembler = Class.forName("dev.logisim.mobile.core.Rv32Disassembler").getField("INSTANCE").get(null);
        Method format = null;
        for (Method method : disassembler.getClass().getMethods()) if (method.getName().startsWith("format")) format = method;
        int[] opcodes = {0x37, 0x17, 0x6F, 0x67, 0x63, 0x03, 0x23, 0x13, 0x33, 0x0F, 0x73};
        var random = new java.util.Random(353);
        List<Integer> words = new ArrayList<>();
        for (int opcode : opcodes) for (int i = 0; i < 4000; i++) {
            int word = (random.nextInt() & ~0x7F) | opcode;
            // Bias funct7, rs1, rs2 and immediates toward the pseudo-instruction cases.
            switch (random.nextInt(6)) {
                case 0 -> word &= 0x01FFFFFF;                 // funct7 = 0
                case 1 -> word = (word & 0x01FFFFFF) | 0x40000000; // funct7 = 0x20
                case 2 -> word &= ~(0x1F << 15);              // rs1 = 0
                case 3 -> word &= ~(0x1F << 20);              // rs2 / low immediate = 0
                case 4 -> word = (word & 0x000FFFFF) | (random.nextBoolean() ? 0xFFF00000 : 0x00100000); // imm -1 or 1
                default -> { }
            }
            words.add(word);
        }
        for (int i = 0; i < 4000; i++) words.add(random.nextInt());
        for (int funct12 : new int[] {0, 1, 0x302, 0x305, 0x300, 0x7A2, 0x123})
            for (int funct3 = 0; funct3 < 8; funct3++) words.add((funct12 << 20) | (5 << 15) | (funct3 << 12) | (6 << 7) | 0x73);
        int same = 0, different = 0;
        List<String> examples = new ArrayList<>();
        for (int word : words) {
            RV32imState.ASSEMBLER.decode(word);
            var unit = RV32imState.ASSEMBLER.getExeUnit();
            String evolution = unit == null ? null : unit.getAsmInstruction();
            String mobile = (String) format.invoke(disassembler, word);
            if (java.util.Objects.equals(evolution, mobile)) same++;
            else {
                different++;
                if (examples.size() < 12) examples.add(hex(word) + " '" + evolution + "' vs '" + mobile + "'");
            }
        }
        log(same + " same, " + different + " different " + examples);
        result("rv32 trace text for " + words.size() + " decoded words", different == 0, same + " same byte for byte, " + different + " different " + examples);
    }

    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(120_000); } catch (InterruptedException ignored) { return; }
            System.err.println("SoC debugger oracle exceeded its 120-second deadline.");
            Runtime.getRuntime().halt(124);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        File repo = new File(args[0]);
        File out = new File(args[1]);
        out.mkdirs();
        int exit = 0;
        for (String architecture : List.of("rv32", "nios2")) {
            try { elfTrace(repo, architecture); } catch (Throwable failure) { failure.printStackTrace(); result(architecture + " elf-trace", false, "oracle failure " + failure); exit = 1; }
            try { breakpoints(repo, architecture); } catch (Throwable failure) { failure.printStackTrace(); result(architecture + " breakpoint-run", false, "oracle failure " + failure); exit = 1; }
        }
        try { assembler(); } catch (Throwable failure) { failure.printStackTrace(); result("assembler", false, "oracle failure " + failure); exit = 1; }
        try { disassembly(); } catch (Throwable failure) { failure.printStackTrace(); result("rv32 disassembly", false, "oracle failure " + failure); exit = 1; }
        Files.writeString(new File(out, "soc-debugger.txt").toPath(), LOG.toString());
        Files.writeString(new File(out, "soc-debugger-results.json").toPath(), "{\"comparisons\": [\n  " + String.join(",\n  ", RESULTS) + "\n]}\n");
        long same = RESULTS.stream().filter(r -> r.contains("\"SAME\"")).count();
        System.out.println("SoC debugger: " + same + " of " + RESULTS.size() + " comparisons equal");
        watchdog.interrupt();
        System.exit(exit);
    }
}
