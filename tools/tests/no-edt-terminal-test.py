#!/usr/bin/env python3
"""Contract: the EDT plugin does not integrate Eclipse TM Terminal."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "bundles/com.codepilot1c.ui"
CORE = ROOT / "bundles/com.codepilot1c.core"


class NoEdtTerminalTest(unittest.TestCase):
    def test_ui_has_no_terminal_package_requirement(self):
        manifest = (UI / "META-INF/MANIFEST.MF").read_text()
        self.assertNotIn("org.eclipse.tm.terminal", manifest)

    def test_build_target_does_not_request_terminal_feature(self):
        target = ET.parse(ROOT / "targets/default/default.target")
        units = [element.attrib["id"] for element in target.iter("unit")]
        self.assertFalse(any(unit.startswith("org.eclipse.tm.terminal") for unit in units))

    def test_terminal_command_is_not_contributed(self):
        plugin = ET.parse(UI / "plugin.xml")
        attributes = [value for element in plugin.iter() for value in element.attrib.values()]
        self.assertFalse(any("OpenTerminal" in value for value in attributes))
        self.assertFalse((UI / "src/com/codepilot1c/ui/handlers/OpenTerminalHandler.java").exists())

    def test_terminal_preferences_are_not_offered(self):
        preference_files = [
            UI / "src/com/codepilot1c/ui/preferences/VibePreferencePage.java",
            UI / "src/com/codepilot1c/ui/internal/Messages.java",
            UI / "src/com/codepilot1c/ui/internal/messages.properties",
            UI / "src/com/codepilot1c/ui/internal/messages_ru.properties",
            CORE / "src/com/codepilot1c/core/settings/VibePreferenceConstants.java",
            CORE / "src/com/codepilot1c/core/internal/VibePreferenceInitializer.java",
        ]
        for file in preference_files:
            with self.subTest(file=file.name):
                text = file.read_text()
                self.assertNotIn("PREF_TERMINAL_", text)
                self.assertNotIn("PreferencePage_Terminal", text)


if __name__ == "__main__":
    unittest.main()
