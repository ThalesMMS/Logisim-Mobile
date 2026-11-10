#!/usr/bin/env python3
import hashlib,json,pathlib,plistlib,struct
OUT=None  # Set only by the explicit offline CLI or an isolated caller.


def sha(b):return hashlib.sha256(b).hexdigest()
def u32(b,o):return struct.unpack_from('>I',b,o)[0]
def parse(path,name,data=None):
 b=path.read_bytes() if data is None else data; assert b[:4]==b'\xcf\xfa\xed\xfe','Only the observed thin little-endian64 form is admitted'
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


def tlv(data,at=0,depth=0):
 assert depth<16 and at+2<=len(data)
 start=at;tag=data[at];at+=1;assert tag&31!=31,'High-tag form not present in this bounded observed fixture'
 n=data[at];at+=1
 if n&128:
  width=n&127;assert 0<width<=4 and at+width<=len(data)
  raw=data[at:at+width];assert raw[0]!=0
  length=int.from_bytes(raw,'big');assert length>=128;at+=width
 else:length=n
 end=at+length;assert end<=len(data)
 node={'offset':start,'tag':hex(tag),'tagClass':tag>>6,'constructed':bool(tag&32),'tagNumber':tag&31,'headerBytes':at-start,'contentBytes':length,'sha256':sha(data[start:end])}
 if tag&32:
  children=[];pos=at
  while pos<end:
   child,pos=tlv(data,pos,depth+1);children.append(child)
  assert pos==end;node['children']=children
 elif tag==0x0c:node['utf8']=data[at:end].decode('utf-8','strict')
 elif tag==0x02:
  raw=data[at:end];assert raw and (len(raw)==1 or not(raw[0]==0 and raw[1]<128)) and (len(raw)==1 or not(raw[0]==255 and raw[1]>=128))
  node['integer']=int.from_bytes(raw,'big',signed=True)
 else:node['contentHex']=data[at:end].hex()
 return node,end

def der_dictionary(data):
 tree,end=tlv(data);assert end==len(data)
 assert tree['tag']=='0x70' and len(tree['children'])==2
 version,dictionary=tree['children'];assert version['tag']=='0x2' and version['integer']==1 and dictionary['tag']=='0xb0'
 result={}
 for entry in dictionary['children']:
  assert entry['tag']=='0x30' and len(entry['children'])==2
  key,array=entry['children'];assert key['tag']=='0xc' and array['tag']=='0x30'
  assert key['utf8'] not in result
  assert all(x['tag']=='0xc' for x in array['children'])
  result[key['utf8']]=[x['utf8']for x in array['children']]
 return result

def sections(data):
 assert data[:4]==b'\xcf\xfa\xed\xfe'
 ncmds,size=struct.unpack_from('<II',data,16);assert 32+size<=len(data)
 at=32;result=[]
 for _ in range(ncmds):
  cmd,ln=struct.unpack_from('<II',data,at);assert ln>=8 and at+ln<=32+size
  if cmd==0x19:
   segment=data[at+8:at+24].rstrip(b'\0').decode('ascii');count=struct.unpack_from('<I',data,at+64)[0]
   assert 72+80*count<=ln
   for index in range(count):
    pos=at+72+80*index;name,seg,addr,length,offset,align,reloff,nrel,flags,r1,r2,r3=struct.unpack_from('<16s16sQQIIIIIIII',data,pos)
    name=name.rstrip(b'\0').decode('ascii');seg=seg.rstrip(b'\0').decode('ascii');assert seg==segment
    zero=(flags&255)in[1,12,18]
    if not zero:assert offset+length<=len(data)
    result.append({'segment':seg,'section':name,'address':hex(addr),'bytes':length,'fileOffset':offset,'flags':hex(flags),'zeroFill':zero,'sha256':None if zero else sha(data[offset:offset+length])})
  at+=ln
 assert at==32+size
 return result

if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser(description='Read one explicit observed thin arm64 Mach-O. No execution, signing or device access. Output must be new.')
 parser.add_argument('--binary',type=pathlib.Path,required=True);parser.add_argument('--expected-sha256',required=True);parser.add_argument('--output',type=pathlib.Path,required=True);parser.add_argument('--label',default='explicit-image')
 args=parser.parse_args();assert args.binary.is_file()and not args.binary.is_symlink();data=args.binary.read_bytes();assert sha(data)==args.expected_sha256
 assert args.label.replace('-','').replace('_','').isalnum()
 OUT=args.output;OUT.mkdir(parents=True,exist_ok=False)
 result=parse(args.binary,args.label,data);assert result['sha256']==args.expected_sha256;result['sections']=sections(data)
 for slot in result['slots']:
  if slot['slot']==7:slot['decodedDER']=der_dictionary((OUT/slot['blobFile']).read_bytes()[8:])
 (OUT/'inspection.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'status':'STATIC_INSPECTION_PASS','sha256':result['sha256'],'signatureSlots':[s['slot']for s in result['slots']],'sectionCount':len(result['sections']),'runtimeTrust':'NOT_ESTABLISHED'}))
