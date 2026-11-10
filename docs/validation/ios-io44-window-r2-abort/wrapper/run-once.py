from pathlib import Path
from datetime import datetime, timezone
import hashlib,json,os,re,shutil,signal,subprocess,time
plan=json.loads(Path('/tmp/logisim-ios44-window-r2-plan.json').read_text())
root=Path(plan['harnessRoot']);output=Path(plan['output']);video_path=Path(plan['passiveVideo']['path']);sidecar=video_path.parent
assert not output.exists() and not sidecar.exists()
sidecar.mkdir(parents=True)
shutil.copytree('/tmp/logisim-ios44-window-r2-admission',sidecar/'admission')
shutil.copyfile('/tmp/logisim-ios44-window-r2-plan.json',sidecar/'plan.json')
shutil.copyfile('/tmp/logisim-ios44-window-r2-plan.md',sidecar/'plan.md')
source_copy=sidecar/'frozen-sources'
for rel,expected in plan['sourceFiles'].items():
 src=root/rel;assert hashlib.sha256(src.read_bytes()).hexdigest()==expected
 dst=source_copy/rel;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
def utc():return datetime.now(timezone.utc).isoformat()
receipt={'scope':'single approved iOS I/O call plus passive recording; no input from recorder','startedUTC':utc(),'hostMonotonicBeforeRecording':time.monotonic(),'simulator':plan['simulator'],'argv':plan['argv'],'videoArgv':plan['passiveVideo']['startArgv'],'sourceHead':plan['harnessHead'],'selectedTests':plan['selectedTests'],'caseEvents':[],'infraAbort':None,'clockAssociation':'NOT_QUALIFIED','functionalHeldQualification':'NOT_INFERRED'}
def save(): (sidecar/'wrapper-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
video_log=(sidecar/'video.stderr.log').open('w');runner_log=(sidecar/'wrapper.log').open('w')
recorder=None;runner=None
patterns=[r'Restarting after unexpected exit',r'Lost connection to testmanagerd',r'Failed to establish communication with the test runner',r'Simulator device failed to (?:boot|launch)',r'(?:Metal|backboardd).*(?:aborted|crashed|failed to initialize|not responding|connection invalid|terminated unexpectedly)']
try:
 recorder=subprocess.Popen(plan['passiveVideo']['startArgv'],stdout=video_log,stderr=subprocess.STDOUT,start_new_session=True)
 receipt.update(videoPid=recorder.pid,videoStartRequestedUTC=utc());save()
 time.sleep(0.8)
 if recorder.poll() is not None:raise RuntimeError('Passive recorder exited before the test call; preserve without fallback')
 runner=subprocess.Popen(plan['argv'],cwd=root,stdout=runner_log,stderr=subprocess.STDOUT,start_new_session=True)
 receipt.update(runnerPid=runner.pid,runnerStartedUTC=utc());save();print(json.dumps({'stage':'STARTED','runnerPid':runner.pid,'videoPid':recorder.pid,'output':str(output)}),flush=True)
 seen=set();positions={}
 while runner.poll() is None:
  for name in ('build.log','test.log'):
   path=output/name
   if not path.exists():continue
   with path.open(errors='replace') as f:f.seek(positions.get(name,0));new=f.read();positions[name]=f.tell()
   for line in new.splitlines():
    if 'Test Case ' in line and any(x in line for x in (' started.',' passed ',' failed ')) and line not in seen:
     seen.add(line);event={'observedHostUTC':utc(),'source':name,'line':line};receipt['caseEvents'].append(event);save();print(json.dumps(event),flush=True)
    if receipt['infraAbort'] is None and any(re.search(pattern,line,re.I) for pattern in patterns):
     receipt['infraAbort']={'observedHostUTC':utc(),'source':name,'line':line};save();print(json.dumps({'stage':'INFRA_ABORT','line':line}),flush=True)
     os.killpg(runner.pid,signal.SIGINT)
  if receipt['infraAbort'] is not None:
   try:runner.wait(timeout=45)
   except subprocess.TimeoutExpired:os.killpg(runner.pid,signal.SIGTERM)
   break
  time.sleep(1)
 receipt.update(runnerExit=runner.wait(),runnerTerminalUTC=utc());save()
except BaseException as error:
 receipt['wrapperError']={'type':type(error).__name__,'message':str(error)}
 if runner is not None and runner.poll() is None:
  os.killpg(runner.pid,signal.SIGINT)
  try:runner.wait(timeout=45)
  except subprocess.TimeoutExpired:os.killpg(runner.pid,signal.SIGTERM);runner.wait(timeout=15)
finally:
 if recorder is not None and recorder.poll() is None:
  receipt['videoStopRequestedUTC']=utc();os.killpg(recorder.pid,signal.SIGINT)
  try:recorder.wait(timeout=20)
  except subprocess.TimeoutExpired:os.killpg(recorder.pid,signal.SIGTERM);recorder.wait(timeout=10)
 receipt.update(videoExit=recorder.returncode if recorder else None,finishedUTC=utc())
 video_log.close();runner_log.close()
 if video_path.exists():receipt['videoFile']={'path':str(video_path),'bytes':video_path.stat().st_size,'sha256':hashlib.sha256(video_path.read_bytes()).hexdigest()}
 save();print(json.dumps({'stage':'TERMINAL','runnerExit':receipt.get('runnerExit'),'infraAbort':receipt['infraAbort'],'error':receipt.get('wrapperError'),'receipt':str(sidecar/'wrapper-receipt.json')}),flush=True)
raise SystemExit(receipt.get('runnerExit',1) if receipt.get('wrapperError') is None else 1)
