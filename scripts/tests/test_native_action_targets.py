from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "androidApp/src/androidTest/java/dev/logisim/mobile/input/NativeActionTargets.java"
FIXTURE = ROOT / "fixtures/user-scenarios/mixed-close-dialog.xml"

HARNESS = r"""
import dev.logisim.mobile.input.NativeActionTargets;
import java.io.File;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public final class NativeActionTargetsProbe {
  static NativeActionTargets.Node node(int parent, String text, String description,
                                      boolean visible, boolean enabled, boolean clickable) {
    return new NativeActionTargets.Node(parent,text,description,visible,enabled,clickable);
  }
  static void expect(int expected, List<NativeActionTargets.Node> nodes) {
    int actual = NativeActionTargets.resolve(nodes, new HashSet<>(Arrays.asList("Close","Fechar")));
    if (actual != expected) throw new AssertionError("Expected action index " + expected + ", got " + actual);
  }
  static NativeActionTargets.Node state(int parent, String text, boolean clickable,
                                       boolean selected, boolean checkable, boolean checked) {
    return new NativeActionTargets.Node(parent,text,"",true,true,clickable,selected,checkable,checked);
  }
  static void selection(String expected, int target, int[] sources, List<NativeActionTargets.Node> nodes) {
    NativeActionTargets.Action action=NativeActionTargets.resolveAction(nodes,
      new HashSet<>(Arrays.asList("Close","Fechar")));
    if(action==null || action.clickTarget!=target || !action.selection.name().equals(expected))
      throw new AssertionError("Expected "+expected+" on target"+target+", got"+
        (action==null?"null":action.selection+" on "+action.clickTarget));
    List<Integer> wanted=new ArrayList<>();
    for(int value:sources) wanted.add(value);
    if(!action.semanticNodes.equals(wanted))
      throw new AssertionError("Wrong semantic owners "+action.semanticNodes+" expected "+wanted);
  }
  static void add(Element element, int parent, List<NativeActionTargets.Node> result) {
    int index = result.size();
    result.add(node(parent,element.getAttribute("text"),element.getAttribute("content-desc"),
      !"false".equals(element.getAttribute("visible-to-user")),
      !"false".equals(element.getAttribute("enabled")),
      "true".equals(element.getAttribute("clickable"))));
    NodeList children = element.getChildNodes();
    for(int i=0;i<children.getLength();i++) if(children.item(i) instanceof Element)
      add((Element)children.item(i),index,result);
  }
  public static void main(String[] args) throws Exception {
    String scenario=args[0];
    if(scenario.equals("observed")) {
      DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
      Element root=factory.newDocumentBuilder().parse(new File(args[1])).getDocumentElement();
      List<NativeActionTargets.Node> nodes=new ArrayList<>();
      NodeList children=root.getChildNodes();
      for(int i=0;i<children.getLength();i++) if(children.item(i) instanceof Element)
        add((Element)children.item(i),-1,nodes);
      expect(1,nodes);
    } else if(scenario.equals("direct")) {
      expect(0,Arrays.asList(node(-1,"Close","",true,true,true)));
    } else if(scenario.equals("coalesced")) {
      expect(0,Arrays.asList(node(-1,"","Fechar, Button",true,true,true)));
    } else if(scenario.equals("ambiguous")) {
      expect(-1,Arrays.asList(node(-1,"","",true,true,false),
        node(0,"Close","",true,true,true),node(0,"Fechar","",true,true,true)));
    } else if(scenario.equals("deduplicate")) {
      expect(1,Arrays.asList(node(-1,"","",true,true,false),
        node(0,"Close","",true,true,true),node(1,"Close","",true,true,false)));
    } else if(scenario.equals("nearest")) {
      expect(1,Arrays.asList(node(-1,"","",true,true,true),
        node(0,"","",true,true,true),node(1,"Close","",true,true,false)));
    } else if(scenario.equals("hidden")) {
      expect(-1,Arrays.asList(node(-1,"","",true,true,false),
        node(0,"","",true,true,true),node(1,"Close","",false,true,false)));
    } else if(scenario.equals("disabled")) {
      expect(-1,Arrays.asList(node(-1,"","",true,true,true),
        node(0,"","",true,false,true),node(1,"Close","",true,true,false)));
    } else if(scenario.equals("scope")) {
      expect(-1,Arrays.asList(node(-1,"Close","",true,true,false)));
    } else if(scenario.equals("button-active")) {
      selection("ACTIVE",1,new int[]{1},Arrays.asList(node(-1,"","",true,true,false),
        state(0,"",true,false,true,true),node(1,"Close","",true,true,false),
        node(1,"","",true,true,false)));
    } else if(scenario.equals("button-inactive")) {
      selection("INACTIVE",1,new int[]{1},Arrays.asList(node(-1,"","",true,true,false),
        state(0,"",true,false,true,false),node(1,"Close","",true,true,false)));
    } else if(scenario.equals("semantic-descriptor")) {
      selection("ACTIVE",1,new int[]{2},Arrays.asList(node(-1,"","",true,true,false),
        node(0,"","",true,true,true),state(1,"Close",false,false,true,true),
        node(1,"","",true,true,false)));
    } else if(scenario.equals("tab-active")) {
      selection("ACTIVE",0,new int[]{0},Arrays.asList(state(-1,"Close",true,true,false,false)));
    } else if(scenario.equals("unchecked-flag")) {
      selection("UNKNOWN",0,new int[]{},Arrays.asList(state(-1,"Close",true,false,false,true)));
    } else if(scenario.equals("nested-state")) {
      selection("INACTIVE",1,new int[]{1},Arrays.asList(node(-1,"","",true,true,false),
        state(0,"Close",true,false,true,false),state(1,"Other",true,false,true,true)));
    } else if(scenario.equals("outside-state")) {
      selection("UNKNOWN",1,new int[]{},Arrays.asList(node(-1,"","",true,true,false),
        node(0,"Close","",true,true,true),state(0,"Other",true,false,true,true)));
    } else if(scenario.equals("hidden-state")) {
      selection("UNKNOWN",0,new int[]{},Arrays.asList(node(-1,"Close","",true,true,true),
        new NativeActionTargets.Node(0,"","",false,true,false,false,true,true)));
    } else if(scenario.equals("disabled-state")) {
      selection("UNKNOWN",0,new int[]{},Arrays.asList(node(-1,"Close","",true,true,true),
        new NativeActionTargets.Node(0,"","",true,false,false,false,true,true)));
    } else if(scenario.equals("conflict")) {
      selection("AMBIGUOUS",0,new int[]{0,1},Arrays.asList(state(-1,"Close",true,false,true,true),
        state(0,"",false,false,true,false)));
    } else if(scenario.equals("same-node-conflict")) {
      selection("AMBIGUOUS",0,new int[]{0},Arrays.asList(state(-1,"Close",true,true,true,false)));
    } else if(scenario.equals("no-state")) {
      selection("UNKNOWN",0,new int[]{},Arrays.asList(node(-1,"Close","",true,true,true)));
    } else throw new AssertionError("Unknown scenario");
    System.out.println("PASS " + scenario);
  }
}
"""


class NativeActionTargetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="logisim-action-targets-")
        cls.classes = Path(cls.directory.name)
        harness = cls.classes / "NativeActionTargetsProbe.java"
        harness.write_text(HARNESS)
        subprocess.run(["javac", "--release", "8", "-d", str(cls.classes), str(SOURCE), str(harness)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def prove(self, scenario):
        result = subprocess.run(["java", "-cp", str(self.classes), "NativeActionTargetsProbe", scenario,
                                 str(FIXTURE)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PASS " + scenario, result.stdout)

    def test_observed_close_label_resolves_its_anonymous_clickable_parent(self):
        self.prove("observed")

    def test_direct_label_and_coalesced_description_remain_supported(self):
        self.prove("direct")
        self.prove("coalesced")

    def test_two_distinct_labelled_controls_remain_ambiguous(self):
        self.prove("ambiguous")

    def test_parent_and_child_labels_deduplicate_to_one_target(self):
        self.prove("deduplicate")

    def test_nearest_clickable_parent_wins(self):
        self.prove("nearest")

    def test_hidden_or_disabled_controls_are_not_promoted(self):
        self.prove("hidden")
        self.prove("disabled")

    def test_ancestor_outside_the_scoped_subtree_cannot_be_used(self):
        self.prove("scope")

    def test_button_selected_is_exposed_as_checkable_and_checked(self):
        self.prove("button-active")
        self.prove("button-inactive")

    def test_semantic_descriptor_is_separate_from_the_click_target(self):
        self.prove("semantic-descriptor")

    def test_tab_selected_is_supported(self):
        self.prove("tab-active")

    def test_checked_without_checkable_and_absent_state_do_not_prove_selection(self):
        self.prove("unchecked-flag")
        self.prove("no-state")

    def test_state_from_nested_or_other_controls_does_not_leak(self):
        self.prove("nested-state")
        self.prove("outside-state")
        self.prove("hidden-state")
        self.prove("disabled-state")

    def test_conflicting_semantic_state_does_not_qualify(self):
        self.prove("conflict")
        self.prove("same-node-conflict")


if __name__ == "__main__":
    unittest.main()
