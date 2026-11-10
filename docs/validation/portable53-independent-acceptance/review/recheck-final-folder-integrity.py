from pathlib import Path
import hashlib,json,tarfile,subprocess
repo='/Users/thales/GitHub/logisim-mobile-dev';commit='2832d29b053da7026540dcc63bd62aed1d040845';b=Path(Path('/tmp/logisim-portable53-folder-integrity-path.txt').read_text());native=b/'passed-48824'
def digest(data):return hashlib.sha256(data).hexdigest()
def sha(p):return digest(p.read_bytes())
files={str(p.relative_to(b)) for p in b.rglob('*') if p.is_file()};res={'commit':commit,'root':str(b),'files':len(files),'bytes':sum((b/p).stat().st_size for p in files)}
sums={};errs=[]
for line in (b/'SHA256SUMS').read_text().splitlines():
 s,n=line.split('  ',1)
 if n in sums:errs.append('duplicate '+n)
 sums[n]=s
res['sha256sums']={'entries':len(sums),'matched':sum((b/n).is_file() and sha(b/n)==s for n,s in sums.items()),'mismatches':[n for n,s in sums.items() if not (b/n).is_file() or sha(b/n)!=s],'uncovered':sorted(files-set(sums)),'extraneous':sorted(set(sums)-files),'parse_errors':errs}
chain=json.loads((b/'copy-chain.json').read_text());records={x['path']:x for x in chain['files']};res['copy_chain']={'entries':len(records),'duplicates':len(chain['files'])-len(records),'copied_match':0,'original_match':0,'original_missing':[],'copy_mismatches':[],'original_mismatches':[],'git_sources':[]}
for n,x in records.items():
 if 'source' not in x:
  x['source']='git:'+x['sourceGitRevision']+':'+x['sourceGitPath']
 dest=b/n
 if dest.is_file() and dest.stat().st_size==x['bytes'] and sha(dest)==x['sha256']:res['copy_chain']['copied_match']+=1
 else:res['copy_chain']['copy_mismatches'].append(n)
 if x['source'].startswith('git:'):
  rev,src=x['source'][4:].split(':',1);data=subprocess.check_output(['git','show',rev+':'+src],cwd=repo);res['copy_chain']['git_sources'].append({'path':n,'source':x['source'],'matches':dest.read_bytes()==data and len(data)==x['bytes'] and digest(data)==x['sha256']});res['copy_chain']['original_match']+=int(dest.read_bytes()==data and len(data)==x['bytes'] and digest(data)==x['sha256']);continue
 src=Path(x['source'])
 if not src.is_file():res['copy_chain']['original_missing'].append({'path':n,'source':x['source']})
 elif src.read_bytes()==dest.read_bytes() and sha(src)==x['sha256'] and src.stat().st_size==x['bytes']:res['copy_chain']['original_match']+=1
 else:res['copy_chain']['original_mismatches'].append(n)
r=json.loads((native/'portable-folder-receipt.json').read_text());m=json.loads((native/'manifest.json').read_text());q=json.loads((b/'qualification-summary.json').read_text());res['receipt']={'status':r['status'],'hash':sha(native/'portable-folder-receipt.json'),'hash_matches_summary':sha(native/'portable-folder-receipt.json')==q['nativeReceiptSha256'],'evidence_count':len(r['evidence']),'missing_evidence':[p for p in r['evidence'] if not (native/p).is_file()],'snapshot_count':len(r['workspaceSnapshots']),'missing_snapshots':[p for p in r['workspaceSnapshots'].values() if not (native/p).is_file()],'exports':[],'imported':r['importedProjectId'],'restarted':r['restartedProjectId'],'reopened':r['reopenedProjectId']}
for x in r['exports']:
 p=native/x['path'];res['receipt']['exports'].append({'path':x['path'],'afterRestart':x['afterRestart'],'bytes':p.stat().st_size,'sha256':sha(p),'hash_matches_receipt':sha(p)==x['sha256']})
res['snapshots']={}
for stage,tarname in r['workspaceSnapshots'].items():
 target=b/'snapshots'/stage/'workspace';members={};unsafe=[]
 with tarfile.open(native/tarname) as t:
  for x in t.getmembers():
   if x.isdir():continue
   name=x.name.removeprefix('files/workspace/')
   if not x.isfile() or not x.name.startswith('files/workspace/') or '/' in name or name in members:unsafe.append(x.name);continue
   members[name]=t.extractfile(x).read()
 extracted={str(p.relative_to(target)):p.read_bytes() for p in target.rglob('*') if p.is_file()};res['snapshots'][stage]={'raw_files':len(members),'extracted_files':len(extracted),'names_match':set(members)==set(extracted),'bytes_match':members==extracted,'unsafe_members':unsafe}
res['source_hashes']={n:sha(native/('source-'+n))==s for n,s in m['scenarioSources'].items()};contract=b/'fixtures/user-scenarios/editor-actions-v1.json';res['contract_match']=sha(contract)==m['scenarioContractSha256'];res['manifest_git_head']=m['gitHead'];res['manifest_harness_head']=m['harnessGitHead'];res['installed_artifacts_equal']={x['package']:x['sha256'] for x in m['installedArtifactsBefore']}=={x['package']:x['sha256'] for x in m['installedArtifactsAfter']}
changes=subprocess.check_output(['git','diff-tree','--no-commit-id','--name-status','-r',commit],cwd=repo,text=True).splitlines();res['commit_change_count']=len(changes);res['commit_only_adds_this_package']=all(x.startswith('A\tdocs/validation/portable53-android-folder-pass/') for x in changes)
res['native_results']=json.loads((native/'results.json').read_text());res['sources_without_copy_chain']=sorted(files-set(records)-{'SHA256SUMS','copy-chain.json'});Path('/tmp/logisim-portable53-folder-integrity-results.json').write_text(json.dumps(res,indent=2));print(json.dumps(res,indent=2))
