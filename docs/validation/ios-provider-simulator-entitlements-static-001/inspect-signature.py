#!/usr/bin/env python3
import hashlib,json,pathlib,plistlib,struct
OUT=pathlib.Path(__file__).resolve().parent
ART=pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-fileprovider-error-chain/outputs/ios-fileprovider-fixture-11fc44d5/artifact/artifact.json')
AFTER=pathlib.Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-fileprovider-error-chain/outputs/ios-provider-existing-group-ad28-002/after-installed.json')

def sha(b):return hashlib.sha256(b).hexdigest()
def u32(b,o):return struct.unpack_from('>I',b,o)[0]
def parse(path,name):
 b=path.read_bytes(); assert b[:4]==b'\xcf\xfa\xed\xfe','Only the observed thin little-endian64 form is admitted'
 magic,cpu,subcpu,ft,ncmds,cs,flags,res=struct.unpack_from('<IiiIIIII',b)
 assert ncmds<10000 and 32+cs<=len(b)
 at=32;commands=[]
 for i in range(ncmds):
  cmd,size=struct.unpack_from('<II',b,at);assert size>=8 and at+size<=32+cs
  if cmd==0x1d:
   off,length=struct.unpack_from('<II',b,at+8);commands.append({'index':i,'loadCommandFileOffset':at,'signatureFileOffset':off,'signatureBytes':length})
  at+=size
 assert at==32+cs and len(commands)==1
 command=commands[0];off=command['signatureFileOffset'];size=command['signatureBytes'];assert off+size<=len(b)
 sb=b[off:off+size];assert u32(sb,0)==0xfade0cc0
 length,count=u32(sb,4),u32(sb,8);assert count<100 and 12+count*8<=length<=len(sb)
 slots=[];by_type={}
 for i in range(count):
  slot,rel=struct.unpack_from('>II',sb,12+i*8);assert rel>=12+count*8 and rel+8<=length
  mg,ln=struct.unpack_from('>II',sb,rel);assert ln>=8 and rel+ln<=length
  assert slot not in by_type
  blob=sb[rel:rel+ln];by_type[slot]=blob
  dst=OUT/(name+'-slot-'+str(slot)+'.blob');dst.write_bytes(blob)
  entry={'slot':slot,'magic':hex(mg),'superBlobOffset':rel,'fileOffset':off+rel,'bytes':ln,'blobSHA256':sha(blob),'blobFile':dst.name,'payloadSHA256':sha(blob[8:]),'payloadBytes':len(blob)-8}
  if slot==5:
   assert mg==0xfade7171
   payload=blob[8:];(OUT/(name+'-entitlements.xml')).write_bytes(payload)
   entry['decodedXML']=plistlib.loads(payload)
  elif slot==7:
   assert mg==0xfade7172
   (OUT/(name+'-entitlements.der')).write_bytes(blob[8:])
  slots.append(entry)
 ranges=sorted((x['superBlobOffset'],x['superBlobOffset']+x['bytes'])for x in slots)
 assert all(a[1]<=z[0] for a,z in zip(ranges,ranges[1:]))
 directories=[]
 for entry in slots:
  if entry['magic']!='0xfade0c02':continue
  cd=by_type[entry['slot']];version,cdflags,hashoff,identoff,nspecial,ncode,limit=struct.unpack_from('>7I',cd,8)
  hs,ht,platform,page=struct.unpack_from('>4B',cd,36)
  assert ht in [1,2,3,4] and hs=={1:20,2:32,3:20,4:48}[ht]
  assert hashoff-nspecial*hs>=0 and hashoff+ncode*hs<=len(cd)
  def h(data):return {1:hashlib.sha1,2:hashlib.sha256,3:hashlib.sha256,4:hashlib.sha384}[ht](data).digest()[:hs]
  identity=cd[identoff:cd.index(0,identoff)].decode('utf8')
  teamoff=u32(cd,48) if version>=0x20200 else None
  team=cd[teamoff:cd.index(0,teamoff)].decode('utf8') if teamoff else None
  special=[]
  for s in range(1,nspecial+1):
   digest=cd[hashoff-s*hs:hashoff-(s-1)*hs]
   actual=h(by_type[s]) if s in by_type else None
   special.append({'slot':s,'codeDirectoryOffset':hashoff-s*hs,'storedDigest':digest.hex(),'blobPresent':s in by_type,'actualBlobDigest':actual.hex() if actual else None,'digestMatchesBlob':actual==digest if actual else None})
  pagesize=1<<page if page else limit
  assert ncode==(limit+pagesize-1)//pagesize
  code_matches=[]
  for j in range(ncode):code_matches.append(h(b[j*pagesize:min((j+1)*pagesize,limit)])==cd[hashoff+j*hs:hashoff+(j+1)*hs])
  directories.append({'slot':entry['slot'],'version':hex(version),'flags':hex(cdflags),'adhocFlag':bool(cdflags&2),'linkerSignedFlag':bool(cdflags&0x20000),'hashOffset':hashoff,'hashSize':hs,'hashType':ht,'platform':platform,'pageSizeExponent':page,'identifier':identity,'teamIdentifier':team,'specialSlotCount':nspecial,'codeSlotCount':ncode,'codeLimit':limit,'specialSlots':special,'allCodePageDigestsMatch':all(code_matches),'codePageCount':len(code_matches)})
 return {'path':str(path),'bytes':len(b),'sha256':sha(b),'machHeader':{'cpuType':hex(cpu),'fileType':ft,'flags':hex(flags),'ncmds':ncmds},'codeSignatureCommand':command,'superBlob':{'magic':hex(u32(sb,0)),'length':length,'loadCommandSize':size,'count':count,'sha256DeclaredLength':sha(sb[:length]),'paddingBytes':size-length},'slots':slots,'codeDirectories':directories}

if __name__=='__main__':
 a=json.loads(ART.read_text());installed=json.loads(AFTER.read_text());results={}
 for n in ['app','provider']:
  name={'app':'RevocationFixture','provider':'RevocationProvider'}[n]
  for scope,data in [('produced',a),('installed',installed)]:
   p=pathlib.Path(data[n]['path'])/name
   r=parse(p,scope+'-'+n);assert r['sha256']==a[n]['executable_sha256'];results[scope+'-'+n]=r
 assert results['produced-app']['sha256']==results['installed-app']['sha256']
 assert results['produced-provider']['sha256']==results['installed-provider']['sha256']
 (OUT/'signature-slots.json').write_text(json.dumps(results,indent=2)+'\n')
 print(json.dumps({k:{'bytes':v['bytes'],'sha256':v['sha256'],'slots':[(x['slot'],x['magic'],x['bytes']) for x in v['slots']],'codeDirectories':v['codeDirectories']}for k,v in results.items()},indent=2))
