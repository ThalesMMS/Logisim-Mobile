import hashlib
import unittest

from android_share_delivery_scenario import assert_share_receipt


class ShareDeliveryChecks(unittest.TestCase):
    def setUp(self):
        self.payload = b'<project><circuit name="main"/></project>'
        self.package = 'dev.logisim.mobile.e2e'
        self.received = {'readable': True, 'circuits': 1, 'bytes': len(self.payload),
                         'sha256': hashlib.sha256(self.payload).hexdigest(),
                         'uri': 'content://dev.logisim.mobile.e2e.files/circuits/token/main.circ'}

    def test_exact_temporary_uri_delivery_is_accepted(self):
        assert_share_receipt(self.payload, self.received, self.package, 1)

    def test_unreadable_changed_truncated_or_wrong_route_delivery_is_rejected(self):
        for change in ({'readable': False}, {'circuits': 7}, {'bytes': len(self.payload)-1},
                       {'sha256': '0'*64}, {'uri': 'file:///private/project.circ'}):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                assert_share_receipt(self.payload, dict(self.received, **change), self.package, 1)


if __name__ == '__main__':
    unittest.main()
