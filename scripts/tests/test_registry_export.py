import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("registry_export", ROOT / "scripts/export-registry.py")
EXPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORT)


class RegistryExportTests(unittest.TestCase):
    def test_hash_mismatch_rejects_the_copied_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination = Path(temporary) / "input.jar", Path(temporary) / "copied.jar"
            source.write_bytes(b"untrusted replacement")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                EXPORT.verified_copy(source, destination, "0" * 64)

    def test_verified_copy_remains_stable_when_the_producer_rebuilds(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination = Path(temporary) / "input.jar", Path(temporary) / "copied.jar"
            source.write_bytes(b"verified artifact")
            expected = hashlib.sha256(source.read_bytes()).hexdigest()
            EXPORT.verified_copy(source, destination, expected)
            source.write_bytes(b"next build")
            self.assertEqual(destination.read_bytes(), b"verified artifact")
            self.assertEqual(EXPORT.sha256(destination), expected)

    def test_invalid_expected_hash_does_not_copy_the_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, destination = Path(temporary) / "input.jar", Path(temporary) / "copied.jar"
            source.write_bytes(b"artifact")
            with self.assertRaisesRegex(ValueError, "64 lowercase"):
                EXPORT.verified_copy(source, destination, "bad")
            self.assertFalse(destination.exists())

    def test_check_rejects_changed_metadata_without_rewriting_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "snapshot.json"
            EXPORT.write_or_check({"definitions": [{"kind": "AND"}]}, output, False)
            original = output.read_bytes()
            EXPORT.write_or_check({"definitions": [{"kind": "AND"}]}, output, True)
            with self.assertRaisesRegex(ValueError, "snapshot is stale"):
                EXPORT.write_or_check({"definitions": [{"kind": "OR"}]}, output, True)
            self.assertEqual(output.read_bytes(), original)

    def test_sources_are_bound_to_the_declared_revision(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = root / EXPORT.CORE_SOURCES
            sources.mkdir(parents=True)
            for name in EXPORT.STRUCTURAL_SOURCES:
                (sources / name).write_text("// structural source\n")
            (sources / "DeviceFamily.kt").write_text("object DeviceFamily\n")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                            "commit", "-qm", "fixture"], cwd=root, check=True)
            revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            snapshot = {"definitions": [{"metadataOwners": ["dev.logisim.mobile.core.DeviceFamily"]}],
                        "kindAliases": [{"symbol": "DeviceFamily.Device", "kind": "DEVICE"}]}
            bindings = EXPORT.registry_sources(snapshot, root, revision)
            self.assertEqual(len(bindings), 4)
            binding = next(value for value in bindings if value["path"].endswith("DeviceFamily.kt"))
            self.assertEqual(binding["sha256"], hashlib.sha256(b"object DeviceFamily\n").hexdigest())
            (sources / "DeviceFamily.kt").write_text("object DeviceFamily // changed\n")
            with self.assertRaisesRegex(ValueError, "differs from declared revision"):
                EXPORT.registry_sources(snapshot, root, revision)

    def test_unbound_metadata_owner_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / EXPORT.CORE_SOURCES).mkdir(parents=True)
            snapshot = {"definitions": [{"metadataOwners": ["unreviewed.Plugin"]}], "kindAliases": []}
            with self.assertRaisesRegex(ValueError, "Cannot bind metadata owners.*Plugin"):
                EXPORT.registry_sources(snapshot, root, "unused")


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java is needed to exercise the registry bridge")
class RegistryBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="registry-bridge-test-")
        cls.directory = Path(cls.temporary.name)
        package = cls.directory / "dev/logisim/mobile/core"
        package.mkdir(parents=True)
        (package / "ComponentKind.java").write_text("""
package dev.logisim.mobile.core;
public class ComponentKind {
  public static final ComponentKind AND = new ComponentKind("AND");
  public static final Companion Companion = new Companion();
  private final String name;
  public ComponentKind(String name) { this.name = name; }
  public String getName() { return name; }
  public String toString() { return "ComponentKind(name=" + name + ")"; }
  public static class Companion {
    public ComponentKind getCustom() { return new ComponentKind("CUSTOM"); }
  }
}
""")
        (package / "ComponentCatalog.java").write_text(r'''
package dev.logisim.mobile.core;
import java.util.*;
public class ComponentCatalog {
  public static final ComponentCatalog INSTANCE = new ComponentCatalog();
  public Registry getRegistry() { return new Registry(); }
  public static class Registry {
    public List<Definition> getDefinitions() { return List.of(new Definition("Z"), new Definition("A")); }
  }
  public static class Definition {
    private final String kind;
    public Definition(String kind) { this.kind = kind; }
    public ComponentKind getKind() { return new ComponentKind(kind); }
    public String getFileName() { return "Quoted \"name\"\\path\n\t é 😀"; }
    public String getLibrary() { return "#Fixture"; }
    public Object getBehavior() { return kind.equals("A") ? (Runnable) () -> {} : null; }
    public boolean isCombinational() { return kind.equals("A"); }
    public boolean getSupportsRotation() { return false; }
    public Map<String, String> getDefaultAttributes() { return Map.of("z", "2", "a", "1"); }
    public Schema getSchema() { return new Schema(); }
    public Object getLayout() { return (Runnable) () -> {}; }
    public Object getPullDrivers() { return null; }
    public Object getMatchesFileAttributes() { return null; }
    public Object getDefaultsForVersion() { return null; }
    public Object getCopyAttributes() { return null; }
    public Object getReconfigureAttributes() { return null; }
  }
  public static class Schema {
    public Map<String, String> getDefaults() { return Map.of("z", "2", "a", "1"); }
    public List<Key> getKeys() { return List.of(new Key("z"), new Key("a")); }
  }
  public static class Key {
    private final String name;
    public Key(String name) { this.name = name; }
    public String getName() { return name; }
  }
}
''')
        families = ("WiringLibrary", "GatesLibrary", "PlexersLibrary", "MemoryComponents", "SequentialLibrary",
                    "IoKinds", "ExtraIoKinds", "ExternalIoComponents", "AnnotationLibrary", "SocComponents",
                    "TclComponents", "HdlIpLibrary")
        for family in families:
            (package / (family + ".java")).write_text(f"""
package dev.logisim.mobile.core;
public class {family} {{
  public static final {family} INSTANCE = new {family}();
  public ComponentKind getButton() {{ return new ComponentKind("BUTTON"); }}
  public String getDangerous() {{ throw new AssertionError("Do not invoke unrelated getters"); }}
  public ComponentKind evaluate(String input) {{ throw new AssertionError("Do not invoke operations"); }}
}}
""")
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(cls.directory),
                        str(ROOT / "scripts/RegistryInventory.java"), *map(str, package.glob("*.java"))],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def bridge(self):
        return subprocess.check_output(["java", "-cp", str(self.directory), "RegistryInventory"], text=True)

    def test_bridge_reads_names_aliases_and_unicode_metadata(self):
        snapshot = json.loads(self.bridge())
        self.assertEqual([entry["kind"] for entry in snapshot["definitions"]], ["A", "Z"])
        first, second = snapshot["definitions"]
        self.assertEqual(first["fileName"], 'Quoted "name"\\path\n\t é 😀')
        self.assertTrue(first["hasBehavior"])
        self.assertFalse(second["hasBehavior"])
        self.assertEqual(first["schemaKeys"], ["a", "z"])
        self.assertEqual(first["defaultAttributes"], {"a": "1", "z": "2"})
        self.assertEqual(first["metadataOwners"], ["dev.logisim.mobile.core.ComponentCatalog"])
        aliases = {entry["symbol"]: entry["kind"] for entry in snapshot["kindAliases"]}
        self.assertEqual(aliases["ComponentKind.AND"], "AND")
        self.assertEqual(aliases["ComponentKind.Custom"], "CUSTOM")
        self.assertEqual(aliases["IoKinds.Button"], "BUTTON")

    def test_bridge_output_is_identical_in_separate_java_processes(self):
        self.assertEqual(self.bridge(), self.bridge())


if __name__ == "__main__":
    unittest.main()
