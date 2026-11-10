import base64, datetime as dt, hashlib, importlib.util, json, pathlib, plistlib, subprocess, time, traceback
ROOT = pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-fileprovider-error-chain')
OUT = ROOT/'outputs/ios-provider-existing-group-ad28-002'
OLD = ROOT/'outputs/ios-provider-diagnostic-ad28-001'
ART = ROOT/'outputs/ios-fileprovider-fixture-11fc44d5/artifact'
APPID='thalesmms.logisim.validation.revocationfixture'
EXTID=APPID+'.provider'
GROUP='group.'+APPID
UDID='AD28C68F-21D3-4FA6-BCD7-383AE2A1D372'
GP=pathlib.Path('/Users/thales/Library/Developer/CoreSimulator/Devices')/UDID/'data/Containers/Shared/AppGroup/5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4'
EXPECTED_MANIFEST='bda24c74410814276f00474a689ae95508deb7330cc0eacae9f55bf54ec7388a'
BACKEND='54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88'
OUT.mkdir(parents=True,exist_ok=False)
records=[]
def now(): return dt.datetime.now(dt.timezone.utc).isoformat()
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def save(name,obj): (OUT/name).write_text(json.dumps(obj,indent=2,default=str)+'\n')
def run(name,args,check=True,timeout=30):
    record={'name':name,'argv':list(map(str,args)),'startedUTC':now()}
    records.append(record);save('commands.json',records)
    result=subprocess.run(record['argv'],capture_output=True,timeout=timeout)
    for stream in ['stdout','stderr']:
        data=getattr(result,stream);p=OUT/(name+'.'+stream);p.write_bytes(data)
        record[stream]={'path':p.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    record.update({'finishedUTC':now(),'exitCode':result.returncode});save('commands.json',records)
    if check and result.returncode: raise RuntimeError(name+' exit '+str(result.returncode))
    return result
spec=importlib.util.spec_from_file_location('artifact_reader',ROOT/'scripts/run-ios-accessibility.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
manifest=json.loads((ART/'artifact.json').read_text())
APP=pathlib.Path(manifest['app']['path'])
def bundle(name,path):
    expected=manifest[name]
    actual=helper.artifact(path,expected['bundle_id'])
    for key in ['bundle_tree_sha256','executable_sha256','version','build']: assert actual[key]==expected[key],(name,key,actual[key])
    actual['files']=[{'path':str(p.relative_to(path)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(path.rglob('*')) if p.is_file()]
    return actual
def group_snapshot(label):
    assert GP.is_dir() and not GP.is_symlink()
    mp=GP/'.com.apple.mobile_container_manager.metadata.plist'
    meta=plistlib.loads(mp.read_bytes())
    assert meta['MCMMetadataIdentifier']==GROUP
    assert meta['MCMMetadataCreator']==APPID
    assert meta['MCMMetadataUUID']=='C4A78BD1-44B3-4590-B045-A3AC1883D566'
    sp=GP/'RevocationFixture/fixture-state.json'
    data=sp.read_bytes();obj=json.loads(data)
    contents=base64.b64decode(obj['contents'],validate=True)
    assert len(contents)==552 and hashlib.sha256(contents).hexdigest()==BACKEND
    assert obj['state']=={'schemaVersion':1,'authenticationAllowed':True,'backendSHA256':BACKEND,'backendByteCount':552,'generation':1}
    (OUT/(label+'-fixture-state.json')).write_bytes(data)
    (OUT/(label+'-group-metadata.plist')).write_bytes(mp.read_bytes())
    result={'queriedUTC':now(),'path':str(GP),'metadata':meta,'metadataSHA256':sha(mp),'directoryBirthUTC':dt.datetime.fromtimestamp(GP.stat().st_birthtime,dt.timezone.utc).isoformat(),'stateSHA256':hashlib.sha256(data).hexdigest(),'backendBytes':len(contents),'backendSHA256':hashlib.sha256(contents).hexdigest(),'state':obj['state'],'events':obj['events']}
    save(label+'-group.json',result);return result

def checked_installed(label):
    p=pathlib.Path(run(label+'-installed-path',['xcrun','simctl','get_app_container',UDID,APPID,'app']).stdout.decode().strip())
    value={'app':bundle('app',p),'provider':bundle('provider',p/'PlugIns/RevocationProvider.appex')}
    for kind,path in [('app',p),('provider',p/'PlugIns/RevocationProvider.appex')]:
        run(label+'-'+kind+'-verify',['codesign','--verify','--deep','--strict','--verbose=2',path])
        ent=run(label+'-'+kind+'-entitlements',['codesign','--display','--entitlements',':-',path])
        assert plistlib.loads(ent.stdout)=={'com.apple.security.application-groups':[GROUP]}
    save(label+'-installed.json',value);return value

def device(label):
    all_devices=json.loads(run(label+'-device-query',['xcrun','simctl','list','devices','-j']).stdout)
    matches=[dict(item,runtime=runtime) for runtime,items in all_devices['devices'].items() for item in items if item['udid']==UDID]
    assert len(matches)==1
    value=matches[0];assert value['state']=='Booted' and value['deviceTypeIdentifier']=='com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-M5-12GB'
    assert value['runtime']=='com.apple.CoreSimulator.SimRuntime.iOS-27-0'
    runtimes=json.loads(run(label+'-runtime-query',['xcrun','simctl','list','runtimes','-j']).stdout)['runtimes']
    runtime=next(r for r in runtimes if r['identifier']==value['runtime'])
    assert runtime['version']=='27.0' and runtime['buildversion']=='24A434'
    result={'device':value,'runtime':{k:runtime[k] for k in ['identifier','version','buildversion','isAvailable','supportedArchitectures']}};save(label+'-device.json',result);return result

start=now();test_exit=None
save('scope.json',{'status':'PREPARING','startedUTC':start,'simulator':UDID,'fixtureSource':'11fc44d53b229558fbfc57b66e0b2f2d11a0037b','harnessSource':'6630378c89a0c9e0a6b15ee8b6ec275a5cd1aaca','plan':'One exact full-bundle reinstall with the existing group, then the unchanged one Prepare/Register/Inspect UI case. No build, signing, entitlements, uninstall, erase, retry, Main, B25 or17e. New discovery logs are evidence, not a public setup API.','priorRun':str(OLD),'physicalDevice':False})
try:
    assert sha(ART/'artifact.json')==EXPECTED_MANIFEST
    save('source-artifact.json',manifest)
    assert bundle('app',APP)['bundle_tree_sha256']=='8b2577dbe1c2f1e3233448010f82d7399b9f41d8fa52f8b4e73cc81ea1d34e2a'
    bundle('provider',APP/'PlugIns/RevocationProvider.appex')
    source=run('fixture-source',['git','-C',ROOT,'rev-parse','HEAD']).stdout.decode().strip();assert source==manifest['source']
    assert not run('fixture-source-status',['git','-C',ROOT,'status','--porcelain']).stdout
    before_device=device('before');before_app=checked_installed('before');before=group_snapshot('before')
    assert len(before['events'])==3 and before['events'][-1]['kind']=='domain.registration.failed'
    assert before['events'][-1]['errorDiagnostic']['chain'][1]['code']==-2014
    compiled=OLD/'DerivedData/Build/Products/Debug-iphonesimulator/FileProviderBootstrapTests-Runner.app/PlugIns/FileProviderBootstrapTests.xctest'
    assert compiled.is_dir()
    assert sha(compiled/'ProvenanceSignedFixture.json')==EXPECTED_MANIFEST
    staged=OLD/'harness/FileProviderBootstrapTests.swift'
    current=pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-fileprovider-bootstrap-error-chain/scripts/ios_fileprovider_bootstrap/FileProviderBootstrapTests.swift')
    assert staged.read_bytes()==current.read_bytes()
    save('preflight.json',{'status':'PASS','atUTC':now(),'manifestSHA256':EXPECTED_MANIFEST,'device':before_device,'app':before_app,'group':before,'compiledHarness':str(compiled),'stagedHarnessSHA256':sha(staged),'compiledHarnessFiles':[{'path':str(p.relative_to(compiled)),'sha256':sha(p)} for p in sorted(compiled.rglob('*')) if p.is_file()]})
    install_start=now()
    run('reinstall-exact-fixture',['xcrun','simctl','install',UDID,APP])
    after_install=group_snapshot('after-install')
    assert after_install['path']==before['path'] and after_install['metadataSHA256']==before['metadataSHA256'] and after_install['stateSHA256']==before['stateSHA256']
    checked_installed('after-install')
    time.sleep(6)
    predicate='eventMessage CONTAINS "'+APPID+'" OR eventMessage CONTAINS "'+EXTID+'"'
    run('new-discovery-after-reinstall',['xcrun','simctl','spawn',UDID,'log','show','--style','json','--info','--debug','--start',dt.datetime.fromisoformat(install_start).strftime('%Y-%m-%d %H:%M:%S%z'),'--timezone','UTC','--predicate',predicate],timeout=45)
    old_execution=json.loads((OLD/'execution.json').read_text())['command']
    command=[str(OUT/'bootstrap.xcresult') if str(v)==str(OLD/'bootstrap.xcresult') else v for v in old_execution]
    execution={'command':command,'started':now(),'scope':'One authorized exact-bundle reinstall and unchanged Prepare/Register/Inspect on pre-existing-group AD28; no build or retries.'}
    save('execution.json',execution)
    with (OUT/'test.log').open('w') as stream:
        process=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT)
        save('test-process.json',{'pid':process.pid,'startedUTC':now(),'command':command})
        print(json.dumps({'status':'TEST_STARTED','pid':process.pid,'output':str(OUT)}),flush=True)
        test_exit=process.wait()
    execution.update({'exit_code':test_exit,'finished':now()});save('execution.json',execution)
    print(json.dumps({'status':'TEST_TERMINAL','exitCode':test_exit,'output':str(OUT)}),flush=True)
except Exception as error:
    save('interruption.json',{'type':type(error).__name__,'message':str(error),'traceback':traceback.format_exc(),'atUTC':now()})
    print(traceback.format_exc(),flush=True)
finally:
    try:
        run('fixture-terminate',['xcrun','simctl','terminate',UDID,APPID],check=False)
        checked_installed('after');group_snapshot('after');device('after')
        run('full-differential-history',['xcrun','simctl','spawn',UDID,'log','show','--style','json','--info','--debug','--start',dt.datetime.fromisoformat(start).strftime('%Y-%m-%d %H:%M:%S%z'),'--timezone','UTC','--predicate','eventMessage CONTAINS "'+APPID+'" OR eventMessage CONTAINS "'+EXTID+'"'],timeout=45)
        run('xcresult-summary',['xcrun','xcresulttool','get','test-results','summary','--path',OUT/'bootstrap.xcresult'],check=False)
        run('export-attachments',['xcrun','xcresulttool','export','attachments','--path',OUT/'bootstrap.xcresult','--output-path',OUT/'attachments'],check=False)
    except Exception as error:
        save('postflight-error.json',{'error':str(error),'traceback':traceback.format_exc()})
    save('terminal.json',{'finishedUTC':now(),'testExitCode':test_exit,'fixtureTerminated':True,'noRetry':True,'deviceReturned':UDID,'status':'PASS' if test_exit==0 else 'FAIL' if test_exit is not None else 'SETUP_INTERRUPTED'})
