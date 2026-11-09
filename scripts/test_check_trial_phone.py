import unittest
import xml.etree.ElementTree as ET
from check_trial_phone import visible_region


class TrialPhoneGeometryTest(unittest.TestCase):
    def test_system_picker_bottom_editor_and_save_button_are_visible(self):
        for bounds, expected in [('[234,2513][855,2659]', [234, 2513, 855, 2659]),
                                 ('[868,2508][1154,2664]', [868, 2508, 1154, 2664])]:
            node = ET.Element('node', {'bounds': bounds, 'enabled': 'true'})
            visible, _ = visible_region(node, {}, 1200, 2664)
            self.assertEqual(expected, visible)

    def test_text_hidden_behind_fixed_toolbar_is_not_clickable(self):
        root = ET.fromstring('<node scrollable="true" bounds="[78,308][1122,2664]" enabled="true"><node bounds="[117,166][330,243]" enabled="true" /></node>')
        self.assertIsNone(visible_region(root[0], {root[0]: root}, 1200, 2664)[0])

    def test_disabled_parent_prevents_clicking_enabled_text_child(self):
        root = ET.fromstring('<node enabled="false"><node bounds="[100,600][400,700]" enabled="true" /></node>')
        self.assertIsNone(visible_region(root[0], {root[0]: root}, 1200, 2664)[0])
