from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,time
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts'))
from android_ui import AndroidUi
from android_accessibility_layout import inventory,restore,restoration_differences
spec=importlib.util.spec_from_file_location('layout12_frozen_runner',ROOT/'scripts/e2e-android.py')
runner=importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
SDK=Path('/Users/thales/Library/Android/sdk')
app,tests,helper=runner.artifact_paths(OUT/'artifacts')
expected=['10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973','19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b','504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c']
assert [runner.file_hash(f) for f in (app,tests,helper)]==expected
receipt,_=runner.load_build_receipt(OUT/'build-receipt.json',expected[0])
assert receipt['gitHead']=='3a0a01f1af05dd3fea75d5a1499c762d039c478a'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()=='68a3138307638dad928b184a0d3ec30c8eb30ead'
assert not subprocess.check_output(['git','status','--short'],cwd=ROOT,text=True).strip()
assert not subprocess.check_output(['git','diff','--name-only',receipt['gitHead'],'HEAD','--','core/src/commonMain','composeApp/src/commonMain','composeApp/src/androidMain','androidApp/src/main','localization/messages','app.properties'],cwd=ROOT,text=True).strip()
assert not subprocess.check_output(['git','diff','--name-only',receipt['snapshotSource'],'HEAD','--','androidSnapshot/src','scripts/android_ui.py'],cwd=ROOT,text=True).strip()
catalog={}
for path in sorted((ROOT/'localization/messages').glob('*.json')):
 data=json.loads(path.read_text()); assert not catalog.keys()&data.keys(); catalog.update(data)
assert len(catalog)==2497 and all('en' in item and 'pt' in item for item in catalog.values())
aapt=runner.find_aapt2(SDK)
identities=runner.validate_apk_manifests(aapt,app,tests,helper,'thalesmms.logisim.mobile.e2e')
preflight={'status':'PASS','serial':'emulator-5558','appProducer':receipt['gitHead'],'harness':'68a3138307638dad928b184a0d3ec30c8eb30ead','helperSource':receipt['snapshotSource'],'catalogMessages':len(catalog),'apks':dict(zip(['app','test','snapshot'],expected)),'identities':identities,'aapt2':str(aapt),'scope':'Local preflight only. No device command or UI has run.'}
(OUT/'preflight.json').write_text(json.dumps(preflight,indent=2)+'\n')
print('LOCAL_PREFLIGHT_PASS; waiting for ARM on stdin before the first device getter.',flush=True)
assert input().strip()=='ARM'
ui=AndroidUi(SDK/'platform-tools/adb','emulator-5558','thalesmms.logisim.mobile.e2e',OUT/'outer-captures',focus_snapshots=True)
verbose=ui.adb('shell','dumpsys','window','-a')
(OUT/'window-verbose-before-ui.txt').write_text(verbose)
assert 'Frames:' in verbose and 'mFullConfiguration=' in verbose, 'Verbose collection lacks required raw observations; stop before UI without fallback.'
original=inventory(ui)
(OUT/'inventory-before-ui.json').write_text(json.dumps(original,indent=2)+'\n')
print('GETTERS_BACKUP_SAVED; starting exactly one layout12 small-phone/font1 run.',flush=True)
started=time.monotonic(); code=None; cleanup={}
try:
 command=[sys.executable,str(ROOT/'scripts/e2e-android.py'),'--serial','emulator-5558','--scenario','layout12','--layout12-unit','small-phone','--layout12-font','1','--artifacts',str(OUT/'artifacts'),'--build-receipt',str(OUT/'build-receipt.json'),'--aapt2',str(aapt),'--language','en','--output',str(OUT/'run')]
 (OUT/'command.json').write_text(json.dumps(command,indent=2)+'\n')
 with (OUT/'console.log').open('w') as stream:
  result=subprocess.run(command,cwd=ROOT,env={**os.environ,'ANDROID_HOME':str(SDK)},stdout=stream,stderr=subprocess.STDOUT,check=False)
 code=result.returncode
finally:
 try:
  cleanup['installedAfter']=[ui.verify_installed_apk(f,p) for f,p in [(app,ui.package),(tests,ui.package+'.test'),(helper,ui.snapshot_package)]]
 except Exception as error:
  cleanup['installedVerificationError']=str(error)
 after=inventory(ui)
 (OUT/'inventory-after-run.json').write_text(json.dumps(after,indent=2)+'\n')
 differences=restoration_differences(original,after)
 cleanup['differencesBeforeOuterCleanup']=differences
 if differences:
  cleanup['outerRestoration']=restore(ui,original)
 ui.adb('shell','am','force-stop',ui.package)
 final=inventory(ui)
 (OUT/'inventory-final.json').write_text(json.dumps(final,indent=2)+'\n')
 cleanup['finalDifferences']=restoration_differences(original,final)
 cleanup['forceStoppedPackage']=ui.package
 cleanup['elapsedSeconds']=round(time.monotonic()-started,3)
 cleanup['nativeExitCode']=code
 cleanup['unitAttempts']=1
 cleanup['nativeStatus']='PASS' if code==0 else 'FAIL'
 cleanup['restorationStatus']='PASS' if not cleanup['finalDifferences'] else 'FAIL'
 (OUT/'cleanup.json').write_text(json.dumps(cleanup,indent=2)+'\n')
 print(json.dumps(cleanup),flush=True)
assert code==0 and not cleanup['finalDifferences'] and 'installedVerificationError' not in cleanup, 'The one authorized unit or cleanup did not pass; no retry.'
