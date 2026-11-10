#!/usr/bin/env python3
"""Check the tablet profile gate of the layout12 unit without a device."""

import unittest

from android_layout12_scenarios import tablet_profile

TABLET = ("config: mcc310-mnc260-en-rUS-ldltr-sw800dp-w1280dp-h800dp-xlarge-notlong-notround-nowidecg-lowdr-"
          "land-notnight-xhdpi-finger-keysexposed-nokeys-navhidden-nonav-2560x1600-v35\nabi: arm64-v8a\n")
PHONE = ("config: mcc310-mnc260-en-rUS-ldltr-sw411dp-w411dp-h914dp-normal-long-notround-nowidecg-lowdr-"
         "port-notnight-420dpi-finger-keysexposed-nokeys-navhidden-nonav-1080x2400-v35\nabi: arm64-v8a\n")


class TabletProfileTests(unittest.TestCase):
    def test_a_tablet_build_is_accepted(self):
        self.assertTrue(tablet_profile(["tablet"], ""))

    def test_the_pixel_tablet_emulator_is_accepted_by_its_framework_configuration(self):
        self.assertTrue(tablet_profile(["emulator"], TABLET))

    def test_a_phone_emulator_is_rejected(self):
        self.assertFalse(tablet_profile(["emulator"], PHONE))

    def test_a_wide_normal_bucket_or_a_large_narrow_one_is_rejected(self):
        self.assertFalse(tablet_profile(["emulator"], TABLET.replace("-xlarge-", "-normal-")))
        self.assertFalse(tablet_profile(["emulator"], TABLET.replace("sw800dp", "sw599dp")))

    def test_a_non_emulator_phone_build_is_not_reclassified(self):
        self.assertFalse(tablet_profile(["default"], TABLET))
        self.assertFalse(tablet_profile(["emulator"], "Config unavailable"))


if __name__ == "__main__":
    unittest.main()
