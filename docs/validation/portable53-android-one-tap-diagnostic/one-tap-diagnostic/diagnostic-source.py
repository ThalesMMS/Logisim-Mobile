#!/usr/bin/env python3
"""One explicitly authorized native Downloads tap, with fail-closed observations."""
import hashlib,json,re,subprocess,sys
from pathlib import Path
canonical=Path('/Users/thales/GitHub/logisim-mobile-worktrees/android-e2e-df276e')
sys.path.insert(0,str(canonical/'scripts'))
from android_ui import AndroidUi
output=canonical/'outputs/android-e2e/59d5606b-portable-folder-5558-en-40493ec1/one-tap-diagnostic'
output.mkdir(parents=True,exist_ok=False)
ui=AndroidUi('/Users/thales/Library/Android/sdk/platform-tools/adb','emulator-5558','thalesmms.logisim.mobile.e2e',output)
receipt={'schema':1,'scope':'One root-authorized diagnostic tap only; not a roundtrip retry or PASS for run14368','tapCount':0,'status':'ABSTAINED','serial':ui.serial,'productPin':'59d5606bfe70e5a8119a387f5c4b5c2a85884c8e','driverSha256':hashlib.sha256((canonical/'scripts/android_ui.py').read_bytes()).hexdigest()}
native='com.google.android.documentsui'

def state(name):
    window=ui.adb('shell','dumpsys','window','windows')
    activity=ui.adb('shell','dumpsys','activity','activities')
    (output/(name+'-window.txt')).write_text(window)
    (output/(name+'-activity.txt')).write_text(activity)
    pid=ui.adb('shell','pidof',native).strip()
    focus=re.findall(r'^\s*mCurrentFocus=(.*)$',window,re.MULTILINE)
    assert len(focus)==1 and native+'/com.android.documentsui.picker.PickActivity' in focus[0], 'The expected native picker is not uniquely focused'
    assert re.fullmatch(r'[0-9]+',pid), 'The native picker PID is not unique'
    return {'pid':pid,'focus':focus[0]}

def target(root):
    roots=[node for node in root.iter('node') if node.get('package')==native and node.get('resource-id')==native+':id/roots_list']
    assert len(roots)==1,'No unique native roots list'
    node=ui.find(roots[0],text='Downloads')
    assert node.get('package')==native and node.get('enabled')=='true','Downloads is not enabled in the native picker'
    bounds=ui._bounds(node)
    outer=ui._bounds(roots[0])
    assert outer[0]<=bounds[0]<bounds[2]<=outer[2] and outer[1]<=bounds[1]<bounds[3]<=outer[3],'The Downloads bounds escape its roots list'
    return node,{'node':dict(node.attrib),'rootsBounds':list(outer)}

try:
    artifacts=canonical/'outputs/android-e2e/artifacts-59d5606b'
    receipt['installedArtifacts']=[ui.verify_installed_apk(artifacts/name,package) for name,package in (
       ('androidApp-e2e.apk',ui.package),('androidApp-e2e-androidTest.apk',ui.package+'.test'),
       ('androidSnapshot-debug.apk',ui.snapshot_package))]
    first=state('before')
    root=ui.screen(timeout=30)
    node,observed=target(root)
    ui.capture('before',root)
    root2=ui.screen(timeout=30)
    node2,observed2=target(root2)
    ui.capture('before-confirmed',root2)
    confirmed=state('before-confirmed')
    receipt.update(before=first,beforeConfirmed=confirmed,target=observed,confirmedTarget=observed2)
    assert first==confirmed,'The native PID or focused window changed before the tap'
    assert observed==observed2,'The canonical target or its bounds changed before the tap'
    receipt['tapCount']=1
    ui.tap(node2)
    after=ui.screen(timeout=30)
    ui.capture('after',after)
    receipt['after']=state('after')
    receipt['afterNativeTexts']=[node.get('text') for node in after.iter('node') if node.get('package')==native and node.get('text')]
    receipt['afterRootsCount']=sum(node.get('resource-id')==native+':id/roots_list' for node in after.iter('node'))
    receipt['afterDirectoryListCount']=sum(node.get('resource-id')==native+':id/dir_list' for node in after.iter('node'))
    receipt['status']='OBSERVED_ONE_CANONICAL_TAP'
except Exception as error:
    receipt['error']=str(error)
    if receipt['tapCount']:receipt['status']='FAILED_AFTER_DIAGNOSTIC_TAP'
    raise
finally:
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    (output/'diagnostic-source.py').write_bytes(Path(__file__).read_bytes())
    print(json.dumps(receipt,indent=2))
