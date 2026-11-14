import copy
import json
import unittest

from tools.generate_app_identity import ROOT
from tools.validate_android_listing import validate_listing


class AndroidListingTest(unittest.TestCase):
    def setUp(self):
        self.listing = json.loads((ROOT / "docs/releases/android-listing-draft.json").read_text())

    def test_current_draft_has_valid_text_and_explicit_unresolved_destinations(self):
        pending = validate_listing(self.listing, ROOT)
        self.assertIn("ownerDecisions.channel", pending)
        self.assertNotIn("ownerDecisions.privacyPolicyUrl", pending)
        self.assertIn("privacyReview", pending)
        self.assertIn("licenseReview", pending)

    def test_identity_drift_is_rejected(self):
        self.listing["identity"]["packageName"] = "dev.logisim.mobile"
        with self.assertRaisesRegex(ValueError, "identity differs"):
            validate_listing(self.listing, ROOT)

    def test_unreviewed_draft_cannot_pass_review_gate(self):
        with self.assertRaisesRegex(ValueError, "Listing review is incomplete"):
            validate_listing(self.listing, ROOT, require_reviewed=True)

    def test_removing_pending_fields_does_not_complete_review(self):
        del self.listing["ownerDecisions"]["channel"]
        with self.assertRaisesRegex(ValueError, "fields are missing"):
            validate_listing(self.listing, ROOT, require_reviewed=True)

    def test_description_limit_is_enforced(self):
        for field, length in (("title", 31), ("shortDescription", 81), ("fullDescription", 4001)):
            listing = copy.deepcopy(self.listing)
            listing["listings"]["pt-BR"][field] = "a" * length
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, field):
                validate_listing(listing, ROOT)


if __name__ == "__main__":
    unittest.main()
