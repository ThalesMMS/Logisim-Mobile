#!/usr/bin/env python3
import datetime,hashlib,json,pathlib,plistlib,struct,subprocess
OUT=pathlib.Path(__file__).resolve().parent
RESULT=json.loads((OUT/'signature-slots.json').read_text())
records=[]
def sha(b):return hashlib.sha256(b).hexdigest()
def run(name,argv):
 r=subprocess.run(argv,capture_output=True);record={'name':name,'argv':list(map(str,argv)),'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exitCode':r.returncode}
 for stream in ['stdout','stderr']:
  b=getattr(r,stream);p=OUT/(name+'.'+stream);p.write_bytes(b);record[stream]={'file':p.name,'bytes':len(b),'sha256':sha(b)}
 records.append(record);(OUT/'commands.json').write_text(json.dumps(records,indent=2)+'\n');assert r.returncode==0,name
 return r

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

comparisons={}
for name,value in RESULT.items():
 path=pathlib.Path(value['path'])
 xml=run(name+'-codesign-xml',['codesign','--display','--entitlements','-','--xml',str(path)]).stdout
 der=run(name+'-codesign-der',['codesign','--display','--entitlements','-','--der',str(path)]).stdout
 blob_xml=(OUT/(name+'-entitlements.xml')).read_bytes();blob_der=(OUT/(name+'-entitlements.der')).read_bytes()
 xmlvalue=plistlib.loads(xml);assert xmlvalue==plistlib.loads(blob_xml)
 assert der==blob_der
 tree,end=tlv(der);assert end==len(der)
 assert tree['tag']=='0x70' and len(tree['children'])==2
 version,dictionary=tree['children'];assert version['tag']=='0x2' and version['integer']==1 and dictionary['tag']=='0xb0'
 entries={}
 for entry in dictionary['children']:
  assert entry['tag']=='0x30' and len(entry['children'])==2
  key,array=entry['children'];assert key['tag']=='0xc' and array['tag']=='0x30'
  assert key['utf8'] not in entries
  assert all(x['tag']=='0xc' for x in array['children'])
  entries[key['utf8']]=[x['utf8'] for x in array['children']]
 assert entries==xmlvalue=={'com.apple.security.application-groups':['group.thalesmms.logisim.validation.revocationfixture']}
 comparisons[name]={'codesignXMLSemanticallyEqualsSlot5':True,'codesignDERExactlyEqualsSlot7Payload':True,'derVersion':1,'observedDictionaryWithStringArrayValues':entries,'derSemanticallyEqualsXML':True,'strictBoundedASN1Tree':tree,'limitations':['The strict bounded decoder admits only this observed v1 dictionary/string-array shape; it is not a general DER entitlement validator.','Code-signature structural/digest equality does not establish runtime trust or what CoreSimulatorBridge consumed.']}
 run(name+'-openssl-asn1',['openssl','asn1parse','-inform','DER','-in',str(OUT/(name+'-entitlements.der')),'-i'])
(OUT/'format-comparison.json').write_text(json.dumps(comparisons,indent=2)+'\n')

sections={}
for name,value in RESULT.items():
 for image,p in [('main-executable',pathlib.Path(value['path'])),('debug-dylib',pathlib.Path(value['path']+'.debug.dylib'))]:
  b=p.read_bytes();assert b[:4]==b'\xcf\xfa\xed\xfe';ncmds,cs=struct.unpack_from('<II',b,16);at=32;all_sections=[]
  for i in range(ncmds):
   cmd,size=struct.unpack_from('<II',b,at);assert size>=8 and at+size<=32+cs
   if cmd==0x19:
    seg=struct.unpack_from('<16s',b,at+8)[0].rstrip(b'\0').decode('ascii');ns=struct.unpack_from('<I',b,at+64)[0];assert 72+80*ns<=size
    for j in range(ns):
     atsect=at+72+80*j;sn,sg,addr,ln,off,align,reloff,nrel,flags,r1,r2,r3=struct.unpack_from('<16s16sQQIIIIIIII',b,atsect)
     sn=sn.rstrip(b'\0').decode('ascii');sg=sg.rstrip(b'\0').decode('ascii');assert sg==seg
     zero=(flags&255) in [1,12,18]
     if not zero:assert off+ln<=len(b)
     entry={'segment':sg,'section':sn,'address':hex(addr),'bytes':ln,'fileOffset':off,'flags':hex(flags),'zeroFill':zero,'sha256':None if zero else sha(b[off:off+ln])}
     if sn in ['__entitlements','__info_plist','__ents_der']:
      assert not zero;target=OUT/(name+'-'+image+'-'+sn+'.bin');target.write_bytes(b[off:off+ln]);entry['extractedFile']=target.name
     all_sections.append(entry)
   at+=size
  assert at==32+cs
  selected=[x for x in all_sections if x['section'] in ['__entitlements','__info_plist','__ents_der']]
  sections[name+'-'+image]={'path':str(p),'bytes':len(b),'sha256':sha(b),'sections':all_sections,'selectedSections':selected,'absentRequestedSections':[sn for sn in ['__entitlements','__info_plist','__ents_der'] if not any(x['section']==sn for x in all_sections)]}
(OUT/'sections.json').write_text(json.dumps(sections,indent=2)+'\n')
print(json.dumps({'formats':{k:v['observedDictionaryWithStringArrayValues']for k,v in comparisons.items()},'sections':{k:{'selected':v['selectedSections'],'absent':v['absentRequestedSections']}for k,v in sections.items()}},indent=2))
