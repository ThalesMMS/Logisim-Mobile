import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.tools.TextEditable;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.JoinedAction;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.tools.TextTool;
import dev.logisim.mobile.core.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies the same Text Tool edits with Evolution's TextTool commit path and with the Mobile
 * EditorState.beginTextToolEdit/applyTextToolEdit rules (core calls only), then reopens both results
 * in Evolution and compares every component attribute. Usage: TextToolOracle text-tool.circ OUTPUT_DIR
 *
 * Edits: (1) new text "Gate timing\nsecond line" at (220,160) from the toolbar Text Tool;
 * (2) Pin label "counter_enable" -> "clock_enable"; (3) Text "Keep" -> "Kept note";
 * (4) Text "Old note" -> "" (cleared, kept by both); (5) new text left empty at (400,400).
 */
public final class TextToolOracle {
    static final String NEW_TEXT = "Gate timing\nsecond line";

    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var output = Path.of(args[1]);
        Files.createDirectories(output);
        // Evolution autosaves next to the opened file after an edit; work on a copy in OUTPUT_DIR.
        var source = output.resolve("text-tool.circ").toFile();
        Files.copy(Path.of(args[0]), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Evolution: the tool's attribute set and the TextTool.editingStopped actions.
        var loader = new Loader(null);
        var file = loader.openLogisimFile(source);
        var project = new Project(file);
        var circuit = file.getMainCircuit();
        var tool = new TextTool();
        System.out.println("desktop-tool-defaults\t" + attributes(tool.getAttributeSet()));
        var created = Text.FACTORY.createComponent(Location.create(220, 160, false), (AttributeSet) tool.getAttributeSet().clone());
        var add = new CircuitMutation(circuit);
        add.add(created);
        Action action = add.toAction(null);
        var commit = ((TextEditable) created.getFeature(TextEditable.class)).getCommitAction(circuit, "", NEW_TEXT);
        if (commit != null) action = new JoinedAction(action, commit);
        project.doAction(action);
        project.doAction(edit(circuit, find(circuit, "counter_enable"), "counter_enable", "clock_enable"));
        project.doAction(edit(circuit, find(circuit, "Keep"), "Keep", "Kept note"));
        // Clearing an existing Text: editingStopped builds CircuitMutation.add(caretComponent).
        var cleared = find(circuit, "Old note");
        var clear = new CircuitMutation(circuit);
        clear.add(cleared);
        project.doAction(clear.toAction(null));
        // The empty new text (5) is never added by editingStopped.
        var desktopOut = output.resolve("desktop-text-tool.circ").toFile();
        if (!loader.save(file, desktopOut)) throw new AssertionError("Desktop save failed");
        project.getSimulator().shutDown();

        // Mobile: same edits through the core calls EditorState uses.
        var registry = ComponentCatalog.INSTANCE.getRegistry();
        var imported = CircCodec.INSTANCE.readProject(Files.readString(source.toPath()), registry);
        if (!(imported instanceof CircProjectImportResult.Success success)) throw new AssertionError(imported);
        var mobile = success.getProject();
        CircuitComponent prototype = null;
        for (var item : ProjectToolbar.INSTANCE.read(mobile)) {
            if (ProjectToolbar.INSTANCE.binding(item, mobile, registry) instanceof ProjectToolbarBinding.Text text) prototype = text.getPrototype();
        }
        if (prototype == null) throw new AssertionError("Mobile toolbar has no Text Tool binding");
        System.out.println("mobile-tool-prototype\t" + new java.util.TreeMap<>(prototype.getAttributes()));
        var defaults = new CreationDefaults();
        if (!prototype.getAttributes().containsKey("color")) {
            var merged = new java.util.LinkedHashMap<>(prototype.getAttributes());
            merged.putAll(defaults.textAttributes());
            prototype = registry.create(prototype.getKind(), prototype.getId(), prototype.getPosition(), merged, prototype.getLabel());
        }
        // EditorState gives a Text without a font the project's default (bold 16 here), implicit on save (#346).
        if (!prototype.getAttributes().containsKey("font")) {
            var font = AnnotationLibrary.INSTANCE.defaultTextFont(mobile.isOriginalLogisim());
            var attributes = new java.util.LinkedHashMap<>(prototype.getAttributes());
            attributes.put("font", font);
            var implicit = new java.util.LinkedHashMap<>(prototype.getImplicitAttributes());
            implicit.put("font", font);
            prototype = prototype.copy(prototype.getId(), prototype.getKind(), prototype.getPosition(), prototype.getLabel(),
                attributes, prototype.getCircuitName(), prototype.getXmlAttributes(), prototype.getExtraXml(), prototype.getExplicitPorts(),
                prototype.getExplicitBounds(), prototype.getAppearanceTexts(), prototype.getSharedLabel(), implicit,
                prototype.isOriginalSequential$LogisimMobile_core());
        }
        var editor = new CircuitEditor(mobile.getMainCircuit(), registry);
        var point = new GridPoint(220, 160);
        editor.add(registry.reconfigure(prototype, Map.of("text", NEW_TEXT)), point);
        var pin = mobileFind(editor.getDocument(), "counter_enable");
        editor.updateComponents(Map.of(pin.getId(), pin.copy(pin.getId(), pin.getKind(), pin.getPosition(), "clock_enable",
            pin.getAttributes(), pin.getCircuitName(), pin.getXmlAttributes(), pin.getExtraXml(), pin.getExplicitPorts(),
            pin.getExplicitBounds(), pin.getAppearanceTexts(), pin.getSharedLabel(), pin.getImplicitAttributes(),
            pin.isOriginalSequential$LogisimMobile_core())));
        var keep = mobileFind(editor.getDocument(), "Keep");
        editor.updateComponents(Map.of(keep.getId(), registry.reconfigure(keep, Map.of("text", "Kept note"))));
        // Clearing "Old note" leaves it unchanged in EditorState.applyTextToolEdit, as in Evolution (#346).
        var mobileOut = output.resolve("mobile-text-tool.circ");
        Files.writeString(mobileOut, CircCodec.INSTANCE.writeProject(mobile.withCircuit(editor.getDocument()), registry, false));

        var desktopDump = dump(desktopOut);
        var mobileDump = dump(mobileOut.toFile());
        Files.write(output.resolve("desktop-reopened.tsv"), desktopDump);
        Files.write(output.resolve("mobile-reopened.tsv"), mobileDump);
        var same = desktopDump.equals(mobileDump);
        // Geometry of each Text in the Evolution-saved file, as Evolution and as Mobile resolve it
        // (fonts omitted from the file use each program's own default).
        var annotationRegistry = registry.withDefinitions(AnnotationLibrary.INSTANCE.definitions());
        var reread = CircCodec.INSTANCE.readProject(Files.readString(desktopOut.toPath()), annotationRegistry);
        if (!(reread instanceof CircProjectImportResult.Success again)) throw new AssertionError(reread);
        var boundsLoader = new Loader(null);
        var boundsFile = boundsLoader.openLogisimFile(desktopOut);
        var boundsProject = new Project(boundsFile);
        for (var component : boundsFile.getMainCircuit().getNonWires()) {
            if (!(component.getFactory() instanceof Text)) continue;
            var loc = component.getLocation();
            var match = again.getProject().getMainCircuit().getComponents().stream()
                .filter(item -> item.getPosition().getX() == loc.getX() && item.getPosition().getY() == loc.getY()).findFirst().orElseThrow(() -> new AssertionError("Mobile has no Text at " + loc + ": " + again.getProject().getMainCircuit().getComponents()));
            var b = AnnotationLibrary.INSTANCE.bounds(match);
            var d = component.getBounds();
            var desktopBounds = d.getX() + "," + d.getY() + "," + d.getWidth() + "x" + d.getHeight();
            var mobileBounds = b.getLeft() + "," + b.getTop() + "," + b.getWidth() + "x" + b.getHeight();
            var equal = desktopBounds.equals(mobileBounds);
            if (!equal) same = false;
            System.out.println((equal ? "SAME" : "DIFF") + "	bounds	" + loc + "	desktop=" + desktopBounds + "	mobile=" + mobileBounds
                + "	mobile-font=" + match.getAttributes().getOrDefault("font", "(absent)"));
        }
        boundsProject.getSimulator().shutDown();
        for (var line : desktopDump) if (!mobileDump.contains(line)) System.out.println("ONLY-DESKTOP\t" + line);
        for (var line : mobileDump) if (!desktopDump.contains(line)) System.out.println("ONLY-MOBILE\t" + line);
        System.out.println(same ? "SAME text-tool (" + desktopDump.size() + " components)" : "DIFF text-tool");
        System.exit(same ? 0 : 1);
    }

    private static Action edit(Circuit circuit, Component component, String before, String after) {
        return ((TextEditable) component.getFeature(TextEditable.class)).getCommitAction(circuit, before, after);
    }

    private static Component find(Circuit circuit, String text) {
        for (var component : circuit.getNonWires()) {
            var attrs = component.getAttributeSet();
            if (attrs.containsAttribute(Text.ATTR_TEXT) && text.equals(attrs.getValue(Text.ATTR_TEXT))) return component;
            var label = attrs.getAttribute("label");
            if (label != null && text.equals(attrs.getValue(label))) return component;
        }
        var seen = new ArrayList<String>();
        for (var component : circuit.getNonWires()) seen.add(component.getFactory().getName() + " " + attributes(component.getAttributeSet()));
        throw new AssertionError("No component " + text + " in " + seen);
    }

    private static CircuitComponent mobileFind(CircuitDocument document, String text) {
        for (var component : document.getComponents()) {
            if (text.equals(component.getAttributes().get("text")) || text.equals(component.getLabel())) return component;
        }
        throw new AssertionError("No mobile component " + text);
    }

    /** One line per component, sorted: factory, location and every attribute in Evolution's text form. */
    private static List<String> dump(File circ) throws Exception {
        var loader = new Loader(null);
        var file = loader.openLogisimFile(circ);
        var project = new Project(file);
        var lines = new ArrayList<String>();
        for (var component : file.getMainCircuit().getNonWires()) {
            lines.add(component.getFactory().getName() + "\t" + component.getLocation() + "\t" + attributes(component.getAttributeSet()));
        }
        project.getSimulator().shutDown();
        lines.sort(String::compareTo);
        return lines;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String attributes(AttributeSet attrs) {
        var parts = new ArrayList<String>();
        for (Attribute attribute : attrs.getAttributes()) {
            var value = attrs.getValue(attribute);
            parts.add(attribute.getName() + "=" + (value == null ? "null" : attribute.toStandardString(value).replace("\n", "\\n")));
        }
        parts.sort(String::compareTo);
        return String.join(";", parts);
    }
}
