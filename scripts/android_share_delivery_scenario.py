"""Verify a mounted editor shares its current XML through the native URI grant."""

import hashlib
import json
import subprocess
import time


def assert_share_receipt(expected, received, package, circuit_count):
    assert received.get('readable') is True, 'The external receiver could not read the temporary grant'
    assert received.get('circuits') == circuit_count, 'The receiver parsed the wrong project'
    assert received.get('bytes') == len(expected), 'The receiver read a truncated or extended document'
    assert received.get('sha256') == hashlib.sha256(expected).hexdigest(), 'The receiver read different bytes'
    assert received.get('uri', '').startswith(f'content://{package}.files/circuits/'), 'The shared URI escaped the restricted FileProvider route'


def run_share_delivery(scenarios):
    ui = scenarios.ui
    ui._require_test_app()
    before, project = scenarios.export_xml('delivery-before')
    expected = before.encode('utf-8')
    test_package = ui.package + '.test'
    ui.adb('shell', 'run-as', test_package, 'rm', '-f', 'files/share-result.json')
    scenarios.click_key('a11y_project_files', description=True)
    scenarios.click_key('action_share')
    ui.click(text='Document test receiver: ' + test_package)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = subprocess.run([ui.adb_path, '-s', ui.serial, 'exec-out', 'run-as', test_package,
                                 'cat', 'files/share-result.json'], capture_output=True, timeout=10)
        try:
            received = json.loads(result.stdout)
        except (json.JSONDecodeError, UnicodeError):
            time.sleep(.2)
            continue
        assert_share_receipt(expected, received, ui.package, len(project.findall('circuit')))
        break
    else:
        raise AssertionError('The external share receiver did not record delivery within 30s')
    (ui.output / 'delivery-receiver.json').write_text(json.dumps(received, indent=2) + '\n')
    scenarios.wait_editor()
    after, _ = scenarios.export_xml('delivery-after')
    assert before == after, 'Sharing changed the current editor document'
    ui.capture('delivery-editor-returned')
    receipt = {'scope': 'mounted editor share to external test receiver', 'result': 'PASS',
               'expectedBytes': len(expected), 'expectedSha256': hashlib.sha256(expected).hexdigest(),
               'received': received, 'documentBefore': 'delivery-before.circ', 'documentAfter': 'delivery-after.circ'}
    (ui.output / 'delivery-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt
