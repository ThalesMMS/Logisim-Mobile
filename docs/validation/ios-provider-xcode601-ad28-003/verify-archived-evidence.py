#!/usr/bin/env python3
"""Verify the local archive only; no native tool, producer helper or recorded path runs."""
import base64
from datetime import datetime
import gzip
import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct

ROOT = Path(__file__).resolve().parent
DEVICE = "AD28C68F-21D3-4FA6-BCD7-383AE2A1D372"
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
MANIFEST = "c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159"
REVIEW = "254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8"
BACKEND = "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88"

def decode_der(blob):
    """Strict bounded decoder for the observed version-1 string/array dictionaries."""
    assert 0 < len(blob) <= 1048576
    nodes = 0
    def node(pos, end, depth):
        nonlocal nodes
        nodes += 1
        assert nodes <= 256 and depth <= 12 and pos + 2 <= end
        tag, length = blob[pos:pos + 2]
        pos += 2
        assert tag & 31 != 31
        if length & 128:
            width = length & 127
            assert 1 <= width <= 4 and pos + width <= end and blob[pos] != 0
            length = int.from_bytes(blob[pos:pos + width], "big")
            assert length >= 128
            pos += width
        limit = pos + length
        assert limit <= end
        if tag & 32:
            value = []
            while pos < limit:
                child, pos = node(pos, limit, depth + 1)
                value.append(child)
        else:
            value = blob[pos:limit]
        return (tag, value), limit
    root, end = node(0, len(blob), 0)
    assert end == len(blob) and root[0] == 0x70
    assert len(root[1]) == 2 and root[1][0] == (2, b"\x01") and root[1][1][0] == 0xB0
    def value(item):
        tag, payload = item
        if tag == 0x0C:
            return payload.decode("utf-8", "strict")
        assert tag == 0x30
        return [value(child) for child in payload]
    result = {}
    for tag, pair in root[1][1][1]:
        assert tag == 0x30 and len(pair) == 2 and pair[0][0] == 0x0C
        key = pair[0][1].decode("utf-8", "strict")
        assert key not in result
        result[key] = value(pair[1])
    return result

def read(name):
    p = ROOT / name
    assert p.resolve().is_relative_to(ROOT) and p.is_file() and not p.is_symlink(), name
    return p.read_bytes()


def load(name):
    return json.loads(read(name))


def stamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def public(name):
    text = read('evidence/attachments/' + name).decode()
    assert 'App Group: ' + GROUP in text and 'Domain: logisim-revocation-fixture-v1' in text
    return text, json.loads(text.split('\nSNAPSHOT\n', 1)[1])


def verify():
    sums = {}
    for line in read('SHA256SUMS').decode().splitlines():
        digest, name = line.split('  ', 1)
        assert re.fullmatch(r'[0-9a-f]{64}', digest) and name not in sums
        assert not Path(name).is_absolute() and '..' not in Path(name).parts
        assert hashlib.sha256(read(name)).hexdigest() == digest, name
        sums[name] = digest
    assert set(sums) == {str(p.relative_to(ROOT)) for p in ROOT.rglob('*') if p.is_file()} - {'SHA256SUMS'}
    inventory = load('inventory.json')
    paths = [e['path'] for e in inventory['files']]
    assert len(paths) == len(set(paths)) and set(paths) == {p for p in sums if p.startswith('evidence/')}
    for e in inventory['files']:
        b = read(e['path'])
        assert len(b) == e['bytes'] and hashlib.sha256(b).hexdigest() == e['sha256'], e['path']
    raw = {}
    for name, item in inventory['compressedRawJSON'].items():
        b = read(item['path'])
        assert int.from_bytes(b[4:8], 'little') == 0
        value = gzip.decompress(b)
        assert len(value) == item['originalBytes'] and hashlib.sha256(value).hexdigest() == item['originalSHA256']
        raw[name] = value
    def output(name):
        return raw[name] if name in raw else read('evidence/run/' + name)
    commands = load('evidence/run/commands.json')
    for command in commands:
        assert command['exitCode'] == 0, command['name']
        for stream in ('stdout', 'stderr'):
            entry = command[stream]
            b = output(entry['path'])
            assert len(b) == entry['bytes'] and hashlib.sha256(b).hexdigest() == entry['sha256']
    install = [c for c in commands if c['name'] == 'install-root-reviewed-fixture']
    assert len(install) == 1 and install[0]['argv'][2:4] == ['install', DEVICE]
    terminal = load('evidence/run/terminal.json')
    receipt = load('receipt.json')
    summary = load('evidence/run/xcresult-summary.stdout')
    counts = {'totalTestCount': 1, 'passedTests': 1, 'failedTests': 0, 'skippedTests': 0}
    assert receipt['status'] == terminal['status'] == 'PASS' and summary['result'] == 'Passed'
    assert receipt['counts'] == counts and all(summary[k] == v for k, v in counts.items())
    assert terminal['testExitCode'] == receipt['exitCode'] == 0 and terminal['qualifiedCounts'] and terminal['noRetry']
    assert receipt['caseSeconds'] == 25.384 and 'passed (25.384 seconds).' in read('evidence/run/test.log').decode()
    assert receipt['qualificationScope'] == 'BOOTSTRAP_REGISTER_ONLY' and receipt['limits']['issue26'] == 'NOT_QUALIFIED'
    assert all(receipt['limits'][k] == 'NOT_QUALIFIED' for k in ('providerFunctionalExecution', 'enumeration', 'fetch', 'Save', 'authenticationDenial', 'securityScopeRevocation'))
    assert hashlib.sha256(read('evidence/run/execution-driver.py')).hexdigest() == receipt['driverSHA256'] == '8d7245a855031b4a29dce225624360e85e7939647fadd49587e9599f51a71107'
    execution = load('evidence/run/execution.json')
    assert execution['exit_code'] == 0 and execution['command'] == load('evidence/run/test-process.json')['command']
    assert 'test-without-building' in execution['command'] and f'platform=iOS Simulator,id={DEVICE}' in execution['command']
    assert terminal['fixtureTerminated'] and terminal['deviceReturned'] == DEVICE
    cleanup = next(c for c in commands if c['name'] == 'fixture-terminate')
    assert cleanup['argv'][2:] == ['terminate', DEVICE, 'thalesmms.logisim.validation.revocationfixture']
    manifest_bytes = read('evidence/run/source-artifact.json')
    assert hashlib.sha256(manifest_bytes).hexdigest() == MANIFEST
    for name in ['evidence/producer/artifact.json', 'evidence/harness/ProvenanceSignedFixture.json', 'evidence/compiled-resources/ProvenanceSignedFixture.json', 'evidence/pinned-harness-source/ProvenanceSignedFixture.json', 'evidence/attachments/FC92BFBF-F869-4FA8-A0BE-92FCB60DBD4B.txt']:
        assert read(name) == manifest_bytes
    m = json.loads(manifest_bytes)
    assert m['source'] == terminal['fixtureSource'] == receipt['fixtureSource'] == '601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd'
    assert m['nativeExecution'] == m['domainRegistration'] == 'NOT_RUN' and m['simulatorEntitlementReviewRequired'] is True
    review_bytes = read('evidence/run/ProvenanceArtifactReview.json')
    assert hashlib.sha256(review_bytes).hexdigest() == terminal['rootReviewSHA256'] == receipt['rootReviewSHA256'] == REVIEW
    for name in ['evidence/harness/ProvenanceArtifactReview.json', 'evidence/compiled-resources/ProvenanceArtifactReview.json', 'evidence/pinned-harness-source/ProvenanceArtifactReview.json', 'evidence/attachments/DC81FB94-D274-4D46-90D4-6DB226C98B8B.txt']:
        assert read(name) == review_bytes
    admission = json.loads(review_bytes)
    assert admission['scope'] == 'LOCAL_SIMULATOR_ONLY' and admission['operation'] == 'ONE_PREPARE_REGISTER_INSPECT'
    assert admission['manifestSHA256'] == MANIFEST and not admission['developerTeamApproved'] and not admission['releaseApproved']
    assert not receipt['developerTeamApproved'] and not receipt['releaseApproved']
    pre = load('evidence/run/preflight.json')
    assert pre['status'] == 'PASS' and pre['manifestSHA256'] == MANIFEST
    assert terminal['harnessSource'] == receipt['harnessSource'] == '046ebd30c0f71d7f6bf65ca80ec93b925e772792'
    assert hashlib.sha256(read('evidence/harness/FileProviderBootstrapTests.swift')).hexdigest() == pre['stagedHarnessSHA256']
    prep = load('evidence/harness-preparation/source.json')
    assert prep['source'] == receipt['harnessSource']
    for entry in prep['files']:
        name = Path(entry['source']).name
        b = read('evidence/pinned-harness-source/' + name)
        assert len(b) == entry['bytes'] and hashlib.sha256(b).hexdigest() == entry['sha256']
        if 'evidence/harness/' + name in sums:
            assert b == read('evidence/harness/' + name)
    for name, digest in m['sourceFiles'].items():
        assert hashlib.sha256(read('evidence/pinned-fixture-source/' + name)).hexdigest() == digest
    old_identity = load('evidence/prior-fail-16.731.json')
    before = load('evidence/run/before-installed.json')
    installed = [load('evidence/run/' + phase + '-installed.json') for phase in ('after-install', 'after')]
    producer_after = load('evidence/run/source-products-after.json')
    for product, label in [('app', 'App'), ('provider', 'Provider')]:
        for key in ('bundle_id', 'executable_sha256', 'bundle_tree_sha256', 'version', 'build'):
            assert before[product][key] == old_identity[product][key]
            assert all(i[product][key] == m[product][key] for i in installed)
        for key in ('executable_sha256', 'bundle_tree_sha256'):
            assert producer_after[product][key] == m[product][key] and before[product][key] != m[product][key]
        assert admission[product + 'TreeSHA256'] == m[product]['bundle_tree_sha256']
        assert plistlib.loads(read(f'evidence/run/before-{product}-entitlements.stdout')) == {'com.apple.security.application-groups': [GROUP]}
        for phase in ('candidate-source', 'after-install', 'after'):
            report = load(f'evidence/run/{phase}-{label}-sections.json')
            assert report['sectionOwners'] == ['main'] and report['mainHasSectionPair']
            main = next(i for i in report['images'] if i['image'] == 'main')
            assert main['sha256'] == m[product]['executable_sha256']
            assert all(not i['sections'] for i in report['images'] if i['image'] == 'debug-dylib')
            values = {}
            for section, entry in main['sections'].items():
                b = read('evidence/run/' + entry['path'])
                assert len(b) == entry['bytes'] and hashlib.sha256(b).hexdigest() == entry['sha256']
                assert b == read('evidence/run/' + report['packagingInputs'][section]['path'])
                values[section] = b
            expected = {'application-identifier': 'P4VGX44GD8.' + m[product]['bundle_id'], 'com.apple.security.application-groups': [GROUP]}
            assert plistlib.loads(values['__entitlements']) == decode_der(values['__ents_der']) == report['simulatedEntitlements'] == admission['approvedSimulatedEntitlements'][label] == expected
        for phase in ('after-install', 'after'):
            assert plistlib.loads(read(f'evidence/run/{phase}-{product}-entitlements.stdout')) == {}
    groups = [load(f'evidence/run/{phase}-group.json') for phase in ('before', 'after-install', 'after')]
    dbs = [load(f'evidence/run/{phase}-fixture-state.json') for phase in ('before', 'after-install', 'after')]
    assert read('evidence/run/before-fixture-state.json') == read('evidence/run/after-install-fixture-state.json')
    assert dbs[0] == load('evidence/prior-after-state.json')
    state = {'authenticationAllowed': True, 'backendByteCount': 552, 'backendSHA256': BACKEND, 'generation': 1, 'schemaVersion': 1}
    for phase, group, db in zip(('before', 'after-install', 'after'), groups, dbs):
        assert group['path'].endswith('/5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4')
        assert group['metadata']['MCMMetadataIdentifier'] == GROUP and group['metadata']['MCMMetadataUUID'] == 'C4A78BD1-44B3-4590-B045-A3AC1883D566'
        assert all(group[k] == groups[0][k] for k in ('metadata', 'metadataSHA256', 'directoryBirthUTC', 'state'))
        assert group['state'] == db['state'] == state and group['events'] == db['events']
        assert hashlib.sha256(read(f'evidence/run/{phase}-fixture-state.json')).hexdigest() == group['stateSHA256']
        assert hashlib.sha256(read(f'evidence/run/{phase}-group-metadata.plist')).hexdigest() == group['metadataSHA256']
        b = base64.b64decode(db['contents'], validate=True)
        assert b == read(f'evidence/backend-{phase}.circ') and len(b) == 552 and hashlib.sha256(b).hexdigest() == BACKEND
    assert dbs[0]['events'] == dbs[2]['events'][:6] and [e['sequence'] for e in dbs[2]['events']] == list(range(1, 10))
    assert dbs[0]['nextSequence'] == 7 and dbs[2]['nextSequence'] == 10
    added = dbs[2]['events'][6:]
    assert [(e['sequence'], e['kind']) for e in added] == [(7, 'prepare'), (8, 'domain.registration.requested'), (9, 'domain.registration.completed')]
    assert all(e['process'] == 'RevocationFixture:23645' for e in added)
    assert all(added[-1].get(k) is None for k in ('errorDomain', 'errorCode', 'errorDiagnostic'))
    assert all(dbs[2]['events'][i]['errorCode'] == -2001 for i in (2, 5))
    _, baseline = public('AB107088-AB6B-44FF-B59B-3146BD1D1E18.txt')
    _, prepared = public('82DA3A33-0E24-4931-A683-283180E8FCAD.txt')
    _, registered = public('8F60315C-C430-47B1-A155-0FBE98F07501.txt')
    text, inspected = public('75BD609B-3A55-47BC-A34B-914DF1F29E5A.txt')
    _, ending = public('1026E1B2-F645-4835-BED1-385B18B0BB15.txt')
    assert baseline['events'] == dbs[0]['events'] and prepared['events'] == dbs[2]['events'][:7]
    assert registered == inspected == ending and inspected['events'] == dbs[2]['events'] and inspected['state'] == state
    assert text.startswith('status=Matching fixture domains: 1. Native scenario remains NOT_RUN.')
    assert receipt['matchingFixtureDomainCount'] == receipt['group']['matchingFixtureDomainCount'] == 1
    log = read('evidence/run/test.log').decode()
    assert all(log.count(f'Tap "{i}" Button') == 1 for i in ('fixture.prepare', 'fixture.domain.add', 'fixture.domain.inspect'))
    records = json.loads(raw['new-discovery-after-install.stdout'])
    selection = load('selected-events.json')
    assert len(records) == selection['sourceRecords'] == 956 and selection['indexBase'] == 0
    assert all(records[e['index']] == e['record'] for e in selection['events'])
    assert GROUP in records[851]['eventMessage'] and groups[0]['path'] in records[851]['eventMessage']
    assert 'Registered file provider extension' in records[859]['eventMessage'] and '5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4' in records[859]['eventMessage']
    assert stamp(records[851]['timestamp']) < stamp(records[859]['timestamp']) < stamp(execution['started']) < stamp(added[-1]['timestamp'])
    assert 'Creating new XPC connection' in records[897]['eventMessage']
    assert records[920]['processID'] == records[921]['processID'] == records[922]['processID'] == 23598
    assert receipt['limits']['providerFunctionalExecution'] == 'NOT_QUALIFIED'
    assert load('evidence/prior-fail-17.003.json')['status'] == old_identity['status'] == 'FAIL'
    pngs = 0
    for item in inventory['attachments']:
        if item['archived'] and item['path'].endswith('.png'):
            b = read(item['path'])
            assert b[:8] == b'\x89PNG\r\n\x1a\n' and all(struct.unpack('>II', b[16:24]))
            pngs += 1
    return {'archiveVerification': 'PASS', 'nativeResult': 'PASS', 'scope': 'BOOTSTRAP_REGISTER_ONLY', 'counts': counts,
            'evidenceFiles': len(inventory['files']), 'screenshots': pngs, 'events': 'prefix6 preserved; new7/8/9 complete', 'matchingFixtureDomainCount': 1,
            'providerFunctionalExecution': 'NOT_QUALIFIED', 'issue26': 'NOT_QUALIFIED', 'deviceAccess': False}


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
