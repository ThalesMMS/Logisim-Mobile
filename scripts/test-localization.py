#!/usr/bin/env python3
"""Exercise catalog validation independently of Gradle and generated Kotlin."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('catalog_generator', Path(__file__).with_name('generate-localization.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CatalogValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original_root = generator.ROOT
        generator.ROOT = Path(self.temp.name)
        self.messages = generator.ROOT / 'localization/messages'
        self.messages.mkdir(parents=True)

    def tearDown(self):
        generator.ROOT = self.original_root
        self.temp.cleanup()

    def write(self, value, name='test.json'):
        (self.messages / name).write_text(json.dumps(value))

    def test_rejects_missing_translation(self):
        self.write({'label': {'en': 'Label'}})
        with self.assertRaisesRegex(ValueError, 'provide text'):
            generator.load_messages()

    def test_additional_translations_keep_named_arguments_and_locale_plural_forms(self):
        self.write({"count": {"en": {"one": "{count} wire", "other": "{count} wires"},
                              "pt": {"one": "{count} fio", "other": "{count} fios"}}})
        directory = generator.ROOT / "localization/translations"
        directory.mkdir()
        path = directory / "ja.json"
        path.write_text(json.dumps({"count": {"other": "配線 {count} 本"}}))
        catalogs = generator.load_translations(generator.load_messages())
        self.assertEqual({"other": "配線 {count} 本"}, catalogs["ja"]["count"])
        path.write_text(json.dumps({"count": {"one": "配線 {count} 本", "other": "配線 {count} 本"}}))
        with self.assertRaisesRegex(ValueError, "expected plurals"):
            generator.load_translations(generator.load_messages())
        path.write_text(json.dumps({"count": {"other": "配線 {total} 本"}}))
        with self.assertRaisesRegex(ValueError, "parameters differ"):
            generator.load_translations(generator.load_messages())

    def test_rejects_translation_parameter_mismatch(self):
        self.write({'label': {'en': 'Open {name}', 'pt': 'Abrir {nome}'}})
        with self.assertRaisesRegex(ValueError, 'parameters differ'):
            generator.load_messages()

    def test_rejects_plural_parameter_mismatch(self):
        self.write({'label': {'en': {'one': '{count} wire', 'other': 'Wires'},
                              'pt': {'one': '{count} fio', 'other': '{count} fios'}}})
        with self.assertRaisesRegex(ValueError, 'Plural parameters differ'):
            generator.load_messages()

    def test_rejects_duplicate_key_from_another_feature(self):
        value = {'label': {'en': 'Label', 'pt': 'Rótulo'}}
        self.write(value)
        self.write(value, 'other.json')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            generator.load_messages()

    def test_preserves_unicode_xml_characters_and_named_parameters(self):
        self.write({'label': {'en': 'CPU & RAM < {size} → {name}', 'pt': 'CPU & RAM < {size} → {name}'}})
        catalog = generator.load_messages()
        xml = generator.generate_xml(catalog, 'pt')
        self.assertIn('CPU &amp; RAM &lt; {size} → {name}', xml)
        self.assertIn('label', generator.generate_kotlin(catalog))

    def test_rejects_unknown_message_in_production_callers(self):
        self.write({'label': {'en': 'Label', 'pt': 'Rótulo'}})
        caller = generator.ROOT / 'composeApp/src/commonMain/kotlin/Panel.kt'
        caller.parent.mkdir(parents=True)
        caller.write_text('import dev.logisim.mobile.core.localization.message\nval title = message("missing_key")\n')
        with self.assertRaisesRegex(ValueError, 'unknown message missing_key'):
            generator.validate_references(generator.load_messages())

    def test_rejects_duplicate_properties_inside_one_catalog(self):
        (self.messages / 'test.json').write_text('{"label":{"en":"Label","pt":"Rótulo"},"label":{"en":"Other","pt":"Outro"}}')
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON property: label'):
            generator.load_messages()

    def test_finds_inline_ui_copy_but_preserves_dynamic_names_symbols_and_units(self):
        source = '\n'.join([
            'Text("Selecionar componentes")',
            'Text(text = "Open project")',
            'contentDescription = "Circuit canvas"',
            'reportError("Could not open the project.")',
            'Text("$label $value")',
            'Text("${state.document.name}")',
            'Text("SAP-1")',
            'Text("10 ns")',
            'Text("→")',
            'Text("PC")',
            'Text(strings(Messages.ActionOpen))',
        ])
        self.assertEqual([(1, 'Selecionar componentes'), (2, 'Open project'),
                          (3, 'Circuit canvas'), (4, 'Could not open the project.')],
                         generator.inline_ui_literals(source))

    def test_accepts_computed_message_keys_without_treating_the_prefix_as_a_key(self):
        self.write({'ios_open': {'en': 'Open', 'pt': 'Abrir'}})
        caller = generator.ROOT / 'composeApp/src/iosMain/kotlin/NativeMessages.kt'
        caller.parent.mkdir(parents=True)
        caller.write_text('import dev.logisim.mobile.core.localization.message\nfun notice(key: String) = message("ios_" + key)\n')
        generator.validate_references(generator.load_messages())

    def test_interpolated_switch_markings_are_data_and_elvis_ui_fallbacks_are_copy(self):
        caption = 'Text("${index + 1}\\n${if (on) "ON" else "OFF"}")'
        self.assertEqual([], generator.inline_ui_literals(caption))
        fallback = 'state.reportError(it.message ?: "Falha na simulação.")'
        self.assertEqual([(1, 'Falha na simulação.')], generator.inline_ui_literals(fallback))
        self.assertEqual([(1, '${component.name} selected')],
                         generator.inline_ui_literals('Text("${component.name} selected")'))


if __name__ == '__main__':
    unittest.main()
