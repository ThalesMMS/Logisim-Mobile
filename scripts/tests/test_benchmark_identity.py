"""Exercise the CLI with fake Gradle, Git and Java; no JVM or Gradle is executed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class BenchmarkIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="benchmark-identity-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.script = self.root / "scripts/benchmark-circuits.py"
        self.script.parent.mkdir()
        shutil.copyfile(Path(__file__).resolve().parents[1] / self.script.name, self.script)
        self.profile = self.root / "scripts/performance/benchmark-profile.jfc"
        self.profile.parent.mkdir()
        shutil.copyfile(Path(__file__).resolve().parents[1] / "performance/benchmark-profile.jfc", self.profile)
        self.source = self.root / "core/src/commonMain/Example.kt"
        sources = [self.source, self.root / "core/src/commonTest/PerformanceCircuits.kt",
                   *[self.root / f"core/src/jvmTest/{name}.kt" for name in
                     ("CircuitPerformanceBenchmark", "SpatialIndexBenchmark", "PerformanceCorpusExport", "PerformanceCorpusReplay")],
                   self.root / "core/build.gradle.kts", self.root / "gradle.properties"]
        for path in sources:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture source")
        (self.root / "build.gradle.kts").write_text('kotlin("multiplatform") version "2.4.21"')
        self.head = self.root / "fake-head.txt"
        self.head.write_text("a" * 40)
        self.binary = self.root / "core/build/classes/kotlin/jvm/main/Example.class"
        self.stdlib = self.root / "cache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/fixture/stdlib.jar"
        self.stdlib.parent.mkdir(parents=True)
        self.stdlib.write_bytes(b"fixture stdlib")
        self.output = self.root / "result.json"
        self.java_log = self.root / "java-observed.json"
        tools = self.root / "tools"
        self.java = tools / "bin/java"
        self.java.parent.mkdir(parents=True)
        self.executable(self.root / "gradlew", '''
from pathlib import Path
import os
root=Path.cwd()
for name,data in {'main/Example.class':b'fixture bytecode', 'test/Benchmark.class':b'fixture test',
                  'main/META-INF/core.kotlin_module':b'fixture metadata'}.items():
    p=root/'core/build/classes/kotlin/jvm'/name
    p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
with (root/'gradle-calls.txt').open('a') as stream:stream.write('build\\n')
change=os.environ.get('BUILD_CHANGE')
if change=='source':(root/'core/src/commonMain/Example.kt').write_text('changed during build')
if change=='revision':(root/'fake-head.txt').write_text('b'*40)
if change=='added-source':(root/'core/src/commonMain/Added.kt').write_text('new during build')
''')
        self.executable(tools / "git", '''
from pathlib import Path
import sys
print((Path.cwd()/'fake-head.txt').read_text() if sys.argv[1]=='rev-parse' else '',end='')
''')
        self.executable(self.java, '''
from pathlib import Path
import hashlib,json,os,shutil,sys,xml.etree.ElementTree as ET
root=Path.cwd()
controls=json.loads((root/'fake-controls.json').read_text())
if sys.argv[1:] == ['--version']:
    (root/'java-version-environment.json').write_text(json.dumps(dict(os.environ)))
    if controls.get('VERSION_CHANGE')=='class':
        (root/'core/build/classes/kotlin/jvm/main/Example.class').write_bytes(b'changed before copy')
    if controls.get('VERSION_CHANGE')=='stdlib':
        Path(controls['FAKE_STDLIB']).write_bytes(b'changed before copy')
    print('fake-java 21 fixture');sys.exit(0)
cp=[Path(p) for p in sys.argv[sys.argv.index('-cp')+1].split(os.pathsep)]
change=controls.get('RUN_CHANGE')
original=root/'core/build/classes/kotlin/jvm/main/Example.class'
if change=='class':original.write_bytes(b'rebuilt during measurement')
if change=='added-class':original.with_name('Added.class').write_bytes(b'new class')
if change=='deleted-class':original.unlink()
if change=='resource':(original.parent/'META-INF/core.kotlin_module').write_bytes(b'changed metadata')
if change=='source':(root/'core/src/commonMain/Example.kt').write_text('changed during measurement')
if change=='added-source':(root/'core/src/commonMain/Added.kt').write_text('new source')
if change=='deleted-source':(root/'core/src/commonMain/Example.kt').unlink()
if change=='revision':(root/'fake-head.txt').write_text('b'*40)
if change=='runner':(root/'scripts/benchmark-circuits.py').write_text('# replacement runner\\n')
if change=='profile':(root/'scripts/performance/benchmark-profile.jfc').write_text('<changed/>')
if change=='stdlib':Path(controls['FAKE_STDLIB']).write_bytes(b'replaced stdlib')
if change=='java':Path(__file__).write_text(Path(__file__).read_text()+'\\n# replaced launcher\\n')
if change=='copied-class':(cp[0]/'Example.class').write_bytes(b'mutated frozen bytecode')
if change=='copied-stdlib':cp[2].write_bytes(b'mutated frozen stdlib')
observed={'argv':sys.argv,'classpath':[str(p) for p in cp],
          'main':(cp[0]/'Example.class').read_text(),'test':(cp[1]/'Benchmark.class').read_text(),
          'stdlib':cp[2].read_text(),'resource':(cp[0]/'META-INF/core.kotlin_module').read_text(),
          'environment':dict(os.environ)}
(root/'java-observed.json').write_text(json.dumps(observed))
i=next(i for i,a in enumerate(sys.argv) if a.startswith('dev.logisim.mobile.core.'))
output=Path(sys.argv[i+1])
if sys.argv[i].endswith('PerformanceCorpusReplay'):
    manifest,sap=map(Path,sys.argv[i+2:i+4])
    inputs={p.name:p.read_bytes().hex() for p in manifest.parent.iterdir()}
    inputs['sap1.circ']=sap.read_bytes().hex()
    observed['replay_inputs']={'manifest':str(manifest),'sap':str(sap),'bytes':inputs}
    if change and change.startswith('replay-'):
        original=change.startswith('replay-original-')
        source=Path(controls['FAKE_REPLAY_MANIFEST']) if original else manifest
        target=Path(controls['FAKE_REPLAY_SAP']) if original else sap
        if change.endswith('-xml'):(source.parent/'independent-16.circ').write_bytes(b'changed literal input')
        if change.endswith('-manifest'):source.write_bytes(source.read_bytes()+b' ')
        if change.endswith('-sap'):target.write_bytes(b'changed SAP input')
        if change.endswith('-extra') and not change.endswith('-root-extra'):(source.parent/'extra.circ').write_bytes(b'unexpected input')
        if change.endswith('-removed'):(source.parent/'independent-16.circ').unlink()
        if change.endswith('-root-extra'):(manifest.parent.parent/'extra-input').write_bytes(b'unexpected input')
        if change.endswith('-directory-link'):
            shutil.rmtree(manifest.parent)
            manifest.parent.symlink_to(Path(controls['FAKE_REPLAY_MANIFEST']).parent,target_is_directory=True)
        if change in ('replay-copied-inputs-symlink', 'replay-copied-corpus-symlink'):
            linked=manifest.parent.parent if change=='replay-copied-inputs-symlink' else manifest.parent
            retained=manifest.parent.parent.parent/('retained-'+linked.name)
            linked.rename(retained)
            linked.symlink_to(retained,target_is_directory=True)
        if change.endswith('-hardlink'):
            sap.unlink();sap.hardlink_to(Path(controls['FAKE_REPLAY_SAP']))
    output.parent.mkdir(parents=True,exist_ok=True)
    cases=[{key:case[key] for key in ('file','sha256','import_status','verified_outputs')}
           for case in json.loads(manifest.read_text())['cases']]
    data={'schema':1,'scope':'functional_replay','benchmark_executed':False,'accepted':8,'rejected':2,
          'cases':cases,'sap_ticks':160,'sap_display_hex':'08','metadata_reference_limit':'fixture derived-import limit'}
    if change=='replay-receipt-incomplete':data={'scope':'functional_replay','benchmark_executed':False}
    if change=='replay-receipt-counts':data.update(accepted=7,rejected=3)
    if change=='replay-receipt-schema':data['schema']=2
    if change=='replay-receipt-case-status':data['cases'][0]['import_status']='rejected'
    if change=='replay-receipt-case-hash':data['cases'][0]['sha256']='0'*64
    if change=='replay-receipt-case-output':data['cases'][0]['verified_outputs']=0
    if change=='replay-receipt-extra-case':data['cases'].append(data['cases'][0])
    if change=='replay-receipt-sap-ticks':data['sap_ticks']=159
    if change=='replay-receipt-sap-display':data['sap_display_hex']='00'
    if change=='replay-benchmark-payload':output.with_suffix('.csv').write_bytes(b'not a replay receipt')
elif sys.argv[i].endswith('PerformanceCorpusExport'):
    assert not output.exists(), 'corpus directory must not exist'
    output.mkdir(parents=True)
    xml=b'<project keep="literal&amp;bytes"><circuit name="main"/></project>\\n'
    (output/'fixture.circ').write_bytes(xml)
    output=output/'manifest.json'
    data={'schema':1,'cases':[{'file':'fixture.circ','sha256':hashlib.sha256(xml).hexdigest()}]}
else:
    output.parent.mkdir(parents=True,exist_ok=True)
    output.with_suffix('.csv').write_bytes(b'fixture csv\\n')
    data={'schema':1,'results':[]}
output.write_text(json.dumps(data))
for argument in sys.argv:
    if argument.startswith('-XX:StartFlightRecording=') and not controls.get('OMIT_JFR'):
        profile=Path(argument.split('settings=',1)[1].split(',',1)[0])
        settings={event.get('name'):{item.get('name'):item.text for item in event.findall('setting')}
                  for event in ET.fromstring(profile.read_text()).findall('event')}
        observed['jfr_profile']={'path':str(profile),'sha256':hashlib.sha256(profile.read_bytes()).hexdigest(),
                                 'settings':settings}
        if change=='copied-profile':profile.write_text('<changed/>')
        target=Path(argument.split('filename=',1)[1].split(',',1)[0]);target.write_bytes(b'fixture jfr')
(root/'java-observed.json').write_text(json.dumps(observed))
if controls.get('FAIL_JAVA'):sys.exit(7)
''')
        self.environment = dict(os.environ, JAVA_HOME=str(tools), GRADLE_USER_HOME=str(self.root / "cache"),
                                PATH=str(tools) + os.pathsep + os.environ.get("PATH", ""), FAKE_STDLIB=str(self.stdlib))

    def executable(self, path, body):
        path.write_text(f"#!{sys.executable}\n" + body)
        path.chmod(0o755)

    def run_cli(self, *arguments, environment=None):
        merged = self.environment | (environment or {})
        controls = {name: merged[name] for name in ("VERSION_CHANGE", "RUN_CHANGE", "OMIT_JFR", "FAIL_JAVA", "FAKE_STDLIB",
                                                   "FAKE_REPLAY_MANIFEST", "FAKE_REPLAY_SAP")
                    if name in merged}
        (self.root / "fake-controls.json").write_text(json.dumps(controls))
        workload_arguments = [] if "replay" in arguments else ["--sizes", "16", "--iterations", "3", "--warmups", "0"]
        return subprocess.run([sys.executable, str(self.script), *workload_arguments, "--output", str(self.output), *arguments],
                              env=merged, text=True, capture_output=True)

    def prepare_replay_inputs(self):
        """Use inert fixture bytes. This never exports or regenerates a circuit XML."""
        directory = self.root / "historical-corpus"
        shutil.rmtree(directory, ignore_errors=True)
        directory.mkdir()
        cases = []
        for kind in ("independent", "hierarchical"):
            for banks in (16, 64, 256, 384, 1024):
                name = f"{kind}-{banks}.circ"
                content = f"literal fixture bytes for {name}\\n&keep;".encode()
                (directory / name).write_bytes(content)
                cases.append({"file": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(),
                              "import_status": "rejected" if banks == 1024 else "accepted",
                              "verified_outputs": 0 if banks == 1024 else banks})
        self.replay_manifest = directory / "manifest.json"
        self.replay_manifest.write_text(json.dumps({"cases": cases}, indent=2) + "\n")
        self.replay_sap = self.root / "historical-sap1.circ"
        self.replay_sap.write_bytes(b"literal SAP-1 fixture bytes\\n&keep;")
        self.replay_originals = {p.name: p.read_bytes() for p in directory.iterdir()} | {"sap1.circ": self.replay_sap.read_bytes()}
        template = getattr(self, "replay_runner_template", self.script.read_text())
        self.replay_runner_template = template
        self.script.write_text(template.replace(
            'REPLAY_MANIFEST_SHA256 = "295571568422db33a280e9c0a52b322982cfa7f57c10e297fd70a788d7596f58"',
            f'REPLAY_MANIFEST_SHA256 = "{hashlib.sha256(self.replay_manifest.read_bytes()).hexdigest()}"').replace(
            'REPLAY_SAP_SHA256 = "2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac"',
            f'REPLAY_SAP_SHA256 = "{hashlib.sha256(self.replay_sap.read_bytes()).hexdigest()}"').replace(
            'REPLAY_SAP_BYTES = 47_528', f'REPLAY_SAP_BYTES = {self.replay_sap.stat().st_size}'))
        self.environment.update(FAKE_REPLAY_MANIFEST=str(self.replay_manifest), FAKE_REPLAY_SAP=str(self.replay_sap))

    def run_replay(self, *arguments, environment=None):
        return self.run_cli("--workload", "replay", "--input-manifest", str(self.replay_manifest),
                            "--sap-input", str(self.replay_sap), *arguments, environment=environment)

    def seed_previous_result(self):
        self.output.write_bytes(b"previous result")
        self.output.with_suffix(".csv").write_bytes(b"previous csv")

    def assert_previous_result(self):
        self.assertEqual(self.output.read_bytes(), b"previous result")
        self.assertEqual(self.output.with_suffix(".csv").read_bytes(), b"previous csv")

    def test_success_records_frozen_classpath_runtime_and_payload_identities(self):
        jfr = self.root / "recording.jfr"
        built = self.run_cli("--jfr", str(jfr))
        self.assertEqual(0, built.returncode, built.stderr)
        result = json.loads(self.output.read_text())
        observed = json.loads(self.java_log.read_text())
        self.assertEqual(["fixture bytecode", "fixture test", "fixture stdlib", "fixture metadata"],
                         [observed[key] for key in ("main", "test", "stdlib", "resource")])
        for path in observed["classpath"]:
            self.assertNotIn("core/build/classes", path)
            self.assertFalse(Path(path).exists())
        self.assertEqual("a" * 40, result["revision"])
        self.assertFalse(result["worktree_dirty"])
        self.assertEqual(hashlib.sha256(b"fixture bytecode").hexdigest(),
                         result["compiled_class_sha256"][str(self.binary.relative_to(self.root))])
        self.assertEqual(hashlib.sha256(b"fixture source").hexdigest(),
                         result["source_sha256"][str(self.source.relative_to(self.root))])
        execution = result["execution"]
        self.assertEqual(str(self.java), execution["java_path"])
        self.assertEqual(hashlib.sha256(self.java.read_bytes()).hexdigest(), execution["java_sha256"])
        self.assertEqual("fake-java 21 fixture", execution["java_version"])
        self.assertEqual(observed["argv"], execution["argv"])
        self.assertEqual(hashlib.sha256(b"fixture metadata").hexdigest(),
                         execution["classpath_files_sha256"]["main/META-INF/core.kotlin_module"])
        self.assertEqual(result["compiled_class_sha256"][str(self.binary.relative_to(self.root))],
                         execution["classpath_files_sha256"]["main/Example.class"])
        self.assertEqual(hashlib.sha256(self.stdlib.read_bytes()).hexdigest(), execution["kotlin_stdlib_sha256"])
        self.assertEqual(hashlib.sha256(b"fixture csv\n").hexdigest(), execution["output_files_sha256"]["result.csv"])
        self.assertEqual(hashlib.sha256(jfr.read_bytes()).hexdigest(), execution["jfr"]["sha256"])
        self.assertEqual(hashlib.sha256(self.profile.read_bytes()).hexdigest(), execution["jfr"]["profile_sha256"])
        self.assertEqual("scripts/performance/benchmark-profile.jfc", execution["jfr"]["profile_source"])
        self.assertEqual(execution["jfr"]["profile_sha256"], observed["jfr_profile"]["sha256"])
        self.assertFalse(Path(observed["jfr_profile"]["path"]).exists())

    def test_java_receives_only_runtime_allowlist_without_credentials_or_option_injection(self):
        forbidden = {
            "GITHUB_TOKEN": "fake-credential-sentinel-github",
            "AWS_SECRET_ACCESS_KEY": "fake-credential-sentinel-aws",
            "UNEXPECTED_ENVIRONMENT": "fake-private-sentinel-unknown",
            "JAVA_TOOL_OPTIONS": "-Dfake.sensitive=fake-java-tool-options-sentinel",
            "_JAVA_OPTIONS": "-Dfake.sensitive=fake-private-java-options-sentinel",
            "JDK_JAVA_OPTIONS": "-Dfake.sensitive=fake-jdk-java-options-sentinel",
        }
        for recording in (False, True):
            with self.subTest(recording=recording):
                result = self.run_cli(*(["--jfr", str(self.root / "recording.jfr")] if recording else []),
                                      environment=forbidden | {"LANG": "C", "TZ": "UTC"})
                self.assertEqual(0, result.returncode, result.stderr)
                manifest = json.loads(self.output.read_text())
                execution = manifest["execution"]
                expected_allowlist = {"PATH", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP", "SYSTEMROOT", "WINDIR"}
                self.assertEqual(expected_allowlist, set(execution["java_environment_allowlist"]))
                self.assertLessEqual(set(execution["java_environment_keys"]), expected_allowlist)
                observed = json.loads(self.java_log.read_text())
                version_environment = json.loads((self.root / "java-version-environment.json").read_text())
                for environment in (observed["environment"], version_environment):
                    runtime_added = {"__CF_USER_TEXT_ENCODING"} if sys.platform == "darwin" else set()
                    self.assertLessEqual(set(environment), expected_allowlist | runtime_added)
                    self.assertEqual("C", environment["LANG"])
                    self.assertEqual("UTC", environment["TZ"])
                    self.assertEqual(str(self.java.parent.parent), environment["JAVA_HOME"])
                    for key in forbidden:
                        self.assertNotIn(key, environment)
                public = self.output.read_text() + self.java_log.read_text() + result.stdout + result.stderr
                for value in forbidden.values():
                    self.assertNotIn(value, public)
                if recording:
                    settings = observed["jfr_profile"]["settings"]
                    for event in ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.JVMInformation"):
                        self.assertEqual("false", settings[event]["enabled"])
                    self.assertEqual("true", settings["jdk.ExecutionSample"]["enabled"])
                    self.assertEqual("true", settings["jdk.ObjectAllocationSample"]["enabled"])

    def test_runner_and_profile_changes_invalidate_prepared_stamp(self):
        self.assertEqual(0, self.run_cli("--prepare-only").returncode)
        for path in (self.script, self.profile):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.write_bytes(original + (b"\n# changed runner\n" if path == self.script else b"\n<!-- changed profile -->\n"))
                result = self.run_cli("--prepare-only", "--skip-build")
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("compiled benchmark identity is missing or stale", result.stderr)
                self.assertFalse(self.java_log.exists())
                path.write_bytes(original)
        self.assertEqual("build\n", (self.root / "gradle-calls.txt").read_text())

    def test_original_or_frozen_profile_mutation_prevents_publication(self):
        for change in ("profile", "copied-profile"):
            with self.subTest(change=change):
                original = self.profile.read_bytes()
                self.seed_previous_result()
                recording = self.root / "recording.jfr"
                result = self.run_cli("--jfr", str(recording), environment={"RUN_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("changed during execution", result.stderr)
                self.assertFalse(recording.exists())
                self.assert_previous_result()
                self.profile.write_bytes(original)

    def test_prepare_only_builds_identity_without_java_or_results(self):
        self.java.unlink()
        self.output = self.root / "uncreated-output/result.json"
        result = self.run_cli("--prepare-only")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("build\n", (self.root / "gradle-calls.txt").read_text())
        stamp = json.loads((self.root / "core/build/performance-sources.json").read_text())
        self.assertEqual({"sources", "classes", "classpath_files"}, set(stamp))
        self.assertEqual(hashlib.sha256(b"fixture source").hexdigest(),
                         stamp["sources"][str(self.source.relative_to(self.root))])
        self.assertEqual(hashlib.sha256(self.script.read_bytes()).hexdigest(), stamp["sources"]["scripts/benchmark-circuits.py"])
        self.assertEqual(hashlib.sha256(self.profile.read_bytes()).hexdigest(), stamp["sources"]["scripts/performance/benchmark-profile.jfc"])
        self.assertEqual({
            "core/build/classes/kotlin/jvm/main/Example.class": hashlib.sha256(b"fixture bytecode").hexdigest(),
            "core/build/classes/kotlin/jvm/test/Benchmark.class": hashlib.sha256(b"fixture test").hexdigest(),
        }, stamp["classes"])
        self.assertEqual({
            "main/Example.class": hashlib.sha256(b"fixture bytecode").hexdigest(),
            "main/META-INF/core.kotlin_module": hashlib.sha256(b"fixture metadata").hexdigest(),
            "test/Benchmark.class": hashlib.sha256(b"fixture test").hexdigest(),
        }, stamp["classpath_files"])
        self.assertFalse(self.java_log.exists())
        self.assertFalse(self.output.parent.exists())
        self.assertEqual([], list(self.root.rglob("*.csv")))
        self.assertEqual([], list(self.root.rglob("*.jfr")))

    def test_prepare_only_skip_build_validates_identity_and_rejects_stale_artifacts(self):
        self.java.unlink()
        self.output = self.root / "uncreated-output/result.json"
        prepared = self.run_cli("--prepare-only")
        self.assertEqual(0, prepared.returncode, prepared.stderr)
        stamp = self.root / "core/build/performance-sources.json"
        original_stamp = stamp.read_bytes()
        skipped = self.run_cli("--prepare-only", "--skip-build")
        self.assertEqual(0, skipped.returncode, skipped.stderr)
        self.assertEqual(original_stamp, stamp.read_bytes())
        resource = self.binary.parent / "META-INF/core.kotlin_module"
        for changed in (self.binary, resource, self.source, stamp):
            with self.subTest(changed=str(changed.relative_to(self.root))):
                original = changed.read_bytes()
                if changed == stamp:
                    changed.unlink()
                else:
                    changed.write_bytes(b"stale preparation input")
                rejected = self.run_cli("--prepare-only", "--skip-build")
                self.assertEqual(2, rejected.returncode, rejected.stderr)
                self.assertIn("compiled benchmark identity is missing or stale", rejected.stderr)
                changed.write_bytes(original)
        self.assertEqual("build\n", (self.root / "gradle-calls.txt").read_text())
        self.assertFalse(self.java_log.exists())
        self.assertFalse(self.output.parent.exists())
        self.assertEqual([], list(self.root.rglob("*.csv")))
        self.assertEqual([], list(self.root.rglob("*.jfr")))

    def test_prepare_only_rejects_jfr_without_build_or_results(self):
        self.java.unlink()
        self.output = self.root / "uncreated-output/result.json"
        recording = self.root / "uncreated-recording/recording.jfr"
        result = self.run_cli("--prepare-only", "--jfr", str(recording))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("--prepare-only", result.stderr)
        self.assertIn("--jfr", result.stderr)
        self.assertFalse((self.root / "gradle-calls.txt").exists())
        self.assertFalse((self.root / "core/build/performance-sources.json").exists())
        self.assertFalse(self.binary.exists())
        self.assertFalse(self.java_log.exists())
        self.assertFalse(self.output.parent.exists())
        self.assertFalse(recording.parent.exists())

    def test_skip_build_rejects_stale_sources_and_classes(self):
        self.assertEqual(0, self.run_cli().returncode)
        self.assertEqual(0, self.run_cli("--skip-build").returncode)
        self.assertEqual("build\n", (self.root / "gradle-calls.txt").read_text())
        self.binary.write_bytes(b"classes from another build")
        stale = self.run_cli("--skip-build")
        self.assertEqual(2, stale.returncode)
        self.assertIn("compiled benchmark identity is missing or stale", stale.stderr)
        self.binary.write_bytes(b"fixture bytecode")
        resource = self.binary.parent / "META-INF/core.kotlin_module"
        resource.write_bytes(b"stale metadata")
        stale = self.run_cli("--skip-build")
        self.assertEqual(2, stale.returncode)
        self.assertIn("compiled benchmark identity is missing or stale", stale.stderr)
        resource.write_bytes(b"fixture metadata")
        self.source.write_text("uncompiled changes")
        stale = self.run_cli("--skip-build")
        self.assertEqual(2, stale.returncode)
        self.assertIn("compiled benchmark identity is missing or stale", stale.stderr)

    def test_source_or_revision_change_during_build_prevents_java_and_publication(self):
        for change in ("source", "revision", "added-source"):
            with self.subTest(change=change):
                self.source.write_text("fixture source")
                self.head.write_text("a" * 40)
                (self.source.parent / "Added.kt").unlink(missing_ok=True)
                self.seed_previous_result()
                result = self.run_cli(environment={"BUILD_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("changed during build", result.stderr)
                self.assertFalse(self.java_log.exists())
                self.assert_previous_result()

    def test_class_rebuild_cannot_change_the_bytes_seen_by_java(self):
        self.seed_previous_result()
        result = self.run_cli(environment={"RUN_CHANGE": "class"})
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("artifacts changed during execution", result.stderr)
        self.assertEqual("fixture bytecode", json.loads(self.java_log.read_text())["main"])
        self.assertEqual(b"rebuilt during measurement", self.binary.read_bytes())
        self.assert_previous_result()

    def test_copy_must_match_the_artifacts_verified_before_java_version(self):
        for change in ("class", "stdlib"):
            with self.subTest(change=change):
                self.stdlib.write_bytes(b"fixture stdlib")
                self.seed_previous_result()
                result = self.run_cli(environment={"VERSION_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("copied benchmark classpath differs", result.stderr)
                self.assertFalse(self.java_log.exists())
                self.assert_previous_result()

    def test_added_removed_or_changed_classpath_files_reject_publication(self):
        for change in ("added-class", "deleted-class", "resource", "stdlib"):
            with self.subTest(change=change):
                self.stdlib.write_bytes(b"fixture stdlib")
                self.binary.with_name("Added.class").unlink(missing_ok=True)
                self.seed_previous_result()
                result = self.run_cli(environment={"RUN_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("artifacts changed during execution", result.stderr)
                self.assert_previous_result()

    def test_source_tree_and_revision_changes_during_execution_reject_publication(self):
        for change in ("source", "added-source", "deleted-source", "revision"):
            with self.subTest(change=change):
                self.source.write_text("fixture source")
                self.head.write_text("a" * 40)
                (self.source.parent / "Added.kt").unlink(missing_ok=True)
                self.seed_previous_result()
                result = self.run_cli(environment={"RUN_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("sources or revision changed during execution", result.stderr)
                self.assert_previous_result()

    def test_runner_and_java_launcher_changes_are_rejected(self):
        for change in ("runner", "java"):
            with self.subTest(change=change):
                script, launcher = self.script.read_bytes(), self.java.read_bytes()
                self.seed_previous_result()
                result = self.run_cli(environment={"RUN_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("changed during execution", result.stderr)
                self.assert_previous_result()
                self.script.write_bytes(script)
                self.java.write_bytes(launcher)

    def test_mutation_of_frozen_copy_is_rejected(self):
        for change in ("copied-class", "copied-stdlib"):
            with self.subTest(change=change):
                self.seed_previous_result()
                result = self.run_cli(environment={"RUN_CHANGE": change})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("frozen benchmark classpath changed", result.stderr)
                self.assert_previous_result()

    def test_rejected_run_does_not_publish_jfr_or_replace_existing_results(self):
        jfr = self.root / "recording.jfr"
        for change in ({"RUN_CHANGE": "source"}, {"FAIL_JAVA": "1"}, {"OMIT_JFR": "1"}):
            with self.subTest(change=change):
                self.source.write_text("fixture source")
                self.seed_previous_result()
                result = self.run_cli("--jfr", str(jfr), environment=change)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(jfr.exists())
                self.assert_previous_result()

    def test_jfr_destination_cannot_overwrite_results_or_create_corpus_directory(self):
        for destination in (self.output, self.output.with_suffix(".csv")):
            with self.subTest(destination=destination.name):
                self.seed_previous_result()
                result = self.run_cli("--jfr", str(destination))
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("JFR destination overlaps", result.stderr)
                self.assert_previous_result()
        self.output = self.root / "corpus"
        result = self.run_cli("--workload", "corpus", "--jfr", str(self.output / "recording.jfr"))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("JFR destination overlaps", result.stderr)
        self.assertFalse(self.output.exists())

    def test_jfr_destination_cannot_alias_csv_through_a_link(self):
        for link in ("symlink", "hardlink"):
            with self.subTest(link=link):
                self.seed_previous_result()
                recording = self.root / "shared.jfr"
                recording.write_bytes(b"previous csv")
                csv = self.output.with_suffix(".csv")
                csv.unlink()
                if link == "symlink":
                    csv.symlink_to(recording)
                else:
                    csv.hardlink_to(recording)
                result = self.run_cli("--jfr", str(recording))
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("JFR destination overlaps", result.stderr)
                self.assert_previous_result()
                self.assertEqual(b"previous csv", recording.read_bytes())

    def test_corpus_keeps_xml_bytes_and_requires_a_new_directory(self):
        self.output = self.root / "corpus"
        result = self.run_cli("--workload", "corpus")
        self.assertEqual(0, result.returncode, result.stderr)
        xml = self.output / "fixture.circ"
        expected = b'<project keep="literal&amp;bytes"><circuit name="main"/></project>\n'
        self.assertEqual(expected, xml.read_bytes())
        manifest = json.loads((self.output / "manifest.json").read_text())
        digest = hashlib.sha256(expected).hexdigest()
        self.assertEqual(digest, manifest["cases"][0]["sha256"])
        self.assertEqual(digest, manifest["execution"]["output_files_sha256"]["fixture.circ"])
        second = self.run_cli("--workload", "corpus")
        self.assertEqual(2, second.returncode)
        self.assertIn("Use a new corpus directory", second.stderr)
        self.assertEqual(expected, xml.read_bytes())

    def test_replay_physically_copies_literal_inputs_and_publishes_only_a_functional_receipt(self):
        self.prepare_replay_inputs()
        result = self.run_replay(environment={"JAVA_TOOL_OPTIONS": "-Dprivate.fixture=secret"})
        self.assertEqual(0, result.returncode, result.stderr)
        observed = json.loads(self.java_log.read_text())
        inputs = observed["replay_inputs"]
        self.assertNotEqual(str(self.replay_manifest), inputs["manifest"])
        self.assertNotEqual(str(self.replay_sap), inputs["sap"])
        self.assertEqual({name: value.hex() for name, value in self.replay_originals.items()}, inputs["bytes"])
        self.assertFalse(Path(inputs["manifest"]).exists())
        self.assertFalse(Path(inputs["sap"]).exists())
        self.assertNotIn("JAVA_TOOL_OPTIONS", observed["environment"])
        self.assertIn("dev.logisim.mobile.core.PerformanceCorpusReplay", observed["argv"])
        receipt = json.loads(self.output.read_text())
        self.assertEqual("functional_replay", receipt["scope"])
        self.assertIs(False, receipt["benchmark_executed"])
        expected_hashes = {"corpus/" + name: hashlib.sha256(value).hexdigest()
                           for name, value in self.replay_originals.items() if name != "sap1.circ"}
        expected_hashes["sap1.circ"] = hashlib.sha256(self.replay_originals["sap1.circ"]).hexdigest()
        self.assertEqual(expected_hashes, receipt["input_sha256"])
        self.assertEqual({}, receipt["execution"]["output_files_sha256"])
        self.assertIsNone(receipt["execution"]["jfr"])
        self.assertEqual(["before_build", "after_build", "before_execution", "after_execution"],
                         receipt["execution"]["input_identity_checks"])
        self.assertFalse(self.output.with_suffix(".csv").exists())
        self.assertEqual([], list(self.root.rglob("*.jfr")))
        self.assertEqual(self.replay_originals,
                         {p.name: p.read_bytes() for p in self.replay_manifest.parent.iterdir()} |
                         {"sap1.circ": self.replay_sap.read_bytes()})

    def test_replay_rejects_measurement_and_prepare_options_before_launch(self):
        self.prepare_replay_inputs()
        for arguments in (("--sizes", "16"), ("--iterations", "3"), ("--warmups", "0"),
                          ("--phases", "import"), ("--jfr", str(self.root / "private.jfr")), ("--prepare-only",)):
            with self.subTest(arguments=arguments):
                result = self.run_replay(*arguments)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("replay is functional only", result.stderr)
                self.assertFalse((self.root / "gradle-calls.txt").exists())
                self.assertFalse(self.java_log.exists())
                self.assertFalse(self.output.exists())

    def test_replay_rejects_changed_pinned_inputs_before_build(self):
        for changed in ("manifest", "xml", "sap"):
            with self.subTest(changed=changed):
                self.prepare_replay_inputs()
                target = (self.replay_manifest if changed == "manifest" else self.replay_sap if changed == "sap" else
                          self.replay_manifest.parent / "independent-16.circ")
                target.write_bytes(target.read_bytes() + b"changed")
                result = self.run_replay()
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.root / "gradle-calls.txt").exists())
                self.assertFalse(self.java_log.exists())
                self.assertFalse(self.output.exists())

    def test_replay_rejects_unsafe_duplicate_and_unexpected_input_names(self):
        for change in ("unsafe", "duplicate", "extra", "missing", "symlink"):
            with self.subTest(change=change):
                self.prepare_replay_inputs()
                directory = self.replay_manifest.parent
                if change in ("unsafe", "duplicate"):
                    manifest = json.loads(self.replay_manifest.read_text())
                    manifest["cases"][0]["file"] = "../escape.circ" if change == "unsafe" else "independent-64.circ"
                    old_hash = hashlib.sha256(self.replay_manifest.read_bytes()).hexdigest()
                    self.replay_manifest.write_text(json.dumps(manifest))
                    self.script.write_text(self.script.read_text().replace(old_hash, hashlib.sha256(self.replay_manifest.read_bytes()).hexdigest()))
                elif change == "extra":
                    (directory / "extra.circ").write_bytes(b"inert extra fixture")
                elif change == "missing":
                    (directory / "independent-16.circ").unlink()
                else:
                    target = directory / "independent-16.circ"
                    target.unlink()
                    target.symlink_to(self.replay_sap)
                result = self.run_replay()
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.root / "gradle-calls.txt").exists())
                self.assertFalse(self.java_log.exists())
                self.assertFalse(self.output.exists())

    def test_replay_rejects_mutated_original_and_copied_inputs_after_fake_execution(self):
        for change in ("xml", "manifest", "sap", "extra", "removed"):
            for target in ("original", "copied"):
                with self.subTest(change=change, target=target):
                    self.prepare_replay_inputs()
                    result = self.run_replay(environment={"RUN_CHANGE": f"replay-{target}-{change}"})
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertFalse(self.output.exists())
                    self.assertIn("replay", result.stderr)
        self.prepare_replay_inputs()
        result = self.run_replay(environment={"RUN_CHANGE": "replay-copied-root-extra"})
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("copied input inventory changed", result.stderr)
        self.assertFalse(self.output.exists())
        for change in ("directory-link", "hardlink"):
            with self.subTest(change=change):
                self.prepare_replay_inputs()
                result = self.run_replay(environment={"RUN_CHANGE": f"replay-copied-{change}"})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("replay", result.stderr)
                self.assertFalse(self.output.exists())

    def test_replay_rejects_symlinked_input_directories_with_independent_bytes(self):
        for name in ("inputs", "corpus"):
            with self.subTest(directory=name):
                self.output.unlink(missing_ok=True)
                self.prepare_replay_inputs()
                result = self.run_replay(environment={"RUN_CHANGE": f"replay-copied-{name}-symlink"})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("replay copied input directory changed during execution", result.stderr)
                self.assertFalse(self.output.exists())
                for filename, content in self.replay_originals.items():
                    original = self.replay_sap if filename == "sap1.circ" else self.replay_manifest.parent / filename
                    self.assertEqual(content, original.read_bytes())

    def test_replay_rejects_benchmark_payload_and_preserves_existing_receipt(self):
        self.prepare_replay_inputs()
        result = self.run_replay(environment={"RUN_CHANGE": "replay-benchmark-payload"})
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("only a functional receipt", result.stderr)
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.with_suffix(".csv").exists())
        self.output.write_bytes(b"existing receipt")
        self.java_log.unlink()
        result = self.run_replay()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertEqual(b"existing receipt", self.output.read_bytes())
        self.assertFalse(self.java_log.exists())

    def test_replay_cannot_write_a_receipt_into_the_historical_corpus(self):
        self.prepare_replay_inputs()
        self.output = self.replay_manifest.parent / "new-receipt.json"
        result = self.run_replay()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("outside the historical corpus", result.stderr)
        self.assertFalse(self.output.exists())
        self.assertFalse((self.root / "gradle-calls.txt").exists())

    def test_replay_rejects_incomplete_or_wrong_functional_receipts(self):
        for change in ("incomplete", "counts", "schema", "case-status", "case-hash", "case-output", "extra-case", "sap-ticks", "sap-display"):
            with self.subTest(change=change):
                self.prepare_replay_inputs()
                result = self.run_replay(environment={"RUN_CHANGE": f"replay-receipt-{change}"})
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("pinned functional expectations", result.stderr)
                self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
