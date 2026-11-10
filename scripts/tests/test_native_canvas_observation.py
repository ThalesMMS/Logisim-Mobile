import subprocess
from pathlib import Path
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "androidApp/src/androidTest/java/dev/logisim/mobile/input/NativeCanvasObservation.java"
PROBE = r"""
import dev.logisim.mobile.input.NativeCanvasObservation;
import java.util.ArrayList;
import java.util.List;

public final class CanvasObservationProbe {
    static NativeCanvasObservation.Node node(String tag, int window, int x, int y, int w, int h) {
        return new NativeCanvasObservation.Node(tag,window,true,true,
            new NativeCanvasObservation.Bounds(x,y,x+w,y+h));
    }
    static NativeCanvasObservation.Snapshot scene(int window, boolean complete, int dx, int dy,
                                                   boolean body, boolean toolbar) {
        List<NativeCanvasObservation.Node> nodes = new ArrayList<>();
        nodes.add(node("editor-canvas",window,0,0,100,100));
        nodes.add(node("canvas-component:mi-input",window,15+dx,35+dy,10,10));
        nodes.add(node("canvas-component:mi-output",window,75+dx,35+dy,10,10));
        if(body) nodes.add(node("canvas-component:mi-not",window,45+dx,35+dy,10,10));
        else nodes.add(node("canvas-port:6:mi-not:in",window,40+dx,35+dy,10,10));
        if(toolbar) nodes.add(node("editor-project-toolbar",window,0,0,100,10));
        return new NativeCanvasObservation.Snapshot(window,complete,nodes);
    }
    static void require(boolean condition, String message) {
        if(!condition) throw new AssertionError(message);
    }
    static NativeCanvasObservation.Snapshot focusScene(int window, int dy, boolean body) {
        List<NativeCanvasObservation.Node> nodes=new ArrayList<>();
        nodes.add(node("editor-canvas",window,0,655,1080,1464));
        nodes.add(node("canvas-component:mi-input",window,422,1263+dy,126,126));
        nodes.add(node("canvas-component:mi-output",window,926,1263+dy,126,126));
        if(body) nodes.add(node("canvas-component:mi-not",window,640,1263+dy,126,126));
        return new NativeCanvasObservation.Snapshot(window,true,nodes);
    }
    public static void main(String[] args) {
        NativeCanvasObservation.Baseline before = NativeCanvasObservation.capture(scene(7,true,0,0,true,true),"canvas-component:mi-not");
        require(before != null,"The observed initial body and anchors must qualify");
        String scenario=args[0];
        if(scenario.equals("occluded-body")) {
            NativeCanvasObservation.Point point=NativeCanvasObservation.reuse(before,scene(7,true,0,0,false,true));
            require(point==before.target && point.x==50 && point.y==40,
                "Reuse must return the literal previously observed body center, without reading ports");
        } else if(scenario.equals("translated-point")) {
            NativeCanvasObservation.Snapshot selected=scene(7,true,0,0,false,true);
            NativeCanvasObservation.Point point=NativeCanvasObservation.translate(before,selected,10,0,2);
            require(point!=null && point.x==70 && point.y==40,"Known XML delta must translate the observed point in world units");
            require(NativeCanvasObservation.translate(before,selected,0,0,2)==before.target,
                "Zero movement must retain the literal original observation");
        } else if(scenario.equals("translated-guards")) {
            require(NativeCanvasObservation.translate(before,scene(7,true,5,0,false,true),10,0,2)==null,
                "A translated target still requires exact absolute anchors");
            require(NativeCanvasObservation.translate(before,scene(8,true,0,0,false,true),10,0,2)==null,
                "A translated target cannot cross a window boundary");
            require(NativeCanvasObservation.translate(before,scene(7,false,0,0,false,true),10,0,2)==null,
                "A translated target cannot use an incomplete tree");
        } else if(scenario.equals("translated-invalid")) {
            NativeCanvasObservation.Snapshot selected=scene(7,true,0,0,false,true);
            for(double scale:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})
                require(NativeCanvasObservation.translate(before,selected,10,0,scale)==null,"Invalid scale must reject target inference");
            require(NativeCanvasObservation.translate(before,selected,Double.NaN,0,2)==null &&
                NativeCanvasObservation.translate(before,selected,0,Double.POSITIVE_INFINITY,2)==null,
                "Non-finite XML displacement must reject target inference");
            require(NativeCanvasObservation.translate(before,selected,30,0,2)==null &&
                NativeCanvasObservation.translate(before,selected,Double.MAX_VALUE,0,Double.MAX_VALUE)==null,
                "Outside or overflowing inferred points cannot authorize input");
        } else if(scenario.equals("focus-common-pan")) {
            NativeCanvasObservation.Baseline literal=NativeCanvasObservation.capture(focusScene(23249,0,true),"canvas-component:mi-not");
            NativeCanvasObservation.Snapshot after=focusScene(23249,-26,false);
            NativeCanvasObservation.Point point=NativeCanvasObservation.focusAfterLabelClear(literal,after);
            require(point!=null && point.x==703 && point.y==1300,"Focus must apply only the literal common anchor displacement");
            require(NativeCanvasObservation.reuse(literal,after)==null &&
                NativeCanvasObservation.translate(literal,after,0,0,2.29)==null,
                "The same pan must remain forbidden for cancel and mixed controls");
            require(NativeCanvasObservation.focusAfterLabelClear(literal,focusScene(23249,0,false))==literal.target,
                "A stationary label-clear frame keeps the original observation");
        } else if(scenario.equals("focus-vectors")) {
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,0,-5,false,true).nodes);
            nodes.set(2,node("canvas-component:mi-output",7,75,31,10,10));
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Different anchor displacement vectors cannot authorize focus");
            nodes.set(2,node("canvas-component:mi-output",7,75,35,10,10));
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Moving only one anchor cannot authorize focus");
        } else if(scenario.equals("focus-size-zoom")) {
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,0,-5,false,true).nodes);
            nodes.set(2,node("canvas-component:mi-output",7,75,31,10,8));
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Equal center vectors cannot hide changed anchor sizes");
            nodes=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            nodes.set(1,node("canvas-component:mi-input",7,10,35,10,10));
            nodes.set(2,node("canvas-component:mi-output",7,80,35,10,10));
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Zoom around the target center cannot authorize the pan opt-in");
        } else if(scenario.equals("focus-clip")) {
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,-16,0,false,true).nodes);
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "An anchor center inside canvas cannot hide a clipped rectangle");
            List<NativeCanvasObservation.Node> prior=new ArrayList<>(scene(7,true,0,0,true,true).nodes);
            prior.set(1,node("canvas-component:mi-input",7,-1,35,10,10));
            NativeCanvasObservation.Baseline clipped=NativeCanvasObservation.capture(new NativeCanvasObservation.Snapshot(7,true,prior),"canvas-component:mi-not");
            nodes=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            nodes.set(1,node("canvas-component:mi-input",7,4,35,10,10));
            nodes.set(2,node("canvas-component:mi-output",7,80,35,10,10));
            require(clipped!=null && NativeCanvasObservation.focusAfterLabelClear(clipped,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "A clipped initial anchor cannot prove a common pan");
            prior=new ArrayList<>(scene(7,true,0,0,true,true).nodes);
            prior.set(3,node("canvas-component:mi-not",7,45,90,10,8));
            NativeCanvasObservation.Baseline low=NativeCanvasObservation.capture(new NativeCanvasObservation.Snapshot(7,true,prior),"canvas-component:mi-not");
            require(low!=null && NativeCanvasObservation.focusAfterLabelClear(low,scene(7,true,0,10,false,true))==null,
                "Common pan cannot move the authorized point outside canvas");
        } else if(scenario.equals("focus-structure")) {
            require(NativeCanvasObservation.focusAfterLabelClear(before,scene(8,true,0,-5,false,true))==null &&
                NativeCanvasObservation.focusAfterLabelClear(before,scene(7,false,0,-5,false,true))==null,
                "Focus requires the same window and a complete tree");
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,0,-5,false,true).nodes);
            nodes.set(0,node("editor-canvas",7,0,1,100,99));
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Focus requires the same canvas bounds");
            nodes=new ArrayList<>(scene(7,true,0,-5,false,true).nodes);
            nodes.remove(2);
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "A missing anchor cannot authorize focus");
            nodes=new ArrayList<>(scene(7,true,0,-5,false,true).nodes);
            nodes.add(node("canvas-component:mi-input",7,15,30,10,10));
            require(NativeCanvasObservation.focusAfterLabelClear(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "An ambiguous anchor cannot authorize focus");
        } else if(scenario.equals("pan")) {
            require(NativeCanvasObservation.reuse(before,scene(7,true,5,0,false,true))==null,
                "Equal anchor span must not hide a horizontal pan");
            require(NativeCanvasObservation.reuse(before,scene(7,true,0,5,false,true))==null,
                "Both anchors' absolute y coordinates must also remain equal");
        } else if(scenario.equals("zoom")) {
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            nodes.set(1,node("canvas-component:mi-input",7,10,35,10,10));
            nodes.set(2,node("canvas-component:mi-output",7,80,35,10,10));
            require(NativeCanvasObservation.reuse(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Zoomed anchor positions must reject reuse");
        } else if(scenario.equals("window-canvas")) {
            require(NativeCanvasObservation.reuse(before,scene(8,true,0,0,false,true))==null,"Another window must reject reuse");
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            nodes.set(0,node("editor-canvas",7,0,1,100,99));
            require(NativeCanvasObservation.reuse(before,new NativeCanvasObservation.Snapshot(7,true,nodes))==null,
                "Changed canvas bounds must reject reuse");
        } else if(scenario.equals("incomplete")) {
            NativeCanvasObservation.Snapshot partial=scene(7,false,0,0,true,true);
            require(NativeCanvasObservation.capture(partial,"canvas-component:mi-not")==null &&
                NativeCanvasObservation.reuse(before,partial)==null &&
                !NativeCanvasObservation.editorReturned(partial,7) && NativeCanvasObservation.undoFocus(partial,true)==null,
                "An incomplete tree cannot authorize any observation");
        } else if(scenario.equals("missing-duplicate")) {
            List<NativeCanvasObservation.Node> missing=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            missing.remove(1);
            require(NativeCanvasObservation.reuse(before,new NativeCanvasObservation.Snapshot(7,true,missing))==null,"Missing anchor must reject");
            List<NativeCanvasObservation.Node> duplicate=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            duplicate.add(node("pkg:id/canvas-component:mi-input",7,15,35,10,10));
            require(NativeCanvasObservation.reuse(before,new NativeCanvasObservation.Snapshot(7,true,duplicate))==null,"Duplicate visible anchor must reject");
            List<NativeCanvasObservation.Node> disabled=new ArrayList<>(scene(7,true,0,0,false,true).nodes);
            disabled.set(1,new NativeCanvasObservation.Node("canvas-component:mi-input",7,true,false,
                new NativeCanvasObservation.Bounds(15,35,25,45)));
            require(NativeCanvasObservation.reuse(before,new NativeCanvasObservation.Snapshot(7,true,disabled))==null,"Disabled anchor must reject");
            require(NativeCanvasObservation.capture(scene(7,true,0,0,false,true),"canvas-component:mi-not")==null,
                "A port cannot replace the body in the initial observation");
        } else if(scenario.equals("invalid-bounds")) {
            List<NativeCanvasObservation.Node> nodes=new ArrayList<>(scene(7,true,0,0,true,true).nodes);
            nodes.set(3,node("canvas-component:mi-not",7,45,35,0,10));
            require(NativeCanvasObservation.capture(new NativeCanvasObservation.Snapshot(7,true,nodes),"canvas-component:mi-not")==null,
                "A zero-area body is not an observed input target");
            nodes.set(3,node("canvas-component:mi-not",7,145,35,10,10));
            require(NativeCanvasObservation.capture(new NativeCanvasObservation.Snapshot(7,true,nodes),"canvas-component:mi-not")==null,
                "An outside-canvas body cannot supply a control point");
        } else if(scenario.equals("editor-return")) {
            require(!NativeCanvasObservation.editorReturned(scene(9,true,0,0,false,false),7),"Popup cannot be editor return");
            require(!NativeCanvasObservation.editorReturned(scene(8,true,0,0,true,true),7),"Another editor window cannot qualify");
            require(!NativeCanvasObservation.editorReturned(scene(7,true,0,0,true,false),7),"Canvas without toolbar cannot authorize export");
            require(NativeCanvasObservation.editorReturned(scene(7,true,0,0,true,true),7),"Original complete editor must qualify");
            List<NativeCanvasObservation.Node> duplicate=new ArrayList<>(scene(7,true,0,0,true,true).nodes);
            duplicate.add(node("editor-project-toolbar",7,0,0,100,10));
            require(!NativeCanvasObservation.editorReturned(new NativeCanvasObservation.Snapshot(7,true,duplicate),7),"Duplicate toolbar cannot authorize export");
            List<NativeCanvasObservation.Node> foreign=new ArrayList<>(scene(7,true,0,0,true,true).nodes);
            foreign.set(0,node("editor-canvas",8,0,0,100,100));
            require(!NativeCanvasObservation.editorReturned(new NativeCanvasObservation.Snapshot(7,true,foreign),7),"A node from another window cannot authorize export");
        } else if(scenario.equals("undo-focus")) {
            NativeCanvasObservation.Snapshot selected=scene(7,true,0,0,false,true);
            require(NativeCanvasObservation.undoFocus(selected,false)==null,"Input anchor cannot be used without verified Select mode");
            NativeCanvasObservation.Point point=NativeCanvasObservation.undoFocus(selected,true);
            require(point!=null && point.x==20 && point.y==40,"Select mode may focus the observed input anchor");
        } else throw new AssertionError("Unknown scenario "+scenario);
        System.out.println("PASS "+scenario);
    }
}
"""


class NativeCanvasObservationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.directory.name)
        probe = cls.classes / "CanvasObservationProbe.java"
        probe.write_text(PROBE)
        subprocess.run(["javac", "--release", "8", "-d", str(cls.classes), str(SOURCE), str(probe)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def prove(self, scenario):
        result = subprocess.run(["java", "-cp", str(self.classes), "CanvasObservationProbe", scenario],
                                check=True, capture_output=True, text=True)
        self.assertEqual("PASS " + scenario, result.stdout.strip())

    def test_body_occlusion_reuses_only_the_previously_observed_point(self):
        self.prove("occluded-body")

    def test_known_world_delta_translates_the_previously_observed_point(self):
        self.prove("translated-point")

    def test_translation_keeps_all_viewport_guards(self):
        self.prove("translated-guards")

    def test_invalid_scale_delta_and_outside_translated_point_are_rejected(self):
        self.prove("translated-invalid")

    def test_focus_label_clear_can_apply_only_the_observed_common_pan(self):
        self.prove("focus-common-pan")

    def test_focus_label_clear_rejects_different_vectors_or_one_moved_anchor(self):
        self.prove("focus-vectors")

    def test_focus_label_clear_rejects_zoom_or_changed_anchor_dimensions(self):
        self.prove("focus-size-zoom")

    def test_focus_label_clear_rejects_clipping_and_outside_targets(self):
        self.prove("focus-clip")

    def test_focus_label_clear_preserves_structural_guards(self):
        self.prove("focus-structure")

    def test_pan_is_rejected_even_when_anchor_span_is_unchanged(self):
        self.prove("pan")

    def test_zoom_is_rejected(self):
        self.prove("zoom")

    def test_window_and_canvas_changes_are_rejected(self):
        self.prove("window-canvas")

    def test_incomplete_tree_cannot_authorize_input_or_export(self):
        self.prove("incomplete")

    def test_missing_or_ambiguous_anchors_and_initial_body_are_rejected(self):
        self.prove("missing-duplicate")

    def test_invalid_or_outside_body_bounds_are_rejected(self):
        self.prove("invalid-bounds")

    def test_editor_return_requires_original_window_canvas_and_toolbar(self):
        self.prove("editor-return")

    def test_input_focus_anchor_requires_verified_select_mode(self):
        self.prove("undo-focus")


if __name__ == "__main__":
    unittest.main()
