import argparse
import copy
from pathlib import Path
import sys
import unittest

import census


class CensusTest(unittest.TestCase):
    def test_r1_skew_is_search_not_terminal_completion(self):
        r = census.analyze("R1", R1)
        self.assertEqual((16, "Search"), (r["failure"]["step"], r["failure"]["actorBeingQueried"]))
        self.assertEqual(["up"] * 12 + ["down"], [x["direction"] for x in r["actors"]["Search"]["drags"]])
        self.assertEqual(1, len(r["actors"]["Start"]["taps"]))
        self.assertEqual([], r["actors"]["Complete"]["taps"])

    def test_r2_skew_moves_to_components_and_search_has_one_short_drag(self):
        r = census.analyze("R2", R2)
        self.assertEqual((17, "Components"), (r["failure"]["step"], r["failure"]["actorBeingQueried"]))
        self.assertEqual(["up"] * 12 + ["down"], [x["direction"] for x in r["actors"]["Components"]["drags"]])
        drags = r["actors"]["Search"]["drags"]
        self.assertEqual(1, len(drags))
        self.assertEqual(("down", 96), (drags[0]["direction"], drags[0]["end"][1] - drags[0]["start"][1]))
        self.assertEqual(1, len(r["actors"]["Complete"]["taps"]))

    def test_a_query_is_not_promoted_to_a_tap(self):
        altered = copy.deepcopy(R2)
        target = census.analyze("R2", R2)["actors"]["Complete"]["taps"][0]["target"]
        altered["log"] = "\n".join(line for line in altered["log"].splitlines() if f'Tap "{target}"' not in line)
        r = census.analyze("R2", altered)["actors"]["Complete"]
        self.assertTrue(r["queries"])
        self.assertEqual([], r["taps"])

    def test_missing_geometry_stays_unknown(self):
        altered = copy.deepcopy(R2)
        altered["retained"] = {k:v for k,v in altered["retained"].items() if not v["name"].startswith("editor-search-before-")}
        search = census.analyze("R2", altered)["actors"]["Search"]
        self.assertEqual("NOT_MEASURED_BY_RETAINED_FRAME", search["visibility"])
        self.assertEqual("NOT_RETAINED_FOR_THIS_ACTOR", search["viewport"])

    def test_taps_do_not_invent_selected_state_or_full_visibility(self):
        for evidence in (R1, R2):
            r = census.analyze("run", evidence)
            for actor,row in r["actors"].items():
                self.assertEqual("NOT_RECORDED", row["selectedState"])
                if actor != "Search":
                    self.assertEqual("NOT_MEASURED_BY_RETAINED_FRAME", row["visibility"])

    def test_a_missing_early_attachment_does_not_shift_later_geometry(self):
        altered = copy.deepcopy(R2)
        candidates = sorted([(k,v) for k,v in altered["retained"].items()
                             if v["name"].startswith("editor-search-before-text-entry_")],
                            key=lambda x:x[1]["timestamp"])
        del altered["retained"][candidates[0][0]]
        r = census.analyze("R2", altered)
        search = r["actors"]["Search"]
        self.assertEqual("NOT_MEASURED_BY_RETAINED_FRAME", search["visibility"])
        self.assertTrue(r["missingGeometryAttachments"])
        self.assertFalse(any("before-text-entry" in x["name"] for x in search["viewport"]))

    def test_a_missing_final_tree_is_unknown_and_not_called_absent(self):
        altered = copy.deepcopy(R2)
        altered["retained"] = {k:v for k,v in altered["retained"].items() if not v["name"].startswith("native-tree_")}
        r = census.analyze("R2", altered)
        self.assertFalse(r["finalTreeRetained"])
        self.assertIsNone(r["sheetPresentInFinalTree"])
        self.assertIsNone(r["listPresentInFinalTree"])
        rendered = census.markdown({"premise":"p", "scope":"s", "sourcePositions":{},
                                  "runs":[census.analyze("R1",R1),r]})
        self.assertNotIn("Both retained final trees lack", rendered)
        self.assertIn("missing trees remain unknown", rendered)

    def test_unknown_timestamp_does_not_bind_a_geometry_group(self):
        for bad in (None, float("nan"), float("inf")):
            altered = copy.deepcopy(R2)
            item = next(v for v in altered["retained"].values() if v["name"].startswith("editor-search-before-text-entry_"))
            item["timestamp"] = bad
            r = census.analyze("R2", altered)
            self.assertEqual("NOT_MEASURED_BY_RETAINED_FRAME", r["actors"]["Search"]["visibility"])
            self.assertTrue(r["missingGeometryAttachments"])

    def test_report_is_deterministic_and_source_positions_are_pinned(self):
        result = census.census(ARGS.r1, ARGS.r2)
        self.assertEqual(result, census.census(ARGS.r1, ARGS.r2))
        self.assertEqual({"3a0a01f1af05dd3fea75d5a1499c762d039c478a"}, {r["pin"] for r in result["sourcePositions"].values()})
        self.assertTrue(all(not r["sheetPresentInFinalTree"] and not r["listPresentInFinalTree"] for r in result["runs"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--r1", required=True, type=Path)
    parser.add_argument("--r2", required=True, type=Path)
    ARGS, remaining = parser.parse_known_args()
    R1, R2 = census.load_evidence(ARGS.r1), census.load_evidence(ARGS.r2)
    unittest.main(argv=[sys.argv[0], *remaining])
