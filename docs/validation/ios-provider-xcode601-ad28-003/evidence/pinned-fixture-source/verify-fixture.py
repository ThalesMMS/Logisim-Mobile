#!/usr/bin/env python3
"""Check standalone fixture configuration. This does not execute FileProvider or the app."""
import hashlib
import json
from pathlib import Path
import plistlib
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
BUNDLES = {
    "thalesmms.logisim.validation.revocationfixture",
    "thalesmms.logisim.validation.revocationfixture.provider",
}


def verify():
    app = plistlib.loads((ROOT / "Config/App-Info.plist").read_bytes())
    provider = plistlib.loads((ROOT / "Config/Provider-Info.plist").read_bytes())
    scene_manifest = app["UIApplicationSceneManifest"]
    assert scene_manifest == {
        "UIApplicationSupportsMultipleScenes": False,
        "UISceneConfigurations": {"UIWindowSceneSessionRoleApplication": [{
            "UISceneConfigurationName": "Fixture",
            "UISceneDelegateClassName": "$(PRODUCT_MODULE_NAME).FixtureSceneDelegate",
        }]},
    }
    extension = provider["NSExtension"]
    assert extension["NSExtensionPointIdentifier"] == "com.apple.fileprovider-nonui"
    assert extension["NSExtensionPrincipalClass"] == "$(PRODUCT_MODULE_NAME).RevocationFileProvider"
    assert extension["NSExtensionFileProviderDocumentGroup"] == GROUP
    assert extension["NSExtensionFileProviderSupportsEnumeration"] is True
    for name in ("App.entitlements", "Provider.entitlements"):
        entitlements = plistlib.loads((ROOT / "Config" / name).read_bytes())
        assert entitlements == {"com.apple.security.application-groups": [GROUP]}, name
    declaration, = app["UTImportedTypeDeclarations"]
    assert declaration["UTTypeIdentifier"] == "org.logisim-evolution.circuit"
    assert declaration["UTTypeTagSpecification"]["public.filename-extension"] == ["circ"]
    project = (ROOT / "project.yml").read_text()
    assert set(re.findall(r"PRODUCT_BUNDLE_IDENTIFIER:\s*(\S+)", project)) == BUNDLES
    assert "thalesmms.logisim.mobile" not in project
    source = (ROOT / "Shared/FixtureStore.swift").read_text()
    assert f'let appGroupIdentifier = "{GROUP}"' in source
    match = re.search(r'private static let initialCircuit = """\n(.*?)\n    """', source, re.S)
    assert match, "The initial circuit literal must be available for independent XML checks"
    circuit_bytes = ("\n".join(line[4:] for line in match[1].splitlines())).encode()
    circuit = ET.fromstring(circuit_bytes)
    assert circuit.tag == "project"
    assert circuit.find("main").attrib["name"] == "provider_revocation"
    circuits = circuit.findall("circuit")
    assert len(circuits) == 1
    assert len(circuits[0].findall("comp")) == 2
    assert len(circuits[0].findall("wire")) == 1
    return {
        "configuration": "PASS",
        "nativeExecution": "NOT_RUN",
        "scope": "Plists, single-scene configuration, isolated identities, App Group agreement and initial XML only",
        "sceneLifecycle": "One declared UIWindowScene delegate; native behavior NOT_RUN",
        "fixtureAppGroup": GROUP,
        "bundleIdentifiers": sorted(BUNDLES),
        "initialCircuitSHA256": hashlib.sha256(circuit_bytes).hexdigest(),
        "initialCircuitBytes": len(circuit_bytes),
    }


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
