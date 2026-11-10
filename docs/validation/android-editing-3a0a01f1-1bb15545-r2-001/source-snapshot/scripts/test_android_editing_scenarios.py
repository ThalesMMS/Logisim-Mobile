#!/usr/bin/env python3
"""Check editing selectors and routing with literal UI hierarchies, without adb."""

from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from android_editing_scenarios import _Editing
from android_ui import AndroidUi


PACKAGE = "dev.logisim.mobile.e2e"
CATALOG = {
    "component_not": {"en": "NOT", "pt": "NOT"},
    "a11y_explorer_title": {"en": "Circuit explorer", "pt": "Explorador do circuito"},
    "a11y_explorer_edit": {"en": "Edit properties of {name}", "pt": "Editar propriedades de {name}"},
    "a11y_explorer_components": {"en": "Components", "pt": "Componentes"},
    "a11y_explorer_select": {"en": "Select {name}", "pt": "Selecionar {name}"},
    "a11y_library": {"en": "Component library", "pt": "Biblioteca de componentes"},
    "a11y_fit_circuit": {"en": "Fit circuit", "pt": "Enquadrar circuito"},
    "action_simulate": {"en": "Simulate", "pt": "Simular"},
    "action_select": {"en": "Select", "pt": "Selecionar"},
    "action_move": {"en": "Move", "pt": "Mover"},
    "action_wire": {"en": "Wire", "pt": "Fio"},
    "a11y_select_all": {"en": "Select all", "pt": "Selecionar tudo"},
    "a11y_copy_selection": {"en": "Copy selection", "pt": "Copiar seleção"},
    "a11y_paste_components": {"en": "Paste components", "pt": "Colar componentes"},
    "action_undo": {"en": "Undo", "pt": "Desfazer"},
    "action_redo": {"en": "Redo", "pt": "Refazer"},
    "a11y_import_export_project": {"en": "Import or export XML", "pt": "Importar ou exportar XML"},
    "a11y_project_files": {"en": "Project files", "pt": "Arquivos do projeto"},
    "a11y_project_tools": {"en": "Project tools", "pt": "Ferramentas do projeto"},
    "a11y_open_help": {"en": "Help", "pt": "Ajuda"},
    "property_label": {"en": "Label", "pt": "Rótulo"},
    "action_cancel": {"en": "Cancel", "pt": "Cancelar"},
    "inspector_edit": {"en": "Edit property {name}", "pt": "Editar propriedade {name}"},
    "attribute_label_facing": {"en": "Facing", "pt": "Direção"},
    "direction_north": {"en": "North", "pt": "Norte"},
    "direction_west": {"en": "West", "pt": "Oeste"},
}

NOT_DIALOG = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][400,800]">
  <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" text="NOT" bounds="[10,80][390,130]"/>
  <node package="dev.logisim.mobile.e2e" class="android.widget.Button" resource-id="insert-not" clickable="true" bounds="[10,300][390,350]">
    <node package="dev.logisim.mobile.e2e" class="android.widget.TextView" text="NOT" bounds="[20,310][100,340]"/>
  </node>
</node></hierarchy>'''

EXPLORER = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][600,1000]">
  <node package="dev.logisim.mobile.e2e" content-desc="Component library" bounds="[0,0][600,1000]">
    <node package="dev.logisim.mobile.e2e" scrollable="true" bounds="[0,0][600,950]"/>
    <node package="dev.logisim.mobile.e2e" text="Circuit explorer" clickable="true" resource-id="background-title" bounds="[0,0][150,50]"/>
  </node>
  <node package="dev.logisim.mobile.e2e" resource-id="editor-tool-sheet" bounds="[20,200][580,950]">
    <node package="dev.logisim.mobile.e2e" resource-id="a11y-circuit-list" scrollable="true" bounds="[30,300][570,900]">
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="a11y-circuit-search" text="E2E_NOT" bounds="[40,310][550,360]"/>
    </node>
  </node>
</node></hierarchy>'''

LEGACY_TOOLBAR = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][400,800]">
  <node package="dev.logisim.mobile.e2e" text="Simulate" clickable="true" bounds="[0,50][100,100]"/>
  <node package="dev.logisim.mobile.e2e" resource-id="editor-canvas" bounds="[0,100][400,800]"/>
</node></hierarchy>'''

CLIPPED_INSPECTOR = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][600,1000]">
  <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="background-editor" bounds="[0,0][100,60]"/>
  <node package="dev.logisim.mobile.e2e" resource-id="editor-inspector-sheet" bounds="[0,400][600,1000]">
    <node package="dev.logisim.mobile.e2e" scrollable="true" bounds="[0,400][600,820]">
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-label" bounds="[10,450][590,510]"/>
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-x" bounds="[10,600][590,660]"/>
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-y" bounds="[10,800][590,860]"/>
    </node>
  </node>
</node></hierarchy>'''

READY_INSPECTOR = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][600,1000]">
  <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="background-editor" bounds="[0,0][100,60]"/>
  <node package="dev.logisim.mobile.e2e" resource-id="editor-inspector-sheet" bounds="[0,400][600,1000]">
    <node package="dev.logisim.mobile.e2e" scrollable="true" bounds="[0,400][600,1000]">
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-label" bounds="[10,450][590,510]"/>
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-x" bounds="[10,600][590,660]"/>
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-y" bounds="[10,800][590,860]"/>
    </node>
  </node>
</node></hierarchy>'''

LOCAL_INSPECTOR = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][1080,2400]">
  <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="background-editor" text="Label" bounds="[21,150][1059,300]"/>
  <node package="dev.logisim.mobile.e2e" resource-id="editor-inspector-sheet" scrollable="false" bounds="[0,0][1080,1130]">
    <node package="dev.logisim.mobile.e2e" scrollable="true" bounds="[0,1300][1080,2400]">
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-label" clickable="true" bounds="[21,1524][1059,1692]">
        <node package="dev.logisim.mobile.e2e" text="Label" bounds="[63,1587][172,1650]"/>
      </node>
      <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="offscreen-editor" bounds="[21,2340][1059,2460]"/>
    </node>
  </node>
</node></hierarchy>'''


class RecordingUi:
    """The fake never launches a process; swipes advance to the next literal dump."""
    package = PACKAGE
    _rectangle = staticmethod(AndroidUi._rectangle)
    visible_node = AndroidUi.visible_node
    tagged = AndroidUi.tagged

    def __init__(self, *hierarchies, routed=None, routing_error=None):
        self.roots = [ET.fromstring(xml) for xml in hierarchies]
        self.index = 0
        self.routed = routed
        self.routing_error = routing_error
        self.routes = []
        self.commands = []
        self.inputs = []

    def screen(self, timeout=30):
        return self.roots[self.index]

    def toolbar_action(self, toolbars, *, matches, timeout=30, root=None):
        self.routes.append((tuple(toolbars), matches, root))
        if self.routing_error:
            raise self.routing_error
        return self.routed

    def adb(self, *arguments):
        self.commands.append(arguments)
        if arguments[:3] == ("shell", "input", "swipe"):
            self.index = min(self.index + 1, len(self.roots) - 1)
        return ""

    def input_text(self, node, value):
        self.inputs.append((node, value))


class PollingUi(RecordingUi):
    def screen(self, timeout=30):
        root = super().screen(timeout)
        self.index = min(self.index + 1, len(self.roots) - 1)
        return root


class FakeClock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now = round(self.now + seconds, 9)


class EditingSelectorTests(unittest.TestCase):
    def setUp(self):
        process = patch("subprocess.run", side_effect=AssertionError("Device processes are forbidden in selector tests"))
        process.start()
        self.addCleanup(process.stop)

    def editing(self, ui):
        scenarios = SimpleNamespace(ui=ui, catalog=CATALOG,
                                    canvas=lambda root: ui.tagged(root, "editor-canvas"))
        return _Editing(scenarios)

    def test_not_query_is_not_the_not_insertion_action(self):
        ui = RecordingUi(NOT_DIALOG)
        found = self.editing(ui).nodes(ui.screen(), "component_not")
        self.assertEqual(["insert-not"], [node.get("resource-id") for node in found])
        self.assertEqual([], ui.commands)

    def test_foreign_window_labels_do_not_match_application_actions(self):
        xml = NOT_DIALOG.replace('</node></hierarchy>',
            '<node package="android" text="NOT" clickable="true" bounds="[10,400][390,450]"/></node></hierarchy>')
        ui = RecordingUi(xml)
        found = self.editing(ui).nodes(ui.screen(), "component_not")
        self.assertEqual(["insert-not"], [node.get("resource-id") for node in found])

    def test_wait_node_accepts_a_matching_leaf_button(self):
        xml = '<hierarchy><node package="dev.logisim.mobile.e2e" text="Components" clickable="true" bounds="[10,10][200,60]"/></hierarchy>'
        ui = RecordingUi(xml)
        node = self.editing(ui).wait_node("a11y_explorer_components")
        self.assertEqual("Components", node.get("text"))

    def test_component_name_reads_the_semantic_child_of_its_clickable_row(self):
        for action in ("Select", "Selecionar"):
            xml = f'''<hierarchy><node package="{PACKAGE}" bounds="[0,0][600,1000]">
              <node package="{PACKAGE}" resource-id="component:c1" clickable="true" bounds="[10,100][590,180]">
                <node package="{PACKAGE}" content-desc="{action} Input (100, 100), c1" bounds="[10,100][590,180]"/>
              </node></node></hierarchy>'''
            self.assertEqual("Input (100, 100), c1", self.editing(RecordingUi(xml)).component_name())

    def test_component_name_rejects_ambiguous_semantic_children(self):
        xml = f'''<hierarchy><node package="{PACKAGE}" bounds="[0,0][600,1000]">
          <node package="{PACKAGE}" clickable="true" bounds="[10,100][590,180]">
            <node package="{PACKAGE}" content-desc="Select FIRST" bounds="[10,100][290,180]"/>
            <node package="{PACKAGE}" content-desc="Select SECOND" bounds="[300,100][590,180]"/>
          </node></node></hierarchy>'''
        with self.assertRaisesRegex(AssertionError, "Expected one component selection name"):
            self.editing(RecordingUi(xml)).component_name()

    def test_export_prepares_receiver_without_an_open_toolbar_menu(self):
        xml = "<project><circuit name=\"main\"/></project>"
        project = ET.fromstring(xml)
        action = ET.fromstring(f'<node package="{PACKAGE}" clickable="true" bounds="[10,10][100,60]"/>')
        ui = RecordingUi(LEGACY_TOOLBAR, routed=action)

        def copy_through_receiver(name):
            # Revealing an overflow action can open its menu. The complete
            # receiver route must return to an unobstructed editor first.
            self.assertEqual([], ui.routes, "The foreground receiver inherited an open toolbar menu")
            self.assertEqual("editing-baseline", name)
            return xml, project

        scenarios = SimpleNamespace(ui=ui, catalog=CATALOG, export_xml=copy_through_receiver)
        result_xml, result_project = _Editing(scenarios).export("editing-baseline")
        self.assertEqual('<project><circuit name="main"/></project>', result_xml)
        self.assertEqual("main", result_project.find("circuit").get("name"))
        self.assertEqual([], ui.commands)

    def test_export_preserves_receiver_failure(self):
        ui = RecordingUi(LEGACY_TOOLBAR)
        failure = AssertionError("The foreground test receiver did not read the copied project XML")

        def failed_receiver(name):
            raise failure

        scenarios = SimpleNamespace(ui=ui, catalog=CATALOG, export_xml=failed_receiver)
        with self.assertRaises(AssertionError) as caught:
            _Editing(scenarios).export("editing-baseline")
        self.assertIs(failure, caught.exception)
        self.assertEqual([], ui.routes)
        self.assertEqual([], ui.commands)

    def inspector(self, *, label="E2E_NOT", facing="North", cancel=False):
        cancel_node = f'<node package="{PACKAGE}" text="Cancel" clickable="true" bounds="[20,100][180,160]"/>' if cancel else ""
        return f'''<hierarchy><node package="{PACKAGE}" bounds="[0,0][600,1000]">
          <node package="{PACKAGE}" resource-id="editor-inspector-sheet" bounds="[0,400][600,1000]">
            <node package="{PACKAGE}" class="android.widget.EditText" text="{label}" bounds="[20,450][580,510]"/>
            <node package="{PACKAGE}" content-desc="Edit property Facing" clickable="true" bounds="[20,520][580,580]">
              <node package="{PACKAGE}" text="Facing" bounds="[30,525][280,575]"/>
              <node package="{PACKAGE}" text="{facing}" bounds="[300,525][570,575]"/>
            </node>
          </node>{cancel_node}
        </node></hierarchy>'''

    def closing(self, *states):
        ui = PollingUi(*states)
        events = []
        ui.key = lambda value: events.append(("key", value, ui.index))
        scenarios = SimpleNamespace(ui=ui, catalog=CATALOG, wait_editor=lambda: events.append(("editor-ready",)))
        return _Editing(scenarios), ui, events

    def test_inspector_back_waits_for_property_dialog_to_stop_owning_the_window(self):
        editing, ui, events = self.closing(self.inspector(cancel=True), self.inspector())
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            editing.close_inspector("E2E_NOT", facing="direction_north")
        self.assertEqual([("key", 4, 1), ("editor-ready",)], events)
        self.assertGreater(clock.now, 0)

    def test_inspector_back_waits_for_the_applied_facing_value(self):
        editing, ui, events = self.closing(self.inspector(facing="East"), self.inspector())
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            editing.close_inspector("E2E_NOT", facing="direction_north")
        self.assertEqual([("key", 4, 1), ("editor-ready",)], events)
        self.assertGreater(clock.now, 0)

    def test_partially_visible_cancel_still_owns_the_window(self):
        partial = self.inspector(cancel=True).replace('[20,100][180,160]', '[20,-10][180,50]')
        editing, ui, events = self.closing(partial, self.inspector())
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            editing.close_inspector("E2E_NOT", facing="direction_north")
        self.assertEqual([("key", 4, 1), ("editor-ready",)], events)
        self.assertGreater(clock.now, 0)

    def test_hidden_cancel_does_not_own_the_window(self):
        hidden = self.inspector(cancel=True).replace('text="Cancel"', 'text="Cancel" visible-to-user="false"')
        editing, ui, events = self.closing(hidden)
        editing.close_inspector("E2E_NOT", facing="direction_north")
        self.assertEqual([("key", 4, 0), ("editor-ready",)], events)

    def test_inspector_back_rejects_another_component_until_the_deadline(self):
        editing, ui, events = self.closing(self.inspector(label="OTHER"))
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            with self.assertRaisesRegex(AssertionError, "applied inspector for E2E_NOT"):
                editing.close_inspector("E2E_NOT", facing="direction_north")
        self.assertEqual([], events)
        self.assertGreaterEqual(clock.now, 20)

    def test_inspector_back_is_requested_once_before_editor_readiness(self):
        editing, ui, events = self.closing(self.inspector(label="E2E_OUT", facing="West"))
        editing.close_inspector("E2E_OUT", facing="direction_west")
        self.assertEqual([("key", 4, 0), ("editor-ready",)], events)

    def test_search_reveals_its_editor_even_when_commands_are_already_visible(self):
        clipped = EXPLORER.replace('[40,310][550,360]', '[40,280][550,330]')
        clipped = clipped.replace('</node>\n  </node>\n</node></hierarchy>', '''
          <node package="dev.logisim.mobile.e2e" text="Components" clickable="true" bounds="[40,340][200,390]"/>
        </node></node></node></hierarchy>''')
        ui = RecordingUi(clipped, EXPLORER)
        self.editing(ui).search("E2E_IN")
        self.assertEqual(("shell", "input", "swipe"), ui.commands[0][:3])
        self.assertEqual(1, len(ui.inputs))
        node, value = ui.inputs[0]
        self.assertEqual("[40,310][550,360]", node.get("bounds"))
        self.assertEqual("E2E_IN", value)

    def test_top_actions_use_both_current_toolbar_routes_and_exact_labels(self):
        routed = ET.fromstring('<node package="dev.logisim.mobile.e2e" content-desc="Redo" clickable="true" bounds="[10,10][100,60]"/>')
        ui = RecordingUi(LEGACY_TOOLBAR, routed=routed)
        self.assertIs(routed, self.editing(ui).reveal("action_redo", description=True, toolbar="top"))
        routes, matches, root = ui.routes[0]
        self.assertEqual(("editor-project-toolbar", "editor-command-toolbar"), routes)
        self.assertIs(ui.screen(), root)
        self.assertTrue(matches(routed))
        self.assertFalse(matches(ET.fromstring('<node content-desc="Redo something else"/>')))
        self.assertFalse(matches(ET.fromstring('<node text="Redo"/>')))
        self.assertEqual([], ui.commands)

    def test_command_action_uses_only_the_command_toolbar(self):
        routed = ET.fromstring('<node package="dev.logisim.mobile.e2e" content-desc="Copy selection" bounds="[10,10][100,60]"/>')
        ui = RecordingUi(LEGACY_TOOLBAR, routed=routed)
        self.assertIs(routed, self.editing(ui).reveal("a11y_copy_selection", description=True, toolbar=True))
        self.assertEqual(("editor-command-toolbar",), ui.routes[0][0])

    def test_no_overflow_route_retains_the_legacy_swipe(self):
        revealed = LEGACY_TOOLBAR.replace('</node></hierarchy>',
            '<node package="dev.logisim.mobile.e2e" content-desc="Fit circuit" clickable="true" bounds="[200,50][300,100]"/></node></hierarchy>')
        ui = RecordingUi(LEGACY_TOOLBAR, revealed)
        node = self.editing(ui).reveal("a11y_fit_circuit", description=True, toolbar=True)
        self.assertEqual("Fit circuit", node.get("content-desc"))
        self.assertEqual(1, len(ui.routes))
        self.assertEqual(("shell", "input", "swipe"), ui.commands[0][:3])

    def test_missing_current_overflow_action_is_not_hidden_by_legacy_swipes(self):
        ui = RecordingUi(LEGACY_TOOLBAR, routing_error=AssertionError("Action absent from current overflow"))
        with self.assertRaisesRegex(AssertionError, "Action absent from current overflow"):
            self.editing(ui).reveal("a11y_fit_circuit", description=True, toolbar=True)
        self.assertEqual([], ui.commands)

    def test_explorer_scroll_uses_the_list_viewport_instead_of_the_larger_library(self):
        ui = RecordingUi(EXPLORER)
        self.assertEqual((30, 300, 570, 900), self.editing(ui).scroll_region(
            ui.screen(), False, "a11y_explorer_edit"))

    def test_tool_selector_is_scoped_to_the_open_menu(self):
        menu = EXPLORER.replace('</node></hierarchy>', '''
          <node package="dev.logisim.mobile.e2e" resource-id="editor-tool-menu" scrollable="true" bounds="[100,100][500,600]">
            <node package="dev.logisim.mobile.e2e" text="Circuit explorer" resource-id="menu-explorer" clickable="true" bounds="[110,110][490,160]"/>
          </node></node></hierarchy>''')
        ui = RecordingUi(menu)
        found = self.editing(ui).nodes(ui.screen(), "a11y_explorer_title")
        self.assertEqual(["menu-explorer"], [node.get("resource-id") for node in found])
        self.assertEqual((100, 100, 500, 600), self.editing(ui).scroll_region(
            ui.screen(), False, "a11y_explorer_title"))

    def test_a_visible_label_does_not_make_its_clipped_button_reachable(self):
        clipped = EXPLORER.replace('</node>\n  </node>\n</node></hierarchy>', '''
          <node package="dev.logisim.mobile.e2e" clickable="true" bounds="[40,880][550,940]">
            <node package="dev.logisim.mobile.e2e" text="Edit properties of E2E_NOT" bounds="[50,885][300,895]"/>
          </node></node></node></node></hierarchy>''')
        ui = RecordingUi(clipped)
        self.assertEqual([], self.editing(ui).nodes(ui.screen(), "a11y_explorer_edit", name="E2E_NOT"))

    def test_inspector_fields_exclude_background_editors(self):
        xml = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][600,1000]">
          <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" text="background" bounds="[0,0][100,60]"/>
          <node package="dev.logisim.mobile.e2e" resource-id="editor-inspector-sheet" bounds="[0,400][600,1000]">
            <node package="dev.logisim.mobile.e2e" class="android.widget.EditText" resource-id="property-label" text="E2E_NOT" bounds="[10,450][590,510]"/>
          </node></node></hierarchy>'''
        ui = RecordingUi(xml)
        self.assertEqual("property-label", self.editing(ui).field().get("resource-id"))

    def test_modal_markers_with_local_bounds_keep_global_children_reachable(self):
        for tag in ("editor-inspector-sheet", "editor-tool-sheet", "editor-tool-menu"):
            with self.subTest(tag=tag):
                ui = RecordingUi(LOCAL_INSPECTOR.replace("editor-inspector-sheet", tag))
                editing = self.editing(ui)
                clock = FakeClock()
                with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                        patch("android_editing_scenarios.time.sleep", clock.sleep):
                    self.assertEqual("property-label", editing.wait_node("property_label").get("resource-id"))
                    self.assertEqual("property-label", editing.field().get("resource-id"))
                self.assertEqual((0, 1300, 1080, 2400), editing.scroll_region(ui.screen(), False))

    def test_modal_actions_cannot_climb_to_a_clickable_background_ancestor(self):
        xml = '''<hierarchy><node package="dev.logisim.mobile.e2e" clickable="true" bounds="[0,0][1080,2400]">
          <node package="dev.logisim.mobile.e2e" resource-id="editor-inspector-sheet" bounds="[0,0][1080,1130]">
            <node package="dev.logisim.mobile.e2e" resource-id="inner-label" text="Label" bounds="[63,1587][172,1650]"/>
          </node></node></hierarchy>'''
        ui = RecordingUi(xml)
        self.assertEqual([], self.editing(ui).nodes(ui.screen(), "property_label"))
        ui = RecordingUi(xml.replace('resource-id="inner-label"', 'resource-id="inner-label" clickable="true"'))
        self.assertEqual(["inner-label"], [node.get("resource-id") for node in self.editing(ui).nodes(ui.screen(), "property_label")])

    def test_field_waits_until_all_requested_fields_are_fully_visible(self):
        ui = PollingUi(CLIPPED_INSPECTOR, READY_INSPECTOR)
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            node = self.editing(ui).field(index=2, count=3)
        self.assertEqual("property-y", node.get("resource-id"))
        self.assertEqual("[10,800][590,860]", node.get("bounds"))
        self.assertEqual(0.2, clock.now)
        self.assertEqual([], ui.commands)

    def test_field_keeps_clipped_fields_unreachable_until_the_existing_deadline(self):
        ui = PollingUi(CLIPPED_INSPECTOR)
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            with self.assertRaisesRegex(AssertionError, "Timed out waiting for 3 fully visible editable fields"):
                self.editing(ui).field(index=2, count=3)
        self.assertEqual(20, clock.now)
        self.assertEqual([], ui.commands)

    def test_field_rejects_ambiguous_visible_fields_without_waiting(self):
        ui = PollingUi(READY_INSPECTOR, CLIPPED_INSPECTOR)
        clock = FakeClock()
        with patch("android_editing_scenarios.time.monotonic", clock.monotonic), \
                patch("android_editing_scenarios.time.sleep", clock.sleep):
            with self.assertRaisesRegex(AssertionError, "Expected 2 editable fields, found 3"):
                self.editing(ui).field(count=2)
        self.assertEqual(0, clock.now)

    def test_disappeared_explorer_does_not_scroll_the_background_library(self):
        background = '''<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][600,1000]">
          <node package="dev.logisim.mobile.e2e" scrollable="true" bounds="[0,0][600,950]"/>
        </node></hierarchy>'''
        ui = RecordingUi(EXPLORER, background)
        with self.assertRaisesRegex(AssertionError, "a11y-circuit-list viewport disappeared"):
            self.editing(ui).reveal("a11y_explorer_edit", name="E2E_NOT")
        self.assertEqual(1, len(ui.commands))


if __name__ == "__main__":
    unittest.main()
