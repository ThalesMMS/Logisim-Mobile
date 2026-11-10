from pathlib import Path
from datetime import datetime,timezone
import sys,importlib.util,subprocess,json,time,threading,hashlib,xml.etree.ElementTree as ET,shlex,shutil
root=Path('/Users/thales/GitHub/logisim-mobile-worktrees/performance-coldrestore-04a')
out=Path('/tmp/logisim-coldrestore-output').read_text().strip(); out=Path(out)
adb='/Users/thales/Library/Android/sdk/platform-tools/adb'; serial='emulator-5558'; pkg='thalesmms.logisim.mobile.e2e'
harness=Path('/Users/thales/GitHub/logisim-mobile-worktrees/android-e2e-release/scripts')
sys.path.insert(0,str(harness))
spec=importlib.util.spec_from_file_location('cold_harness',harness/'e2e-android.py'); mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
events=[]; lock=threading.Lock(); ready=threading.Event(); sigquit=False; launch_ns=None

def event(name,**fields):
 with lock:
  events.append(dict(event=name,utc=datetime.now(timezone.utc).isoformat(),monotonicNs=time.monotonic_ns(),**fields))
  (out/'events.json').write_text(json.dumps(events,indent=2)+'\n')

def cmd(*parts,text=True,timeout=15):
 before=time.monotonic_ns();p=subprocess.run([adb,'-s',serial,*parts],capture_output=True,text=text,timeout=timeout)
 event('command',argv=[adb,'-s',serial,*parts],beforeMonotonicNs=before,returncode=p.returncode)
 if p.returncode:raise RuntimeError(str(p.stderr))
 return p.stdout

class ObservedUi(mod.AndroidUi):
 def screen(self,timeout=45):
  global sigquit
  before=time.monotonic_ns()
  try:
   value=super().screen(timeout=timeout)
  except BaseException as e:
   event('hierarchy-error',requestedTimeout=timeout,beforeMonotonicNs=before,error=str(e));raise
  elapsed=(time.monotonic_ns()-launch_ns)/1e9
  text=ET.tostring(value,encoding='unicode')
  loading='Carregando projeto' in text or 'Loading project' in text
  (out/f'ui-{len(events):04d}.xml').write_text(text)
  event('hierarchy',elapsedSeconds=elapsed,loading=loading,beforeMonotonicNs=before)
  if elapsed>=12 and loading and not sigquit:
   pids=cmd('shell','pidof',pkg).split()
   if len(pids)==1 and pids[0].isdigit():
    sigquit=True; event('sigquit-loading',pid=int(pids[0]),elapsedSeconds=elapsed)
    cmd('shell','run-as',pkg,'kill','-3',pids[0])
  return value

def screenshots():
 for second in [12,30]:
  time.sleep(max(0,second-(time.monotonic_ns()-launch_ns)/1e9))
  try:
   data=cmd('exec-out','screencap','-p',text=False,timeout=5)
   (out/f'screen-{second:02d}s.png').write_bytes(data)
   event('screenshot',targetSeconds=second,ready=ready.is_set())
   print('SCREENSHOT',second,str(out/f'screen-{second:02d}s.png'),flush=True)
  except Exception as e:event('screenshot-error',targetSeconds=second,error=str(e))

observer=subprocess.Popen([sys.executable,str(root/'scripts/profile-android-readiness.py'),'--adb',adb,'--serial',serial,'--package',pkg,'--source-revision','04a0b34a38689932a3f3aaed7f4ea5cb6b63c8db','--expected-apk-sha256','e93a285e3294f91f971a0cc9c80b1f2cd51dc29fa0bdf7989a8ca3f2c38bb5c3','--initial-pid','8880','--seconds','75','--output',str(out/'passive')],stdout=(out/'passive.log').open('w'),stderr=subprocess.STDOUT)
for _ in range(100):
 sample=out/'passive/sample-0000.txt'
 if sample.exists() and 'PROCESS:8880\n' in sample.read_text():break
 if observer.poll() is not None:raise RuntimeError('Passive observer failed before launch; no stop/launch performed')
 time.sleep(.1)
else:raise RuntimeError('No validated baseline process probe; no stop/launch performed')
event('baseline-probe-ready',observerPid=observer.pid)
log=(out/'app-logcat.txt').open('w')
logger=subprocess.Popen([adb,'-s',serial,'logcat','--uid=10207','-v','epoch'],stdout=log,stderr=subprocess.STDOUT)
ui=ObservedUi(adb,serial,pkg,out/'harness');scenarios=mod.Scenarios(ui)
provenance={'harnessSources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [harness/'e2e-android.py',harness/'android_ui.py',harness/'android_editing_scenarios.py',harness/'user_scenario_contract.py']},'observerSourceSha256':hashlib.sha256((root/'scripts/profile-android-readiness.py').read_bytes()).hexdigest(),'initialPid':8880,'apkSha256':'e93a285e3294f91f971a0cc9c80b1f2cd51dc29fa0bdf7989a8ca3f2c38bb5c3','sourceRevision':'04a0b34a38689932a3f3aaed7f4ea5cb6b63c8db'}
(out/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
shutil.copyfile(__file__,out/'capture-coldrestore-once.py')
cmd('shell','am','force-stop',pkg)
launch_ns=time.monotonic_ns();event('launch-request',deadlineSeconds=30)
thread=threading.Thread(target=screenshots,daemon=True);thread.start()
started=cmd('shell','am','start','-W','-n',pkg+'/dev.logisim.mobile.MainActivity',timeout=15)
(out/'am-start.txt').write_text(started); event('am-start-returned')
try:
 value=scenarios.wait_editor(timeout=30)
 ready.set(); event('editor-ready',elapsedSeconds=(time.monotonic_ns()-launch_ns)/1e9)
 (out/'ready.xml').write_text(ET.tostring(value,encoding='unicode'))
 print('READINESS PASS',flush=True)
except Exception as e:
 event('editor-timeout',elapsedSeconds=(time.monotonic_ns()-launch_ns)/1e9,error=str(e))
 print('READINESS FAIL',str(e),flush=True)
thread.join(timeout=5)
observer.wait(timeout=90)
logger.terminate();logger.wait(timeout=5);log.close()
(out/'private-file-hashes-after.txt').write_text(cmd('shell','run-as',pkg,'sh','-c',shlex.quote('find files -type f -exec sha256sum {} \\;')))
(out/'lastanr.txt').write_text(cmd('shell','dumpsys','activity','lastanr'))
print('CAPTURE COMPLETE',out,flush=True)
