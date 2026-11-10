from pathlib import Path
import datetime, hashlib, json, os, re, runpy, subprocess, sys, time, traceback
ROOT=Path('/Users/thales/GitHub/logisim-mobile-worktrees/android35-inspector-close')
CONTROL=ROOT/'outputs/android-editing-3a0a01f1-1bb15545-5558-r2-001-control'
OUTPUT=ROOT/'outputs/android-editing-3a0a01f1-1bb15545-5558-r2-001'
ADB=Path('/Users/thales/Library/Android/sdk/platform-tools/adb')
SERIAL='emulator-5558'
PACKAGE='thalesmms.logisim.mobile.e2e'
ARTIFACTS=Path('/Users/thales/GitHub/logisim-mobile-worktrees/android-io-keyboard-3a-exec/outputs/artifacts-5c-keyboard')
CLAIM=json.loads((ROOT/'docs/validation/android35-io44-preparation.json').read_text())
DISMISSAL=json.loads((ROOT/'docs/validation/android35-inspector-dismissal.json').read_text())
for section in ['preparedSources','unchangedOracleInputs']:
 for name in CLAIM[section]:
  if name in DISMISSAL['files']: CLAIM[section][name]=DISMISSAL['files'][name]
started=time.monotonic()
now=lambda:datetime.datetime.now(datetime.timezone.utc).isoformat()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
record={'schema':1,'status':'PREFLIGHT','serial':SERIAL,'package':PACKAGE,'startedUTC':now(),'driverSHA256':sha(__file__),'nativeInvocations':0,'scenario':'editing','source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'sourceFilesBefore':{},'sourceFilesAfter':{},'cohortLimit':'R1 used5556; R2 uses5558. This is not an isolated A/B comparison.','observer':'Python audit hook records only adb Popen command requests and times. It does not establish command completion or app event delivery.'}
command_log=(CONTROL/'adb-command-requests.jsonl').open('x',buffering=1)

def audit(event,args):
 if event!='subprocess.Popen': return
 executable,argv,cwd,_=args
 if Path(str(executable)).name!='adb': return
 values=list(argv) if not isinstance(argv,str) else [argv]
 command_log.write(json.dumps({'utc':now(),'elapsedSeconds':time.monotonic()-started,'argv':values,'cwd':str(cwd) if cwd is not None else None})+'\n')

sys.addaudithook(audit)
def save(): (CONTROL/'unit.json').write_text(json.dumps(record,indent=2)+'\n')
def adb(*args,timeout=30,check=True):
 result=subprocess.run([str(ADB),'-s',SERIAL,*args],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=timeout)
 if check and result.returncode: raise RuntimeError(f'ADB getter failed: {args}: {result.returncode}: {result.stderr[-1000:]}')
 return result

def capture(phase):
 folder=CONTROL/phase; folder.mkdir()
 getters={'sdk':['getprop','ro.build.version.sdk'],'fingerprint':['getprop','ro.build.fingerprint'],'locale':['settings','get','system','system_locales'],'persist-locale':['getprop','persist.sys.locale'],'product-locale':['getprop','ro.product.locale'],'wm-size':['wm','size'],'wm-density':['wm','density'],'font-scale':['settings','get','system','font_scale'],'accelerometer-rotation':['settings','get','system','accelerometer_rotation'],'user-rotation':['settings','get','system','user_rotation'],'accessibility-enabled':['settings','get','secure','accessibility_enabled'],'enabled-services':['settings','get','secure','enabled_accessibility_services'],'accessibility':['dumpsys','accessibility'],'last-anr':['dumpsys','activity','lastanr'],'package':['dumpsys','package',PACKAGE],'instrumentation':['pm','list','instrumentation'],'pid':['pidof',PACKAGE]}
 values={}
 for name,args in getters.items():
  result=adb('shell',*args,check=name!='pid')
  (folder/(name+'.stdout')).write_text(result.stdout)
  (folder/(name+'.stderr')).write_text(result.stderr)
  values[name]=result.stdout.strip()
 values['installed']=[]
 for name in ['androidApp-e2e.apk','androidApp-e2e-androidTest.apk','androidSnapshot-debug.apk']:
  package={'androidApp-e2e.apk':PACKAGE,'androidApp-e2e-androidTest.apk':PACKAGE+'.test','androidSnapshot-debug.apk':'thalesmms.logisim.mobile.snapshot.e2e.test'}[name]
  paths=adb('shell','pm','path',package).stdout.strip().splitlines()
  assert len(paths)==1 and paths[0].startswith('package:/data/app/'),(package,paths)
  path=paths[0].removeprefix('package:')
  hash_result=adb('shell','sha256sum',path).stdout.strip()
  assert re.fullmatch(r'([0-9a-f]{64})\s+'+re.escape(path),hash_result)
  observed=hash_result.split()[0]
  assert observed==sha(ARTIFACTS/name),(package,observed)
  values['installed'].append({'package':package,'path':path,'sha256':observed})
 (folder/'inventory.json').write_text(json.dumps(values,indent=2)+'\n')
 return values

def assert_reader_off(values):
 assert values['accessibility-enabled']=='0',values['accessibility-enabled']
 assert values['enabled-services'] in ('null',''),values['enabled-services']
 raw=values['accessibility']
 assert re.search(r'touchExplorationEnabled=false',raw), 'No explicit false touch exploration state'
 assert re.search(r'Bound services:\s*\{\}',raw) or re.search(r'BoundServices:\s*\{\}',raw) or re.search(r'mBoundServices=\[\]',raw),'Bound services not visibly empty'
 assert 'com.google.android.marvin.talkback' not in values['enabled-services']

try:
 assert not OUTPUT.exists(),'Output already exists'
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()=='1bb155453a05acca8585d5da15c182c08bda41d0'
 assert subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True)==''
 for section in ['preparedSources','unchangedOracleInputs']:
  for relative,expected in CLAIM[section].items():
   path=ROOT/relative
   assert sha(path)==expected['sha256'] and path.stat().st_size==expected['bytes'],relative
   record['sourceFilesBefore'][relative]=expected
 for section in ['artifacts','buildReceipt']:
  for path,expected in CLAIM[section].items(): assert sha(path)==expected['sha256'],path
 before=capture('before');record['before']=before
 assert before['sdk']=='35'
 locale=before['locale'] if before['locale'] not in ('null','') else before['persist-locale'] or before['product-locale']
 assert locale.lower().startswith('en'),locale
 assert_reader_off(before)
 record.update(status='RUNNING',preflightPassed=True,nativeInvocations=1,nativeStartedUTC=now())
 save();print('PREFLIGHT_PASS. Starting exactly one editing invocation covering the four original groups.',flush=True)
 argv=['scripts/e2e-android.py','--serial',SERIAL,'--scenario','editing','--artifacts',str(ARTIFACTS),'--build-receipt',str(ARTIFACTS/'app-build-receipt.json'),'--language','en','--output',str(OUTPUT)]
 record['argv']=argv;save()
 sys.path.insert(0,str(ROOT/'scripts'))
 sys.argv=argv
 try:
  runpy.run_path(str(ROOT/'scripts/e2e-android.py'),run_name='__main__')
  code=0
 except SystemExit as error:
  code=error.code if isinstance(error.code,int) else 1
 record.update(nativeExitCode=code,nativeFinishedUTC=now(),status='PASS' if code==0 else 'FAIL')
except BaseException as error:
 record.update(status='PREFLIGHT_FAIL' if record['nativeInvocations']==0 else 'INFRA_ERROR',errorType=type(error).__name__,error=str(error))
 (CONTROL/'driver-error.txt').write_text(traceback.format_exc())
 print(record['status'],type(error).__name__,str(error),flush=True)
finally:
 try:
  if record.get('before'):
   after=capture('after');record['after']=after
   assert_reader_off(after)
   setting_keys=['wm-size','wm-density','font-scale','accelerometer-rotation','user-rotation','accessibility-enabled','enabled-services']
   differences={key:{'before':record['before'][key],'after':after[key]} for key in setting_keys if record['before'][key]!=after[key]}
   record['settingsDifferences']=differences
   assert not differences,differences
   record['anrChanged']=record['before']['last-anr']!=after['last-anr']
   for section in ['preparedSources','unchangedOracleInputs']:
    for relative,expected in CLAIM[section].items():
     path=ROOT/relative
     current={'bytes':path.stat().st_size,'sha256':sha(path)}
     record['sourceFilesAfter'][relative]=current
     assert current==expected,relative
   if record['nativeInvocations']:
    stopped=adb('shell','am','force-stop',PACKAGE)
    record['cleanup']={'operation':'am force-stop only owned isolated app after evidence/readback','stdout':stopped.stdout,'utc':now(),'pidAfter':adb('shell','pidof',PACKAGE,check=False).stdout.strip()}
    assert record['cleanup']['pidAfter']==''
 except BaseException as error:
  record['cleanupError']={'type':type(error).__name__,'error':str(error)}
  (CONTROL/'cleanup-error.txt').write_text(traceback.format_exc())
  if record['status']=='PASS': record['status']='CLEANUP_FAIL'
 record.update(finishedUTC=now(),elapsedSeconds=time.monotonic()-started)
 save();command_log.close()
 print(json.dumps({'status':record['status'],'nativeInvocations':record['nativeInvocations'],'nativeExitCode':record.get('nativeExitCode'),'elapsedSeconds':record['elapsedSeconds'],'control':str(CONTROL),'output':str(OUTPUT)}),flush=True)
sys.exit(0 if record['status']=='PASS' else 1)
