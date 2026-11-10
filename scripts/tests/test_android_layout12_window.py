"""Pure parser/geometry behavior. No ADB, Android process, or device access."""

from copy import deepcopy
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("android_layout12_window", Path(__file__).parents[1] / "android_layout12_window.py")
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)
PACKAGE = "thalesmms.logisim.mobile.e2e"
SIZE = (1080, 2400)

WINDOW = """WINDOW MANAGER (dumpsys window)
  mCurrentFocus=Window{16ab6aa u0 thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity}
  Window #0 Window{16ab6aa u0 thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity}:
    mDisplayId=0 rootTaskId=44
    mOwnerUid=10207 showForAllUsers=false package=thalesmms.logisim.mobile.e2e appop=NONE
    mFullConfiguration={ winConfig={ mBounds=Rect(0, 0 - 1080, 2400) mWindowingMode=fullscreen mActivityType=standard}}
    mHasSurface=true isReadyForDisplay()=true
    Frames: parent=[0,0][1080,2400] display=[0,0][1080,2400] frame=[0,0][1080,2400] last=[0,0][1080,2400]
    isOnScreen=true
    isVisible=true
"""

INSETS = """  Display: mDisplayId=0 (organized)
  WindowInsetsStateController
    InsetsState
      mDisplayFrame=Rect(0, 0 - 1080, 2400)
        InsetsSource id=1 type=statusBars frame=[0,0][1080,136] visible=true flags= sideHint=TOP
        InsetsSource id=2 type=navigationBars frame=[0,2337][1080,2400] visible=true flags= sideHint=BOTTOM
        InsetsSource id=3 type=displayCutout frame=[480,0][625,136] visible=true flags= sideHint=TOP
        InsetsSource id=4 type=ime frame=[0,0][0,0] visible=false flags= sideHint=NONE
    Control map:
  InputConsumers:
"""


class FocusedWindowTests(unittest.TestCase):
    def observed(self, window=WINDOW, insets=INSETS):
        return contract.parse_window_observation(window, PACKAGE, SIZE, insets)

    def test_focused_window_and_explicit_primary_display_insets(self):
        value = self.observed()
        self.assertEqual("16ab6aa", value["windowToken"])
        self.assertEqual([0, 0, 1080, 2400], value["appFrame"])
        self.assertEqual("fullscreen", value["windowingMode"])
        self.assertEqual("AVAILABLE", value["insetsStatus"])
        self.assertEqual("activity-activities", value["insetsSource"])
        self.assertEqual(["statusBars", "navigationBars", "displayCutout"], [entry["type"] for entry in value["occlusions"]])

    def test_full_window_dump_supplies_its_own_display_insets(self):
        value = self.observed(WINDOW + INSETS, "malformed unused fallback")
        self.assertEqual("window", value["insetsSource"])

    def test_control_map_source_references_do_not_extend_the_insets_state(self):
        insets = INSETS.replace("    Control map:\n", "    Control map:\n        mSource=InsetsSource id=1 type=statusBars frame=[0,0][1080,136] visible=true flags=\n")
        self.assertEqual("AVAILABLE", self.observed(insets=insets)["insetsStatus"])

    def test_missing_focus_cannot_select_the_only_app_window(self):
        with self.assertRaisesRegex(contract.WindowObservationError, "focus"):
            self.observed(WINDOW.replace("mCurrentFocus=", "oldFocus="))

    def test_foreign_current_focus_does_not_fall_back_to_background_app(self):
        foreign = WINDOW.replace("mCurrentFocus=Window{16ab6aa u0 " + PACKAGE,
                                 "mCurrentFocus=Window{9911 u0 com.android.documentsui", 1)
        with self.assertRaisesRegex(contract.WindowObservationError, "another application"):
            self.observed(foreign)

    POPUP = """  Window #1 Window{122dd65 u0 Pop-Up Window}:
    mDisplayId=0 rootTaskId=44
    mOwnerUid=10207 showForAllUsers=false package=OWNER appop=NONE
    mAttrs={(46,126)(220x304) gr=TOP START CENTER ty=APPLICATION_SUB_PANEL fmt=TRANSLUCENT
    mParentWindow=Window{16ab6aa u0 thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity} mLayoutAttached=true
"""

    def popup(self, owner=PACKAGE, parent="16ab6aa u0 " + PACKAGE + "/dev.logisim.mobile.MainActivity"):
        focus = WINDOW.replace("mCurrentFocus=Window{16ab6aa u0 " + PACKAGE + "/dev.logisim.mobile.MainActivity}",
                               "mCurrentFocus=Window{122dd65 u0 Pop-Up Window}", 1)
        return focus + self.POPUP.replace("OWNER", owner).replace(
            "16ab6aa u0 " + PACKAGE + "/dev.logisim.mobile.MainActivity", parent)

    def test_focused_app_popup_observes_its_activity_window(self):
        value = self.observed(self.popup())
        self.assertEqual("16ab6aa", value["windowToken"])
        self.assertEqual({"token": "122dd65", "title": "Pop-Up Window"}, value["focusedSubWindow"])
        self.assertEqual([0, 0, 1080, 2400], value["appFrame"])

    def test_popup_of_another_owner_or_parent_is_foreign(self):
        for window in (self.popup(owner="com.android.systemui"),
                       self.popup(parent="16ab6aa u0 com.android.documentsui/.Picker")):
            with self.subTest(window=window[-200:]):
                with self.assertRaisesRegex(contract.WindowObservationError, "another application"):
                    self.observed(window)

    def test_package_prefix_is_not_package_identity(self):
        foreign = WINDOW.replace(PACKAGE + "/", PACKAGE + ".other/", 1)
        with self.assertRaisesRegex(contract.WindowObservationError, "another application"):
            self.observed(foreign)

    def test_two_current_focus_lines_are_ambiguous_even_if_they_agree(self):
        with self.assertRaisesRegex(contract.WindowObservationError, "unique.*focus"):
            self.observed(WINDOW + "  mCurrentFocus=Window{16ab6aa u0 " + PACKAGE + "/dev.logisim.mobile.MainActivity}\n")

    def test_duplicate_window_token_is_rejected(self):
        with self.assertRaisesRegex(contract.WindowObservationError, "unique window block"):
            self.observed(WINDOW + WINDOW[WINDOW.index("  Window #0"):])

    def test_focused_token_requires_the_same_owner_package(self):
        with self.assertRaisesRegex(contract.WindowObservationError, "owner"):
            self.observed(WINDOW.replace("package=" + PACKAGE, "package=another.app"))

    def test_each_stale_visibility_signal_is_rejected(self):
        for before, after in (("mHasSurface=true", "mHasSurface=false"),
                              ("isOnScreen=true", "isOnScreen=false"),
                              ("isVisible=true", "isVisible=false")):
            with self.subTest(signal=before), self.assertRaisesRegex(contract.WindowObservationError, "stale"):
                self.observed(WINDOW.replace(before, after))

    def test_contradictory_visibility_is_not_satisfied_by_one_true_value(self):
        for signal in ("mHasSurface", "isOnScreen", "isVisible"):
            with self.subTest(signal=signal), self.assertRaisesRegex(contract.WindowObservationError, "unique visibility"):
                self.observed(WINDOW + "    " + signal + "=false\n")

    def test_negative_or_zero_app_frame_is_rejected(self):
        for frame in ("[-10,0][1080,2400]", "[0,0][0,2400]"):
            with self.subTest(frame=frame), self.assertRaises(contract.WindowObservationError):
                self.observed(WINDOW.replace(" frame=[0,0][1080,2400]", " frame=" + frame))

    def test_display_must_agree_with_screenshot_and_primary_identity(self):
        for change in (lambda text: text.replace("display=[0,0][1080,2400]", "display=[0,0][720,1280]"),
                       lambda text: text.replace("mDisplayId=0", "mDisplayId=1")):
            with self.assertRaises(contract.WindowObservationError):
                self.observed(change(WINDOW))

    def test_windowing_mode_is_observed_without_claiming_split(self):
        resized = WINDOW.replace("mWindowingMode=fullscreen", "mWindowingMode=multi-window").replace(
            " frame=[0,0][1080,2400]", " frame=[0,0][530,2400]")
        observed = self.observed(resized)
        self.assertEqual("multi-window", observed["windowingMode"])
        self.assertEqual([0, 0, 530, 2400], observed["appFrame"])
        self.assertNotIn("splitScreen", observed)

    def test_missing_or_unscoped_insets_remain_unavailable(self):
        for insets in ("", INSETS.replace("  Display: mDisplayId=0 (organized)\n", ""),
                       INSETS.replace("mDisplayId=0", "mDisplayId=1")):
            with self.subTest(insets=insets[:40]):
                observed = self.observed(insets=insets)
                self.assertEqual("NOT_AVAILABLE", observed["insetsStatus"])
                with self.assertRaisesRegex(contract.WindowObservationError, "inset evidence"):
                    contract.assert_control_rect((100, 200, 300, 400), observed, 160)

    def test_missing_source_type_is_unavailable_not_inferred_zero(self):
        observed = self.observed(insets=INSETS.replace("type=ime", "type=systemGestures"))
        self.assertEqual("NOT_AVAILABLE", observed["insetsStatus"])
        self.assertIn("ime", observed["insetsReason"])

    NO_CUTOUT = ("      mDisplayCutout=DisplayCutout{insets=Rect(0, 0 - 0, 0) waterfall=Insets{left=0, top=0, right=0, "
                 "bottom=0} boundingRect={Bounds=[Rect(0, 0 - 0, 0), Rect(0, 0 - 0, 0), Rect(0, 0 - 0, 0), "
                 "Rect(0, 0 - 0, 0)]} cutoutPathParserInfo={} sideOverrides=null}\n")

    def without_cutout_source(self, cutout_line):
        insets = INSETS.replace("        InsetsSource id=3 type=displayCutout frame=[480,0][625,136] visible=true flags= sideHint=TOP\n", "")
        return insets.replace("      mDisplayFrame=Rect(0, 0 - 1080, 2400)\n",
                              "      mDisplayFrame=Rect(0, 0 - 1080, 2400)\n" + cutout_line)

    def test_an_explicitly_empty_cutout_satisfies_the_cutout_evidence(self):
        value = self.observed(insets=self.without_cutout_source(self.NO_CUTOUT))
        self.assertEqual("AVAILABLE", value["insetsStatus"])
        self.assertEqual(["statusBars", "navigationBars"], [entry["type"] for entry in value["occlusions"]])

    def test_a_nonempty_or_repeated_cutout_without_its_source_stays_unavailable(self):
        notch = self.NO_CUTOUT.replace("insets=Rect(0, 0 - 0, 0)", "insets=Rect(0, 136 - 0, 0)", 1)
        for line in (notch, self.NO_CUTOUT * 2, ""):
            with self.subTest(line=line[:60]):
                self.assertEqual("NOT_AVAILABLE", self.observed(insets=self.without_cutout_source(line))["insetsStatus"])

    def test_malformed_insets_do_not_fall_back_to_other_dump(self):
        broken = WINDOW + INSETS.replace("visible=true", "visible=unknown", 1)
        with self.assertRaisesRegex(contract.WindowObservationError, "Malformed"):
            self.observed(broken, INSETS)

    def test_mismatching_inset_display_and_duplicate_controller_are_rejected(self):
        for insets in (INSETS.replace("mDisplayFrame=Rect(0, 0 - 1080, 2400)", "mDisplayFrame=Rect(0, 0 - 720, 1280)"),
                       INSETS + INSETS):
            with self.assertRaises(contract.WindowObservationError):
                self.observed(insets=insets)

    def test_duplicate_source_and_visible_empty_or_outside_source_rejected(self):
        for insets in (INSETS.replace("id=2", "id=1"),
                       INSETS.replace("frame=[0,0][0,0] visible=false", "frame=[0,0][0,0] visible=true"),
                       INSETS.replace("frame=[0,0][1080,136]", "frame=[-1,0][1080,136]")):
            with self.subTest(insets=insets[-150:]), self.assertRaises(contract.WindowObservationError):
                self.observed(insets=insets)

    def test_ime_visible_frame_is_used_only_inside_its_source_frame(self):
        ime = INSETS.replace("frame=[0,0][0,0] visible=false", "frame=[0,1500][1080,2400] visibleFrame=[0,1700][1080,2400] visible=true")
        observed = self.observed(insets=ime)
        self.assertEqual([0, 1700, 1080, 2400], observed["occlusions"][-1]["frame"])
        with self.assertRaisesRegex(contract.WindowObservationError, "outside its source"):
            self.observed(insets=ime.replace("visibleFrame=[0,1700]", "visibleFrame=[0,1400]"))


class ControlGeometryTests(unittest.TestCase):
    def setUp(self):
        self.observed = contract.parse_window_observation(WINDOW, PACKAGE, SIZE, INSETS)

    def test_exact_48dp_target_avoiding_all_occlusions(self):
        result = contract.assert_control_rect((100, 200, 226, 326), self.observed, 420)
        self.assertEqual([48, 48], result["sizeDp"])
        self.assertEqual([100, 200, 226, 326], result["frame"])

    def test_full_target_is_required_not_48dp_intersection(self):
        for frame in ((-1, 200, 200, 400), (100, 200, 225, 326), (100, 200, 226, 325)):
            with self.subTest(frame=frame), self.assertRaises(contract.WindowObservationError):
                contract.assert_control_rect(frame, self.observed, 420)

    def test_status_navigation_cutout_and_ime_overlap_rejected(self):
        for frame in ((0, 100, 200, 300), (100, 2200, 300, 2400), (480, 100, 700, 300)):
            with self.subTest(frame=frame), self.assertRaisesRegex(contract.WindowObservationError, "overlaps"):
                contract.assert_control_rect(frame, self.observed, 160)
        observed = deepcopy(self.observed)
        observed["occlusions"].append({"type": "ime", "frame": [0, 1700, 1080, 2400]})
        with self.assertRaisesRegex(contract.WindowObservationError, "ime"):
            contract.assert_control_rect((100, 1650, 300, 1850), observed, 160)

    def test_adjoining_safe_edge_is_not_overlap(self):
        result = contract.assert_control_rect((0, 136, 126, 262), self.observed, 420)
        self.assertEqual([48, 48], result["sizeDp"])

    def test_malformed_target_and_density_are_rejected(self):
        for frame in ((1.5, 200, 200, 400), (False, 200, 200, 400), (100, 200, 99, 400)):
            with self.subTest(frame=frame), self.assertRaises(contract.WindowObservationError):
                contract.assert_control_rect(frame, self.observed, 160)
        for density in (0, -1, float("nan"), float("inf"), True):
            with self.subTest(density=density), self.assertRaises(ValueError):
                contract.assert_control_rect((100, 200, 300, 400), self.observed, density)


if __name__ == "__main__":
    unittest.main()
