import com.cburch.draw.actions.ModelDeleteHandleAction;
import com.cburch.draw.actions.ModelInsertHandleAction;
import com.cburch.draw.actions.ModelReorderAction;
import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.model.Handle;
import com.cburch.draw.shapes.Poly;
import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.file.Options;
import com.cburch.logisim.gui.appear.RevertAppearanceAction;
import com.cburch.logisim.gui.main.Selection;
import com.cburch.logisim.gui.main.SelectionActions;
import com.cburch.logisim.gui.menu.ProjectCircuitActions;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.vhdl.base.VhdlContent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.std.wiring.Pin;

/**
 * Drives Evolution's menu actions on the fixtures without a window and writes the resulting documents.
 *
 * <p>{@code evolution FIXTURES OUT} writes {@code OUT/<case>.circ} (and {@code .json} for state cases).
 * {@code canon IN OUT} reopens each Mobile document of {@code IN} with Evolution's loader and writes it back
 * with Evolution's writer, so both sides are compared after the same normalization.
 */
public final class MenuActionsOracle {
    interface DocCase { void apply(Project proj, LogisimFile file, Path fixtures) throws Exception; }

    private static final Map<String, String> CASE_FIXTURE = new LinkedHashMap<>();
    private static final Map<String, DocCase> DOC_CASES = new LinkedHashMap<>();
    private static final Map<String, String> STATE = new TreeMap<>();

    static void doc(String id, String fixture, DocCase action) {
        CASE_FIXTURE.put(id, fixture);
        DOC_CASES.put(id, action);
    }

    static Circuit circuit(LogisimFile file, String name) {
        var circuit = file.getCircuit(name);
        if (circuit == null) throw new AssertionError("missing circuit " + name);
        return circuit;
    }

    static Component at(Circuit circuit, int x, int y, String factory) {
        for (var comp : circuit.getNonWires()) {
            if (comp.getLocation().equals(Location.create(x, y, false)) && comp.getFactory().getName().equals(factory)) return comp;
        }
        throw new AssertionError("missing " + factory + " at " + x + "," + y + " in " + circuit.getName());
    }

    static Component wire(Circuit circuit, int x1, int y1, int x2, int y2) {
        for (var w : circuit.getWires()) {
            if (w.getEnd0().equals(Location.create(x1, y1, false)) && w.getEnd1().equals(Location.create(x2, y2, false))) return w;
        }
        throw new AssertionError("missing wire");
    }

    static CanvasObject shape(Circuit circuit, String tag) {
        for (var obj : circuit.getAppearance().getObjectsFromBottom()) {
            if (obj.getClass().getSimpleName().equalsIgnoreCase(tag)) return obj;
        }
        throw new AssertionError("missing appearance " + tag);
    }

    static void act(Project proj, Action action) { proj.doAction(action); }

    static Selection select(Project proj, Circuit circuit, Collection<Component> comps) {
        proj.setCurrentCircuit(circuit);
        // The real editor canvas, without a frame: its selection is the one the Edit menu acts on.
        var sel = new com.cburch.logisim.gui.main.Canvas(proj).getSelection();
        sel.addAll(comps);
        return sel;
    }

    static {
        doc("file-open", "project.circ", (p, f, d) -> { });
        doc("file-save", "project.circ", (p, f, d) -> { });
        doc("file-saveAs", "project.circ", (p, f, d) -> { });
        doc("project-addCircuit", "project.circ", (p, f, d) ->
            act(p, LogisimFileActions.addCircuit(new Circuit("added", f, p))));
        doc("project-moveUp", "project.circ", (p, f, d) -> ProjectCircuitActions.doMoveCircuit(p, circuit(f, "spare"), -1));
        doc("project-moveDown", "project.circ", (p, f, d) -> ProjectCircuitActions.doMoveCircuit(p, circuit(f, "main"), 1));
        doc("project-remove", "project.circ", (p, f, d) -> {
            var spare = circuit(f, "spare");
            if (!p.getDependencies().canRemove(spare)) throw new AssertionError("spare in use");
            act(p, LogisimFileActions.removeCircuit(spare));
        });
        doc("project-setAsMain", "project.circ", (p, f, d) -> ProjectCircuitActions.doSetAsMainCircuit(p, circuit(f, "half")));
        doc("project-revertAppearance", "project.circ", (p, f, d) -> act(p, new RevertAppearanceAction(circuit(f, "half"))));
        doc("edit-raise", "project.circ", (p, f, d) -> {
            var half = circuit(f, "half");
            ModelReorderAction.createRaise(half.getAppearance(), List.of(shape(half, "Poly"))).doIt();
        });
        doc("edit-lower", "project.circ", (p, f, d) -> {
            var half = circuit(f, "half");
            ModelReorderAction.createLower(half.getAppearance(), List.of(shape(half, "Oval"))).doIt();
        });
        doc("edit-raiseTop", "project.circ", (p, f, d) -> {
            var half = circuit(f, "half");
            ModelReorderAction.createRaiseTop(half.getAppearance(), List.of(shape(half, "Rectangle"))).doIt();
        });
        doc("edit-lowerBottom", "project.circ", (p, f, d) -> {
            var half = circuit(f, "half");
            ModelReorderAction.createLowerBottom(half.getAppearance(), List.of(shape(half, "Text"))).doIt();
        });
        doc("edit-addCtrl", "project.circ", (p, f, d) -> {
            var poly = (Poly) shape(circuit(f, "half"), "Poly");
            Handle handle = poly.canInsertHandle(Location.create(55, 70, false));
            if (handle == null) throw new AssertionError("no handle on the closing edge");
            new ModelInsertHandleAction(circuit(f, "half").getAppearance(), handle).doIt();
        });
        doc("edit-remCtrl", "project.circ", (p, f, d) -> {
            var poly = (Poly) shape(circuit(f, "half"), "Poly");
            Handle handle = poly.canDeleteHandle(Location.create(85, 85, false));
            if (handle == null) throw new AssertionError("no deletable handle");
            new ModelDeleteHandleAction(circuit(f, "half").getAppearance(), handle).doIt();
        });
        doc("edit-cut", "project.circ", (p, f, d) -> {
            var main = circuit(f, "main");
            var sel = select(p, main, List.of(at(main, 250, 300, "AND Gate"), at(main, 400, 300, "NOT Gate"), wire(main, 250, 300, 370, 300)));
            act(p, SelectionActions.cut(sel));
        });
        doc("edit-dup", "project.circ", (p, f, d) -> {
            // Same code path as Duplicate with the pointer outside the canvas: copyComponents(comps, false).
            var main = circuit(f, "main");
            List<Component> comps = List.of(at(main, 400, 300, "NOT Gate"));
            var sel = select(p, main, comps);
            Method copy = sel.getClass().getSuperclass().getDeclaredMethod("copyComponents", Collection.class, boolean.class);
            copy.setAccessible(true);
            @SuppressWarnings("unchecked")
            var copies = (HashMap<Component, Component>) copy.invoke(sel, comps, false);
            var xn = new CircuitMutation(main);
            xn.addAll(copies.values());
            act(p, xn.toAction(null));
            var placed = copies.values().iterator().next().getLocation();
            STATE.put("edit-dup.offset", (placed.getX() - 400) + "," + (placed.getY() - 300));
        });
        doc("edit-undoHistory", "project.circ", (p, f, d) -> {
            act(p, LogisimFileActions.addCircuit(new Circuit("added", f, p)));
            act(p, LogisimFileActions.setMainCircuit(circuit(f, "half")));
            ProjectCircuitActions.doMoveCircuit(p, circuit(f, "spare"), -1);
            var undo = p.getUndoActions();
            STATE.put("edit-undoHistory.entries", Integer.toString(undo.size()));
            p.undoUpTo(undo.get(1));
            STATE.put("edit-undoHistory.after", p.getUndoActions().size() + "/" + p.getRedoActions().size());
        });
        doc("edit-redoHistory", "project.circ", (p, f, d) -> {
            act(p, LogisimFileActions.addCircuit(new Circuit("added", f, p)));
            act(p, LogisimFileActions.setMainCircuit(circuit(f, "half")));
            ProjectCircuitActions.doMoveCircuit(p, circuit(f, "spare"), -1);
            p.undoUpTo(p.getUndoActions().get(2));
            var redo = p.getRedoActions();
            STATE.put("edit-redoHistory.entries", Integer.toString(redo.size()));
            p.redoUpTo(redo.get(1));
            STATE.put("edit-redoHistory.after", p.getUndoActions().size() + "/" + p.getRedoActions().size());
        });
        doc("edit-clearHistory", "project.circ", (p, f, d) -> {
            act(p, LogisimFileActions.addCircuit(new Circuit("added", f, p)));
            p.undoAction();
            act(p, LogisimFileActions.setMainCircuit(circuit(f, "half")));
            p.discardAllEdits();
            STATE.put("edit-clearHistory.after", p.getUndoActions().size() + "/" + p.getRedoActions().size());
        });
        doc("project-unload", "project.circ", (p, f, d) -> act(p, LogisimFileActions.unloadLibrary(f.getLibrary("Memory"))));
        doc("project-loadBuiltin", "project.circ", (p, f, d) -> {
            act(p, LogisimFileActions.unloadLibrary(f.getLibrary("Memory")));
            act(p, LogisimFileActions.loadLibrary(f.getLoader().getBuiltin().getLibrary("Memory"), f));
        });
        doc("project-loadLogisim", "project.circ", (p, f, d) -> {
            var lib = f.getLoader().loadLogisimLibrary(d.resolve("library.circ").toFile());
            act(p, LogisimFileActions.loadLibrary(lib, f));
        });
        doc("file-merge", "project.circ", (p, f, d) -> {
            var mergelib = new Loader(null).openLogisimFile(d.resolve("merge.circ").toFile());
            act(p, LogisimFileActions.mergeFile(mergelib, f, List.of(mergelib.getCircuit("top")), true));
        });
        doc("project-options", "project.circ", (p, f, d) -> {
            var attrs = f.getOptions().getAttributeSet();
            // OptionsActions is package-private; the Simulation tab calls setAttribute for each field.
            var setAttribute = Class.forName("com.cburch.logisim.gui.opts.OptionsActions")
                .getDeclaredMethod("setAttribute", com.cburch.logisim.data.AttributeSet.class, com.cburch.logisim.data.Attribute.class, Object.class);
            setAttribute.setAccessible(true);
            act(p, (Action) setAttribute.invoke(null, attrs, Options.ATTR_SIM_LIMIT, 500));
            act(p, (Action) setAttribute.invoke(null, attrs, Options.ATTR_GATE_UNDEFINED, Options.GATE_UNDEFINED_ERROR));
        });
        doc("project-addVhdl", "project.circ", (p, f, d) -> {
            var content = VhdlContent.create("my_entity", f);
            Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "evolution-vhdl-template.vhd"), content.getContent());
            act(p, LogisimFileActions.addVhdl(content));
        });
        doc("project-importVhdl", "project.circ", (p, f, d) -> {
            var content = VhdlContent.parse(null, Files.readString(d.resolve("counter.vhd")), f);
            act(p, LogisimFileActions.addVhdl(content));
        });
    }


    // ---- Simulate menu: the same Simulator calls MenuSimulate makes, observed after each completed propagation.
    static final class Waiter implements Simulator.Listener {
        final Semaphore completed = new Semaphore(0);
        volatile int ticks;
        @Override public void propagationCompleted(Simulator.Event e) { if (e.didTick()) ticks++; completed.release(); }
        @Override public void simulatorReset(Simulator.Event e) { }
        @Override public void simulatorStateChanged(Simulator.Event e) { }
        void await(int count) throws InterruptedException {
            if (!completed.tryAcquire(count, 10, TimeUnit.SECONDS)) throw new AssertionError("simulator did not complete");
            Thread.sleep(30);
            completed.drainPermits();
        }
    }

    static String bit(Value v) {
        if (v == Value.TRUE) return "1";
        if (v == Value.FALSE) return "0";
        if (v == Value.UNKNOWN || v.isUnknown()) return "x";
        return v.isErrorValue() ? "E" : v.toString();
    }

    static String obs(CircuitState root, Circuit main) {
        var stage = main.getNonWires().stream().filter(c -> c.getFactory() instanceof SubcircuitFactory).findFirst().orElseThrow();
        var inner = ((SubcircuitFactory) stage.getFactory()).getSubstate(root, stage);
        return "clk=" + bit(root.getValue(Location.create(100, 450, false)))
            + " q0=" + bit(root.getValue(Location.create(350, 410, false)))
            + " nq0=" + bit(root.getValue(Location.create(350, 450, false)))
            + " q1=" + bit(inner.getValue(Location.create(350, 410, false)));
    }

    static void simulationCases(Path fixtures, Path out) throws Exception {
        record Run(Project proj, Simulator sim, Waiter waiter, Circuit main) {
            String obs() { return MenuActionsOracle.obs(proj.getCircuitState(), main); }
        }
        java.util.function.Function<String, Run> open = name -> {
            try {
                var file = new Loader(null).openLogisimFile(fixtures.resolve(name).toFile());
                var proj = new Project(file);
                for (var circ : file.getCircuits()) circ.setProject(proj);
                var waiter = new Waiter();
                proj.getSimulator().addSimulatorListener(waiter);
                proj.setCurrentCircuit(file.getMainCircuit());
                Thread.sleep(200);
                waiter.completed.drainPermits();
                // Start from Simulate > Reset Simulation, the state a freshly opened window shows.
                proj.getSimulator().reset();
                waiter.await(1);
                return new Run(proj, proj.getSimulator(), waiter, file.getMainCircuit());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        var r = open.apply("sim.circ");
        var trace = new ArrayList<String>();
        trace.add(r.obs());
        for (int i = 0; i < 4; i++) { r.sim().tick(1); r.waiter().await(1); trace.add(r.obs()); }
        STATE.put("sim-tickHalf", String.join(" | ", trace));
        trace.clear();
        for (int i = 0; i < 3; i++) { r.sim().tick(2); r.waiter().await(2); trace.add(r.obs()); }
        STATE.put("sim-tickFull", String.join(" | ", trace));
        r.sim().reset(); r.waiter().await(1);
        STATE.put("sim-reset", r.obs());
        // Simulate > Go Down State / Go Up State: the stage instance's own CircuitState, then the root again.
        for (int i = 0; i < 3; i++) { r.sim().tick(2); r.waiter().await(2); }
        var stage = r.main().getNonWires().stream().filter(c -> c.getFactory() instanceof SubcircuitFactory).findFirst().orElseThrow();
        var inner = ((SubcircuitFactory) stage.getFactory()).getSubstate(r.proj().getCircuitState(), stage);
        r.proj().setCircuitState(inner);
        var down = r.proj().getCircuitState();
        STATE.put("sim-downState", down.getCircuit().getName() + " clk=" + bit(down.getValue(Location.create(100, 450, false)))
            + " q=" + bit(down.getValue(Location.create(460, 410, false))));
        r.proj().setCircuitState(down.getParentState());
        STATE.put("sim-upState", r.proj().getCircuitState().getCircuit().getName() + " " + r.obs());
        r.sim().shutDown();

        // Tick Enabled at 64 Hz: count the automatic ticks until four have happened, then disable.
        int ticks = 0;
        for (int attempt = 0; attempt < 5 && ticks != 4; attempt++) {
            r = open.apply("sim.circ");
            r.sim().setTickFrequency(64.0);
            r.waiter().ticks = 0;
            r.sim().setAutoTicking(true);
            var deadline = System.nanoTime() + 10_000_000_000L;
            while (r.waiter().ticks < 4 && System.nanoTime() < deadline) Thread.onSpinWait();
            r.sim().setAutoTicking(false);
            Thread.sleep(100);
            ticks = r.waiter().ticks;
            if (ticks != 4) r.sim().shutDown();
        }
        // Rebuild the same count by hand so the state is comparable: auto-ticks are tick(1) each.
        STATE.put("sim-ticksEnabled", "ticks=" + ticks + " " + r.obs());
        STATE.put("sim-ticksEnabled.period_ms", Double.toString(1000.0 / r.sim().getTickFrequency()));
        r.sim().shutDown();

        // Simulate > Tick Frequency: the choice list and the value saved with the document.
        STATE.put("sim-tickFreq.choices", java.util.Arrays.toString(com.cburch.logisim.gui.menu.MenuSimulate.SUPPORTED_TICK_FREQUENCIES));
        r = open.apply("sim.circ");
        r.sim().setTickFrequency(8.0);
        Thread.sleep(50);
        Files.writeString(out.resolve("sim-tickFreq.circ"), write(r.proj().getLogisimFile(), fixtures.resolve("sim.circ").toFile()));
        r.sim().shutDown();


        // Simulate > Logging: the Model the Logging window builds for the root state, fed by the same
        // propagationCompleted calls its simulator listener makes, over four half ticks.
        r = open.apply("sim.circ");
        var log = new com.cburch.logisim.gui.log.Model(r.proj().getCircuitState());
        for (int i = 0; i < 4; i++) { r.sim().tick(1); r.waiter().await(1); log.propagationCompleted(true, false, true); }
        var names = new ArrayList<String>();
        for (var sig : log.getSignals()) {
            var values = new ArrayList<String>();
            var it = sig.new Iterator();
            do {
                var v = it.getFormattedValue();
                if (values.isEmpty() || !values.get(values.size() - 1).equals(v)) values.add(v);
            } while (it.advance());
            names.add(sig.getName());
            STATE.put("sim-log." + sig.getName(), String.join(",", values));
        }
        STATE.put("sim-log.defaultSignals", String.join(",", names));
        r.sim().shutDown();

        // Simulate > Step: propagation off, pin A driven high as the Poke tool does, then one step per command.
        var s = open.apply("step.circ");
        s.sim().setAutoPropagation(false);
        var root = s.proj().getCircuitState();
        var pinA = at(s.main(), 100, 100, "Pin");
        Pin.FACTORY.driveInputPin(root.getInstanceState(pinA), Value.TRUE);
        root.markComponentAsDirty(pinA);
        trace.clear();
        for (int i = 0; i < 5; i++) {
            s.sim().step();
            s.waiter().await(1);
            trace.add("a=" + bit(root.getValue(Location.create(100, 100, false))) + " n1=" + bit(root.getValue(Location.create(160, 100, false)))
                + " n2=" + bit(root.getValue(Location.create(220, 100, false))) + " n3=" + bit(root.getValue(Location.create(280, 100, false))));
        }
        STATE.put("sim-step", String.join(" | ", trace));
        s.sim().shutDown();
    }

    /** File > Open and File > New give the document a Project and a simulator before anything is saved. */
    static Project opened(LogisimFile file) {
        var proj = new Project(file);
        for (var circ : file.getCircuits()) circ.setProject(proj);
        return proj;
    }


    // ---- Project > Analyze Circuit, Get Circuit Statistics, Simulate > Test Vector, Edit Circuit Appearance.
    static void analysisCases(Path fixtures, Path out) throws Exception {
        var file = new Loader(null).openLogisimFile(fixtures.resolve("project.circ").toFile());
        var proj = opened(file);
        var half = circuit(file, "half");
        // ProjectCircuitActions.doAnalyze + configureAnalyzer, without the Analyzer frame.
        var pinNames = com.cburch.logisim.circuit.Analyze.getPinLabels(half);
        var inputs = new ArrayList<com.cburch.logisim.analyze.model.Var>();
        var outputs = new ArrayList<com.cburch.logisim.analyze.model.Var>();
        for (var entry : pinNames.entrySet()) {
            var width = entry.getKey().getAttributeValue(com.cburch.logisim.instance.StdAttr.WIDTH).getWidth();
            var v = new com.cburch.logisim.analyze.model.Var(entry.getValue(), width);
            if (Pin.FACTORY.isInputPin(entry.getKey())) inputs.add(v); else outputs.add(v);
        }
        var model = new com.cburch.logisim.analyze.model.AnalyzerModel();
        model.setCurrentCircuit(proj, half);
        model.setVariables(inputs, outputs);
        // configureAnalyzer tries the expressions first and keeps the simulated table as its backup.
        com.cburch.logisim.circuit.Analyze.computeExpression(model, half, pinNames);
        com.cburch.logisim.circuit.Analyze.computeTable(model, proj, half, pinNames);
        var table = model.getTruthTable();
        var rows = new ArrayList<String>();
        for (int row = 0; row < table.getRowCount(); row++) {
            var line = new StringBuilder();
            for (int col = 0; col < table.getInputColumnCount(); col++) line.append(table.getInputEntry(row, col).getDescription());
            line.append("->");
            for (int col = 0; col < table.getOutputColumnCount(); col++) line.append(table.getOutputEntry(row, col).getDescription());
            rows.add(line.toString());
        }
        STATE.put("analyze.vars", inputs.stream().map(Object::toString).toList() + "->" + outputs.stream().map(Object::toString).toList());
        STATE.put("analyze.table", String.join(" ", rows));
        for (var output : outputs) {
            STATE.put("analyze.minimal." + output.name, model.getOutputExpressions().getMinimalExpression(output.name).toString());
        }

        // StatisticsDialog.show(frame, file, circuit) lists FileStatistics.compute(file, circuit).
        var stats = com.cburch.logisim.file.FileStatistics.compute(file, circuit(file, "main"));
        var counts = new ArrayList<String>();
        for (var c : stats.getCounts()) {
            counts.add(c.getFactory().getName() + "@" + (c.getLibrary() == null ? "-" : c.getLibrary().getName())
                + "=" + c.getSimpleCount() + "/" + c.getUniqueCount() + "/" + c.getRecursiveCount());
        }
        java.util.Collections.sort(counts);
        STATE.put("stats.rows", String.join(" ", counts));
        STATE.put("stats.totals", stats.getTotalWithoutSubcircuits().getSimpleCount() + "/" + stats.getTotalWithoutSubcircuits().getUniqueCount()
            + "/" + stats.getTotalWithoutSubcircuits().getRecursiveCount() + " " + stats.getTotalWithSubcircuits().getSimpleCount()
            + "/" + stats.getTotalWithSubcircuits().getUniqueCount() + "/" + stats.getTotalWithSubcircuits().getRecursiveCount());

        // Simulate > Test Vector runs TestThread, which wraps the same TestVectorEvaluator.
        var vector = new com.cburch.logisim.data.TestVector(fixtures.resolve("half.txt").toFile());
        var state = CircuitState.createRootState(proj, half, Thread.currentThread());
        var results = new ArrayList<String>();
        var passFail = new com.cburch.logisim.circuit.TestVectorEvaluator(state, vector).evaluate((row, report) ->
            results.add((row + 1) + ":" + (report == null || report.isEmpty() ? "pass" : "fail")));
        STATE.put("test.rows", String.join(" ", results));
        STATE.put("test.totals", passFail[0] + "/" + passFail[1]);
        proj.getSimulator().shutDown();

        // Project > Edit Circuit Appearance: the editor starts from the default drawing; its first edit keeps it as custom.
        var appearance = new Loader(null).openLogisimFile(fixtures.resolve("appearance.circ").toFile());
        var appearanceProject = opened(appearance);
        for (var circ : appearance.getCircuits()) {
            var objects = new ArrayList<CanvasObject>();
            for (var obj : circ.getAppearance().getObjectsFromBottom()) objects.add(obj.clone());
            circ.getStaticAttributes().setValue(com.cburch.logisim.circuit.CircuitAttributes.APPEARANCE_ATTR,
                com.cburch.logisim.circuit.CircuitAttributes.APPEAR_CUSTOM);
            circ.getAppearance().setObjectsForce(objects);
        }
        Files.writeString(out.resolve("project-appearance.circ"), write(appearance, fixtures.resolve("appearance.circ").toFile()));
        appearanceProject.getSimulator().shutDown();
    }


    // ---- File > Open Recent: the list after opening r01..r12 and then r03 again (ProjectActions.doOpen calls updateRecentFile).
    static void recentCase(Path out) throws Exception {
        var dir = Files.createTempDirectory("menus-recent");
        for (var name : recentSequence()) {
            var file = dir.resolve(name + ".circ");
            if (!Files.exists(file)) Files.writeString(file, "");
            AppPreferences.updateRecentFile(file.toFile());
            Thread.sleep(3);
        }
        STATE.put("file-openRecent", String.join(" ", AppPreferences.getRecentFiles().stream()
            .map(f -> f.getName().replace(".circ", "")).toList()));
    }

    static List<String> recentSequence() {
        var names = new ArrayList<String>();
        for (int i = 1; i <= 12; i++) names.add(String.format("r%02d", i));
        names.add("r03");
        return names;
    }


    // ---- File > Open Project Bundle (extract and run): unzip the bundle next to its main file and open it.
    static void bundleCase(Path fixtures) throws Exception {
        var bundle = fixtures.getParent().getParent().getParent().getParent().resolve("fixtures/portable-projects/portable53.lsebdl");
        var dir = Files.createTempDirectory("menus-bundle");
        String main;
        try (var zip = new java.util.zip.ZipFile(bundle.toFile())) {
            main = com.cburch.logisim.proj.ProjectBundleManifest.getManifestInfo(zip, null).getMainLogisimFilename();
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                var target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir)) throw new AssertionError("entry outside bundle");
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                try (var in = zip.getInputStream(entry)) { Files.copy(in, target); }
            }
        }
        var file = new Loader(null).openLogisimFile(dir.resolve(main).toFile());
        var proj = opened(file);
        var state = CircuitState.createRootState(proj, file.getMainCircuit(), Thread.currentThread());
        state.getPropagator().propagate();
        var outputs = new ArrayList<String>();
        for (var comp : file.getMainCircuit().getNonWires()) {
            if (comp.getFactory() instanceof Pin && !Pin.FACTORY.isInputPin((com.cburch.logisim.instance.Instance) com.cburch.logisim.instance.Instance.getInstanceFor(comp))) {
                outputs.add(comp.getAttributeSet().getValue(com.cburch.logisim.instance.StdAttr.LABEL) + "="
                    + Pin.FACTORY.getValue(state.getInstanceState(comp)).toBinaryString());
            }
        }
        java.util.Collections.sort(outputs);
        var circuits = new java.util.TreeSet<String>();
        var pending = new java.util.ArrayDeque<LogisimFile>(List.of(file));
        while (!pending.isEmpty()) {
            var f = pending.pop();
            for (var circ : f.getCircuits()) circuits.add(circ.getName());
            for (var lib : f.getLibraries()) if (lib instanceof com.cburch.logisim.file.LoadedLibrary loaded
                    && loaded.getBase() instanceof LogisimFile child) pending.push(child);
        }
        STATE.put("file-extractRunProj", "main=" + file.getMainCircuit().getName() + " circuits=" + circuits + " " + String.join(" ", outputs));
        proj.getSimulator().shutDown();
    }

    static String write(LogisimFile file, File dest) {
        var bytes = new ByteArrayOutputStream();
        file.write(bytes, file.getLoader(), dest);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(1);
        });
        Main.headless = true;
        var mode = args[0];
        if (mode.equals("canon")) {
            var in = Path.of(args[1]);
            var out = Files.createDirectories(Path.of(args[2]));
            var fixtures = Path.of(args[3]);
            try (var files = Files.list(in)) {
                for (var source : files.filter(path -> path.toString().endsWith(".circ")).sorted().toList()) {
                    // Reopen next to the fixtures so relative library paths resolve the same way.
                    var staged = fixtures.resolve(".canon-" + source.getFileName());
                    Files.copy(source, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    try {
                        var file = new Loader(null).openLogisimFile(staged.toFile());
                        opened(file);
                        Files.writeString(out.resolve(source.getFileName()), write(file, fixtures.resolve(source.getFileName()).toFile()));
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                }
            }
            System.exit(0);
        }
        var fixtures = Path.of(args[1]);
        var out = Files.createDirectories(Path.of(args[2]));
        var only = args.length > 3 ? List.of(args[3].split(",")) : null;
        for (var entry : DOC_CASES.entrySet()) {
            if (only != null && !only.contains(entry.getKey())) continue;
            var fixture = fixtures.resolve(CASE_FIXTURE.get(entry.getKey()));
            if (entry.getKey().equals("file-newi")) continue;
            var file = new Loader(null).openLogisimFile(fixture.toFile());
            var proj = new Project(file);
            // ProjectActions.updatecircs: opened circuits know their project.
            for (var circ : file.getCircuits()) circ.setProject(proj);
            entry.getValue().apply(proj, file, fixtures);
            Files.writeString(out.resolve(entry.getKey() + ".circ"), write(file, fixture.toFile()));
            System.out.println("evolution " + entry.getKey());
        }
        if (only == null || only.contains("file-newi")) {
            // File > New: the plain template, as ProjectActions.doNew reads it from AppPreferences.
            var loader = new Loader(null);
            try (var template = AppPreferences.getTemplate().createStream()) {
                var file = loader.openLogisimFile(template);
                opened(file);
                Files.writeString(out.resolve("file-newi.circ"), write(file, fixtures.resolve("file-newi.circ").toFile()));
            }
            System.out.println("evolution file-newi");
        }
        if (only == null || only.contains("sim")) simulationCases(fixtures, out);
        if (only == null || only.contains("analysis")) analysisCases(fixtures, out);
        if (only == null || only.contains("recent")) recentCase(out);
        if (only == null || only.contains("bundle")) bundleCase(fixtures.toAbsolutePath());
        var json = new StringBuilder("{\n");
        var first = true;
        for (var e : STATE.entrySet()) {
            json.append(first ? "" : ",\n").append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
            first = false;
        }
        Files.writeString(out.resolve("state.json"), json.append("\n}\n").toString());
        System.exit(0);
    }

    /** Keeps Evolution's preferences in memory so a desktop installation does not change the result. */
    public static final class MemoryPreferencesFactory implements PreferencesFactory {
        private final Preferences user = new MemoryPreferences(null, "");
        private final Preferences system = new MemoryPreferences(null, "");
        @Override public Preferences userRoot() { return user; }
        @Override public Preferences systemRoot() { return system; }
    }

    static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
        @Override protected AbstractPreferences childSpi(String name) { return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n)); }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() { }
    }
}
