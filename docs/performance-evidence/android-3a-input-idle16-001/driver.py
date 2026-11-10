#!/usr/bin/env python3
import argparse, datetime, hashlib, importlib.util, inspect, json, os, re, shutil, subprocess, sys, time, traceback
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path('/Users/thales/GitHub/logisim-mobile-worktrees/performance48-native-source-plan')
PIN='2827b19b2041ed98dc5e10f2c9d4a94e07911869'
PRODUCT='3a0a01f1af05dd3fea75d5a1499c762d039c478a'
SERIAL='emulator-5558'; PACKAGE='thalesmms.logisim.mobile.e2e'
ADB=Path('/Users/thales/Library/Android/sdk/platform-tools/adb')
ARTIFACTS=Path('/Users/thales/GitHub/logisim-mobile-dev/outputs/implementation/artifacts/5c2abc60')
CORPUS=Path('/Users/thales/GitHub/logisim-mobile-worktrees/performance/outputs/performance/mobile-corpus-v2-complete')
OUT=ROOT/'outputs/performance/android-3a-input-idle16-001'
EXPECTED={'e2e_apk.apk':'10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973','e2e_test_apk.apk':'19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b','snapshot_apk.apk':'504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c'}
INPUT_HASH='19198a15e4880baccd3d00391f257c766a8be0859daae1ca721d3d8ed7c12a31'
SETTINGS={'system':['accelerometer_rotation','user_rotation','font_scale','system_locales'],'secure':['accessibility_enabled','enabled_accessibility_services','touch_exploration_enabled','show_ime_with_hard_keyboard'],'global':['animator_duration_scale','transition_animation_scale','window_animation_scale']}

def sha(data): return hashlib.sha256(data).hexdigest()
def file_sha(path): return sha(path.read_bytes())
def utc(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(name,value): (OUT/name).write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n')

def source_inputs():
    manifest=CORPUS/'manifest.json'; assert file_sha(manifest)=='295571568422db33a280e9c0a52b322982cfa7f57c10e297fd70a788d7596f58'
    m=json.loads(manifest.read_text()); result={'manifest.json':{'bytes':manifest.stat().st_size,'sha256':file_sha(manifest)}}
    assert {p.name for p in CORPUS.iterdir()}=={'manifest.json',*[x['file'] for x in m['cases']]}
    for case in m['cases']:
        p=CORPUS/case['file']; assert p.stat().st_size==case['bytes'] and file_sha(p)==case['sha256']; result[p.name]={'bytes':p.stat().st_size,'sha256':file_sha(p)}
    return result

sys.path.insert(0,str(ROOT/'scripts'))
spec=importlib.util.spec_from_file_location('native48_e2e_frozen',ROOT/'scripts/e2e-android.py'); e2e=importlib.util.module_from_spec(spec);sys.modules[spec.name]=e2e;spec.loader.exec_module(e2e)
from android_ui import AndroidUi
class AuditUi(AndroidUi):
    def __init__(self,*a,**kw):
        self.calls=[]; super().__init__(*a,**kw);(self.output/'adb').mkdir()
    def _run(self,args,*,text=True,timeout=30,check=True):
        number=len(self.calls)+1;entry={'number':number,'argv':[str(ADB),'-s',self.serial,*map(str,args)],'started_utc':utc(),'started_monotonic_ns':time.monotonic_ns()};self.calls.append(entry)
        try:
            result=super()._run(args,text=text,timeout=timeout,check=False)
            entry['returncode']=result.returncode
            for channel in ('stdout','stderr'):
                data=getattr(result,channel); data=data.encode() if isinstance(data,str) else data
                p=self.output/'adb'/f'{number:04d}-{channel}';p.write_bytes(data);entry[channel]={'file':str(p.relative_to(self.output)),'bytes':len(data),'sha256':sha(data)}
            if check and result.returncode:
                message=result.stderr if text else result.stderr.decode('utf-8',errors='replace')
                raise RuntimeError(f'adb failed ({result.returncode}): {args!r}\n{message[-4000:]}')
            return result
        except Exception as error:
            entry['error']=repr(error);raise
        finally:
            entry['ended_monotonic_ns']=time.monotonic_ns();write('adb-calls.json',self.calls)

def config(ui):
    return {'settings':{domain:{key:ui.adb('shell','settings','get',domain,key).strip() for key in keys} for domain,keys in SETTINGS.items()},'wm_size':ui.adb('shell','wm','size').strip(),'wm_density':ui.adb('shell','wm','density').strip()}

def restore_config(ui,before):
    current=config(ui);actions=[]
    for domain,values in before['settings'].items():
        for key,value in values.items():
            if current['settings'][domain][key]!=value:
                command=['shell','settings','delete',domain,key] if value in ('','null') else ['shell','settings','put',domain,key,value]
                ui.adb(*command);actions.append(command)
    for field,kind in [('wm_size','size'),('wm_density','density')]:
        if current[field]!=before[field]:
            match=re.search(r'Override (?:size|density):\s*(\S+)',before[field]);value=match.group(1) if match else 'reset'
            command=['shell','wm',kind,value];ui.adb(*command);actions.append(command)
    final=config(ui);assert final==before,'Device configuration restoration differs'
    return {'actions':actions,'final':final,'matches_before':True}

def hash_installed(ui):
    return [ui.verify_installed_apk(ARTIFACTS/'e2e_apk.apk'),ui.verify_installed_apk(ARTIFACTS/'e2e_test_apk.apk',PACKAGE+'.test'),ui.verify_installed_apk(ARTIFACTS/'snapshot_apk.apk',ui.snapshot_package)]

def paused_context(s,label):
    root=s.wait_editor();s.canvas(root)
    def matches(key):
        values=set(s.catalog[key].values())
        return [n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('text','').lstrip('▶Ⅱ⏸ ').strip() in values and s.ui.visible_node(root,n)]
    runs,pauses=matches('action_run'),matches('action_pause')
    assert len(runs)==1 and not pauses,f'Need one Run and no Pause, found {len(runs)}/{len(pauses)}'
    ticks=s.simulation_steps(root);assert ticks==0,f'No clock actions expected, counter={ticks}'
    selected=[n.get('resource-id') for n in root.iter('node') if n.get('resource-id','').startswith(('canvas-component:','canvas-wire:')) and n.get('selected')=='true']
    artifacts=s.ui.capture(label,root)
    return {'clock_counter':ticks,'run_nodes':len(runs),'pause_nodes':len(pauses),'selected_canvas_nodes':selected,'artifacts':artifacts,'observation_limit':'Tree and PNG are sequential; paused input-idle is not worker/GC inactivity.'}

def shape(project):
    circuits=project.findall('circuit');assert len(circuits)==1 and circuits[0].get('name')=='gates-16';main=circuits[0]
    assert len(main.findall('comp'))==64 and len(main.findall('wire'))==16
    labels=[n.get('val') for n in main.findall('comp/a[@name="label"]') if n.get('val')]
    assert len(labels)==48 and set(labels)=={f'{kind}-{i}' for kind in ('a','b','out') for i in range(16)}
    return {'main':'gates-16','circuits':1,'components':64,'wires':16,'pin_labels':48}

def host_state(label):
    records=[]
    for name,argv in [('uptime',['uptime']),('vm_stat',['vm_stat']),('processes',['ps','-axo','pid=,ucomm=,pcpu=,rss='])]:
        result=subprocess.run(argv,capture_output=True,text=True);p=OUT/f'host-{label}-{name}.txt';p.write_text(result.stdout+result.stderr);records.append({'argv':argv,'returncode':result.returncode,'file':p.name,'sha256':file_sha(p)})
    return {'utc':utc(),'monotonic_ns':time.monotonic_ns(),'load_average':os.getloadavg(),'records':records,'cohort':'shared_host_not_a_quiet_gate','process_names_may_be_truncated':True}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--execute',action='store_true');args=parser.parse_args()
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==PIN
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True)
    inputs=source_inputs();content=(CORPUS/'independent-16.circ').read_bytes();assert len(content)==16109 and sha(content)==INPUT_HASH;shape(ET.fromstring(content))
    for name,digest in EXPECTED.items():assert file_sha(ARTIFACTS/name)==digest
    probe=e2e.Scenarios(None)
    for key in ['action_run','action_pause','a11y_fit_circuit','status_file_opened','action_copy','action_close','a11y_reset_simulation']:assert key in probe.catalog,key
    for name in ['fixture','incoming','export_xml','click_key','wait_editor','simulation_steps','canvas']:assert callable(getattr(probe,name)),name
    assert inspect.signature(AndroidUi.__init__).parameters.get('focus_snapshots') is not None
    assert not OUT.exists(),'Use a new attempt; no retry or overwrite'
    if not args.execute:
        print(json.dumps({'status':'HOST_VALIDATION_PASS','source':PIN,'native_calls':0,'original_inputs':len(inputs),'artifacts':EXPECTED,'output':str(OUT)}));return 0
    OUT.mkdir(parents=True);shutil.copyfile(__file__,OUT/'driver.py');(OUT/'source-independent-16.circ').write_bytes(content)
    manifest={'schema':1,'status':'PREPARING','source':PIN,'product_source':PRODUCT,'serial':SERIAL,'package':PACKAGE,'case':'one_paused_independent16_input_idle_20s','created_utc':utc(),'driver_sha256':file_sha(OUT/'driver.py'),'original_inputs_before':inputs,'expected_apks':EXPECTED,'sampling_started':False,'native_case_attempts':1,'installs':0,'builds':0,'runtime_scope':'Shared-host emulator process observations only. No benchmark, quiet gate, real-device budget, energy or draw/pan/zoom latency claim.'};write('attempt.json',manifest)
    ui=AuditUi(ADB,SERIAL,PACKAGE,OUT,focus_snapshots=True);s=e2e.Scenarios(ui);before_config=None;primary=None
    try:
        manifest['installed_before']=hash_installed(ui);before_config=config(ui);manifest['configuration_before']=before_config
        secure=before_config['settings']['secure'];assert secure['enabled_accessibility_services'] in ('','null') and secure['accessibility_enabled'] in ('0','','null') and secure['touch_exploration_enabled'] in ('0','','null'),'Reader must already be off'
        ui.adb('shell','am','force-stop',PACKAGE);assert not ui._run(('shell','pidof',PACKAGE),check=False).stdout.strip();manifest['fresh_launch_basis']='Owned e2e app force-stopped and PID absence observed, then stock launch'
        s.launch();uri=s.fixture(content);name=e2e.fixture_document_name(PACKAGE,uri);manifest['fixture_uri']=uri;manifest['fixture_name']=name
        remote=('shell','run-as',PACKAGE+'.test','cat','files/document-fixtures/'+name)
        staged_before=ui.adb(*remote,text=False);assert staged_before==content;manifest['provider_source_before_sha256']=sha(staged_before)
        s.incoming(uri);before,project=s.export_xml('before-idle');manifest['model_before']=shape(project);manifest['canonical_before_sha256']=sha(before.encode())
        s.click_key('a11y_fit_circuit',description=True);manifest['ready_before']=paused_context(s,'ready-before')
        pid=ui.adb('shell','pidof',PACKAGE).strip();assert re.fullmatch('[1-9][0-9]*',pid);manifest['pid_before']=int(pid)
        manifest['host_before']=host_state('before');manifest['sampling_started']=True;manifest['phase']='SAMPLING';write('attempt.json',manifest)
        command=[sys.executable,str(ROOT/'scripts/profile-android-circuit.py'),'--serial',SERIAL,'--package',PACKAGE,'--pid',pid,'--adb',str(ADB),'--expected-apk-sha256',EXPECTED['e2e_apk.apk'],'--fresh-launch-confirmed','--build-commit',PRODUCT,'--base-commit',PRODUCT,'--scenario','idle','--circuit',str(OUT/'source-independent-16.circ'),'--seconds','20','--interval','1','--no-gfx-reset','--output-dir',str(OUT/'metrics')]
        manifest['collector_argv']=command;calls_before=len(ui.calls);manifest['collector_started_utc']=utc();write('attempt.json',manifest)
        print(json.dumps({'status':'SAMPLING','pid':pid,'seconds':20}),flush=True)
        with (OUT/'collector.log').open('w') as log:r=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        manifest['collector_returncode']=r.returncode;manifest['collector_finished_utc']=utc();manifest['ui_driver_calls_during_collector']=len(ui.calls)-calls_before;assert manifest['ui_driver_calls_during_collector']==0
        manifest['host_after']=host_state('after');metrics=json.loads((OUT/'metrics/manifest.json').read_text());manifest['collector_status']=metrics['status']
        assert r.returncode==0, 'Collector failed'
        manifest['ready_after']=paused_context(s,'ready-after');assert ui.adb('shell','pidof',PACKAGE).strip()==pid;manifest['pid_after']=int(pid)
        after,project=s.export_xml('after-idle');manifest['model_after']=shape(project);assert before==after,'Complete canonical XML changed';manifest['canonical_after_sha256']=sha(after.encode())
        staged_after=ui.adb(*remote,text=False);assert staged_after==content;manifest['provider_source_after_sha256']=sha(staged_after)
        manifest['status']='PASS' if metrics['status']=='completed' else 'PARTIAL';manifest['phase']='FINISHED'
    except Exception as error:
        primary=error;manifest['status']='FAIL';manifest['failure']=repr(error);(OUT/'failure.txt').write_text(traceback.format_exc())
    finally:
        cleanup=[]
        for package in [PACKAGE,PACKAGE+'.test',ui.snapshot_package]:
            try:ui.adb('shell','am','force-stop',package);cleanup.append({'package':package,'stopped':True,'pid_after':ui._run(('shell','pidof',package),check=False).stdout.strip()})
            except Exception as error:cleanup.append({'package':package,'error':repr(error)})
        manifest['owned_app_cleanup']=cleanup
        try:
            if before_config is not None:manifest['configuration_restoration']=restore_config(ui,before_config)
            manifest['installed_after']=hash_installed(ui)
            assert manifest['installed_after']==manifest['installed_before']
            assert all(item.get('stopped') and not item.get('pid_after') for item in cleanup)
        except Exception as error:
            manifest['status']='FAIL';manifest['postflight_failure']=repr(error)
        try:manifest['original_inputs_after']=source_inputs();assert manifest['original_inputs_before']==manifest['original_inputs_after'];manifest['original_inputs_unchanged']=True
        except Exception as error:manifest['status']='FAIL';manifest['original_input_failure']=repr(error)
        manifest['finished_utc']=utc();write('attempt.json',manifest)
    print(json.dumps({'status':manifest['status'],'sampling_started':manifest['sampling_started'],'output':str(OUT),'failure':manifest.get('failure'),'postflight_failure':manifest.get('postflight_failure')}),flush=True)
    return 0 if manifest['status'] in ('PASS','PARTIAL') else 1
if __name__=='__main__':raise SystemExit(main())
