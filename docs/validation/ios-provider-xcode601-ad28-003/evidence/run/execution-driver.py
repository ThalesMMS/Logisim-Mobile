import base64, datetime as dt, hashlib, importlib.util, json, pathlib, plistlib, subprocess, time, traceback
ROOT = pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-provider-xcode-signing')
LEGACY_ROOT = pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-fileprovider-error-chain')
PREP = pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-fileprovider-bootstrap-xcode-601/outputs/ios-provider-xcode601-bootstrap-preparation-001')
HARNESS_ROOT = PREP.parents[1]
HARNESS_PIN = '046ebd30c0f71d7f6bf65ca80ec93b925e772792'
REVIEW_SHA = '254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8'
OUT = ROOT/'outputs/ios-provider-xcode601-ad28-003'
OLD = LEGACY_ROOT/'outputs/ios-provider-diagnostic-ad28-001'
PRIOR = LEGACY_ROOT/'outputs/ios-provider-existing-group-ad28-002'
ART = ROOT/'outputs/ios-fileprovider-fixture-601cc373/artifact'
LEGACY_ART = LEGACY_ROOT/'outputs/ios-fileprovider-fixture-11fc44d5/artifact'
APPID='thalesmms.logisim.validation.revocationfixture'
EXTID=APPID+'.provider'
GROUP='group.'+APPID
UDID='AD28C68F-21D3-4FA6-BCD7-383AE2A1D372'
GP=pathlib.Path('/Users/thales/Library/Developer/CoreSimulator/Devices')/UDID/'data/Containers/Shared/AppGroup/5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4'
EXPECTED_MANIFEST='c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159'
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
manifest=json.loads((ART/'artifact.json').read_text())
legacy_manifest=json.loads((LEGACY_ART/'artifact.json').read_text())
APP=pathlib.Path(manifest['app']['path'])
reader_path=ART/'artifact-reader.py'
reader_bytes=reader_path.read_bytes()
assert hashlib.sha256(reader_bytes).hexdigest()==manifest['artifactReaderSHA256']
spec=importlib.util.spec_from_file_location('artifact_reader',reader_path)
helper=importlib.util.module_from_spec(spec)
exec(compile(reader_bytes,str(reader_path),'exec'),helper.__dict__)
section_path=ART/'Fixture/simulator_entitlements.py'
section_bytes=section_path.read_bytes()
assert hashlib.sha256(section_bytes).hexdigest()==manifest['simulatorEntitlementVerifierSHA256']
section_spec=importlib.util.spec_from_file_location('section_reader',section_path)
section_reader=importlib.util.module_from_spec(section_spec)
exec(compile(section_bytes,str(section_path),'exec'),section_reader.__dict__)
review_bytes=(PREP/'harness/ProvenanceArtifactReview.json').read_bytes()
assert hashlib.sha256(review_bytes).hexdigest()==REVIEW_SHA
review=json.loads(review_bytes)
assert review['manifestSHA256']==EXPECTED_MANIFEST and review['source']==manifest['source']
assert review['scope']=='LOCAL_SIMULATOR_ONLY' and review['operation']=='ONE_PREPARE_REGISTER_INSPECT'
assert review['developerTeamApproved'] is False and review['releaseApproved'] is False
assert review['appTreeSHA256']==manifest['app']['bundle_tree_sha256']
assert review['providerTreeSHA256']==manifest['provider']['bundle_tree_sha256']
assert review['applicationIdentifierPrefix']=='P4VGX44GD8.'
assert review['approvedSimulatedEntitlements']=={
    'App':{'application-identifier':'P4VGX44GD8.'+APPID,'com.apple.security.application-groups':[GROUP]},
    'Provider':{'application-identifier':'P4VGX44GD8.'+EXTID,'com.apple.security.application-groups':[GROUP]}}
(OUT/'ProvenanceArtifactReview.json').write_bytes(review_bytes)
(OUT/'source-artifact.json').write_bytes((ART/'artifact.json').read_bytes())
def verify_sections(label,bundle_path,role):
    v=section_reader.verify_bundle(bundle_path,manifest[role.lower()]['bundle_id'],GROUP,OUT,label+'-'+role)
    section_reader.verify_packaging_inputs(v,bundle_path,ART/'DerivedData',OUT,label+'-'+role)
    assert v['sectionOwners']==['main'] and v['mainHasSectionPair'] is True
    assert v['simulatedEntitlements']==review['approvedSimulatedEntitlements'][role]
    assert v['generatedAdditionalKeys']=={'application-identifier':'P4VGX44GD8.'+manifest[role.lower()]['bundle_id']}
    save(label+'-'+role+'-sections.json',v)
    return v
def bundle(name,path,expected_manifest=None):
    expected=(manifest if expected_manifest is None else expected_manifest)[name]
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

def checked_installed(label,expected_manifest=None):
    expected_manifest=manifest if expected_manifest is None else expected_manifest
    p=pathlib.Path(run(label+'-installed-path',['xcrun','simctl','get_app_container',UDID,APPID,'app']).stdout.decode().strip())
    value={'app':bundle('app',p,expected_manifest),'provider':bundle('provider',p/'PlugIns/RevocationProvider.appex',expected_manifest)}
    for kind,path in [('app',p),('provider',p/'PlugIns/RevocationProvider.appex')]:
        run(label+'-'+kind+'-verify',['codesign','--verify','--deep','--strict','--verbose=2',path])
        ent=run(label+'-'+kind+'-entitlements',['codesign','--display','--entitlements',':-',path])
        actual_entitlements=plistlib.loads(ent.stdout) if ent.stdout else {}
        expected_entitlements={} if expected_manifest is manifest else {'com.apple.security.application-groups':[GROUP]}
        assert actual_entitlements==expected_entitlements
        if expected_manifest is manifest:verify_sections(label,path,'App' if kind=='app' else 'Provider')
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
save('scope.json',{'status':'PREPARING','startedUTC':start,'simulator':UDID,'fixtureSource':'601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd','harnessSource':HARNESS_PIN,'plan':'One installation of root-reviewed 601 Simulator fixture on existing-group AD28, then unchanged one Prepare/Register/Inspect UI flow. No signing changes, uninstall, erase, retry, Main, B25 or17e. Standalone harness build is preserved separately; no fixture rebuild.','priorRun':str(OLD),'physicalDevice':False})
try:
    assert sha(ART/'artifact.json')==EXPECTED_MANIFEST
    (OUT/'source-artifact.json').write_bytes((ART/'artifact.json').read_bytes())
    assert bundle('app',APP)['bundle_tree_sha256']=='e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5'
    bundle('provider',APP/'PlugIns/RevocationProvider.appex')
    source=run('fixture-source',['git','-C',ROOT,'rev-parse','HEAD']).stdout.decode().strip();assert source==manifest['source']
    assert not run('fixture-source-status',['git','-C',ROOT,'status','--porcelain']).stdout
    before_device=device('before');before_app=checked_installed('before',legacy_manifest);before=group_snapshot('before')
    assert len(before['events'])==6 and before['events'][-1]['kind']=='domain.registration.failed'
    assert before['events'][-1]['errorDiagnostic']['chain'][1]['code']==-2014
    assert before['events']==json.loads((PRIOR/'after-fixture-state.json').read_bytes())['events']
    assert run('harness-source',['git','-C',HARNESS_ROOT,'rev-parse','HEAD']).stdout.decode().strip()==HARNESS_PIN
    assert not run('harness-source-status',['git','-C',HARNESS_ROOT,'status','--porcelain']).stdout
    assert json.loads((PREP/'build-command.json').read_text())['exitCode']==0
    compiled=PREP/'DerivedData/Build/Products/Debug-iphonesimulator/FileProviderBootstrapTests-Runner.app/PlugIns/FileProviderBootstrapTests.xctest'
    assert compiled.is_dir()
    assert sha(compiled/'ProvenanceSignedFixture.json')==EXPECTED_MANIFEST
    assert sha(compiled/'ProvenanceArtifactReview.json')==REVIEW_SHA
    staged=PREP/'harness/FileProviderBootstrapTests.swift'
    current=HARNESS_ROOT/'scripts/ios_fileprovider_bootstrap/FileProviderBootstrapTests.swift'
    assert staged.read_bytes()==current.read_bytes()
    for source_file in ['FileProviderBootstrapTests.swift','FixtureArtifactAdmission.swift','project.yml','ProvenanceSignedFixture.json','ProvenanceArtifactReview.json']:
        assert (PREP/'harness'/source_file).read_bytes()==(HARNESS_ROOT/'scripts/ios_fileprovider_bootstrap'/source_file).read_bytes()
    verify_sections('candidate-source',APP,'App')
    verify_sections('candidate-source',APP/'PlugIns/RevocationProvider.appex','Provider')
    save('preflight.json',{'status':'PASS','atUTC':now(),'manifestSHA256':EXPECTED_MANIFEST,'device':before_device,'app':before_app,'group':before,'compiledHarness':str(compiled),'stagedHarnessSHA256':sha(staged),'compiledHarnessFiles':[{'path':str(p.relative_to(compiled)),'sha256':sha(p)} for p in sorted(compiled.rglob('*')) if p.is_file()]})
    install_start=now()
    run('install-root-reviewed-fixture',['xcrun','simctl','install',UDID,APP])
    after_install=group_snapshot('after-install')
    assert after_install['path']==before['path'] and after_install['metadata']['MCMMetadataUUID']==before['metadata']['MCMMetadataUUID']
    assert after_install['state']==before['state'] and after_install['events'][:len(before['events'])]==before['events']
    checked_installed('after-install')
    time.sleep(6)
    predicate='eventMessage CONTAINS "'+APPID+'" OR eventMessage CONTAINS "'+EXTID+'"'
    run('new-discovery-after-install',['xcrun','simctl','spawn',UDID,'log','show','--style','json','--info','--debug','--start',dt.datetime.fromisoformat(install_start).strftime('%Y-%m-%d %H:%M:%S%z'),'--timezone','UTC','--predicate',predicate],timeout=45)
    old_execution=json.loads((OLD/'execution.json').read_text())['command']
    command=[str(OUT/'bootstrap.xcresult') if str(v)==str(OLD/'bootstrap.xcresult') else str(PREP/'harness/LogisimFileProviderBootstrap.xcodeproj') if str(v)==str(OLD/'harness/LogisimFileProviderBootstrap.xcodeproj') else str(PREP/'DerivedData') if str(v)==str(OLD/'DerivedData') else v for v in old_execution]
    execution={'command':command,'started':now(),'scope':'One authorized root-reviewed Simulator fixture601 installation and unchanged Prepare/Register/Inspect UI flow on existing-group AD28; no fixture rebuild, retry or erase.'}
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
    summary_path=OUT/'xcresult-summary.stdout'
    summary=json.loads(summary_path.read_bytes()) if summary_path.is_file() and summary_path.stat().st_size else {}
    qualified_counts=summary.get('totalTestCount')==1 and summary.get('passedTests')==1 and summary.get('failedTests')==0 and summary.get('skippedTests')==0
    save('terminal.json',{'qualifiedCounts':qualified_counts,'fixtureSource':manifest['source'],'harnessSource':HARNESS_PIN,'rootReviewSHA256':REVIEW_SHA,'finishedUTC':now(),'testExitCode':test_exit,'fixtureTerminated':True,'noRetry':True,'deviceReturned':UDID,'status':'PASS' if test_exit==0 and qualified_counts else 'FAIL' if test_exit is not None else 'SETUP_INTERRUPTED'})
