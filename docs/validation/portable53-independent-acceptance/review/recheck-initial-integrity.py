from pathlib import Path
import json,hashlib,tarfile,subprocess
paths=json.loads(Path('/tmp/logisim-portable53-integrity-paths.json').read_text())
res={}
def dig(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def verify(base,records):
 out={'entries':len(records),'matched':0,'missing':[],'mismatches':[]}
 for name,r in records.items():
  p=base/name
  if not p.is_file():out['missing'].append(name);continue
  if dig(p)!=r['sha256'] or p.stat().st_size!=r['bytes']:out['mismatches'].append(name)
  else:out['matched']+=1
 return out
for label,base in paths.items():
 b=Path(base);allfiles={str(p.relative_to(b)) for p in b.rglob('*') if p.is_file()};r={'files':len(allfiles),'bytes':sum((b/p).stat().st_size for p in allfiles)}
 if label=='android':
  ind=json.loads((b/'package-index.json').read_text());r['inventory']=verify(b,ind['files']);r['inventory']['uncovered']=sorted(allfiles-set(ind['files']));r['inventory']['extraneous']=sorted(set(ind['files'])-allfiles)
  chain=json.loads((b/'copy-chain.json').read_text());r['copy_chain']=verify(b,chain['files']);r['original_copy_chain']={'matched':0,'missing':[],'mismatches':[]}
  for name,d in chain['files'].items():
   s=Path(d['source'])
   if not s.is_file():r['original_copy_chain']['missing'].append(name)
   elif dig(s)!=d['sha256'] or s.read_bytes()!=(b/name).read_bytes():r['original_copy_chain']['mismatches'].append(name)
   else:r['original_copy_chain']['matched']+=1
  m=json.loads((b/'manifest.json').read_text());r['frozen_sources']={name:dig(b/('source-'+name))==sha for name,sha in m['scenarioSources'].items()};r['contract_match']=dig(b/'scenario-contract/editor-actions-v1.json')==m['scenarioContractSha256']
  r['native_receipt_hash_matches']=dig(b/ind['nativeReceipt'])==ind['nativeReceiptSha256']==chain['nativeBundleReceiptSha256']
  r['raw_snapshot_comparison']={}
  for stage,name in [('before','before'),('imported','imported'),('restarted','restored'),('reopened','reopened')]:
   tarpath=b/f'portable-bundle-{name}-workspace.tar';target=b/'snapshots'/stage/'workspace';members={};unsafe=[]
   with tarfile.open(tarpath) as t:
    for x in t.getmembers():
     if x.isdir():continue
     if not x.isfile() or not x.name.startswith('files/workspace/') or '/' in x.name.removeprefix('files/workspace/') or x.name in members:unsafe.append(x.name);continue
     members[x.name.removeprefix('files/workspace/')]=t.extractfile(x).read()
   extracted={str(x.relative_to(target)):x.read_bytes() for x in target.rglob('*') if x.is_file()}
   r['raw_snapshot_comparison'][stage]={'raw_file_count':len(members),'extracted_file_count':len(extracted),'same_names':set(members)==set(extracted),'same_bytes':members==extracted,'unsafe_members':unsafe}
 else:
  m=json.loads((b/'manifest.json').read_text());r['manifest']=verify(b,m['files']);r['manifest']['uncovered']=sorted(allfiles-set(m['files']));r['manifest']['extraneous']=sorted(set(m['files'])-allfiles);r['declared_count_matches']=m['fileCount']==len(m['files']);r['declared_payload_bytes_matches']=m['payloadBytes']==sum((b/p).stat().st_size for p in m['files'])
  sums={};errors=[]
  for line in (b/'SHA256SUMS').read_text().splitlines():
   sha,name=line.split('  ',1)
   if name in sums:errors.append('duplicate '+name)
   sums[name]={'sha256':sha,'bytes':(b/name).stat().st_size if (b/name).exists() else -1}
  r['sha256sums']=verify(b,sums);r['sha256sums']['parse_errors']=errors;r['sha256sums']['uncovered']=sorted(allfiles-set(sums));r['sha256sums']['extraneous']=sorted(set(sums)-allfiles)
  c=json.loads((b/'copy-provenance.json').read_text());records={x['path']:x for x in c['records']};r['copy_provenance']=verify(b,records);r['copy_provenance']['duplicate_paths']=len(c['records'])-len(records);r['original_copy_provenance']={'matched':0,'missing':[],'mismatches':[]}
  for name,d in records.items():
   s=Path(d['source'])
   if not s.is_file():r['original_copy_provenance']['missing'].append(name)
   elif dig(s)!=d['sha256'] or s.read_bytes()!=(b/name).read_bytes():r['original_copy_provenance']['mismatches'].append(name)
   else:r['original_copy_provenance']['matched']+=1
  r['explicit_external_only']=[]
  for x in c['externalOnly']:
   p=Path(x['source']);r['explicit_external_only'].append({'path':x['path'],'committed':(b/x['path']).exists(),'source_available':p.is_file(),'source_hash_matches':p.is_file() and dig(p)==x['sha256']})
 res[label]=r
Path('/tmp/logisim-portable53-integrity-results.json').write_text(json.dumps(res,indent=2))
print(json.dumps(res,indent=2))
