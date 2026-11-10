#!/usr/bin/env python3
"""Replay the retained static evidence; no binaries, devices or external paths are opened."""
import gzip,hashlib,importlib.util,json,pathlib,plistlib,re,struct
ROOT=pathlib.Path(__file__).resolve().parent
APP='thalesmms.logisim.validation.revocationfixture'
GROUP='group.'+APP
EXPECTED={'app':'93f5c45e71c1ba64b2789bae430ab117a3152260f17f950bd5d97eab656d08a8','provider':'5c7d3f12196d954902c4bb814532500e7a82bd83c55da89f2da3504302795c75'}
XML_SHA='017a1181f1f961c0e685433cec6a39a943307b15091ce1ef694619774749c91a'
DER_SHA='26c2dda714a8d8e38954596706640d3e846180d64a444f4ec9043b024617994c'
def digest(data):return hashlib.sha256(data).hexdigest()
def read(root,name):
 p=pathlib.PurePosixPath(name);assert not p.is_absolute()and '..'not in p.parts and '\\'not in name
 f=root/name;assert f.is_file()and not f.is_symlink()and f.resolve().is_relative_to(root.resolve());return f.read_bytes()
def document(root,name):return json.loads(read(root,name))
def verify(root=ROOT):
 inventory=document(root,'inventory.json')
 actual={str(p.relative_to(root)) for p in root.rglob('*')if p.is_file() and p.name!='inventory.json' and '__pycache__'not in p.parts}
 assert set(inventory['files'])==actual
 for name,item in inventory['files'].items():
  data=read(root,name);assert len(data)==item['bytes']and digest(data)==item['sha256'],name
 spec=importlib.util.spec_from_file_location('retained_macho_parser',root/'inspect-binary.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 slots=document(root,'signature-slots.json');formats=document(root,'format-comparison.json');images=document(root,'sections.json')
 assert set(slots)=={'produced-app','installed-app','produced-provider','installed-provider'}
 for name,result in slots.items():
  kind=name.rsplit('-',1)[1];assert result['sha256']==EXPECTED[kind]
  assert result['codeSignatureCommand']['signatureFileOffset']=={'app':39696,'provider':37088}[kind]
  assert result['codeSignatureCommand']['signatureBytes']==992
  assert result['superBlob']['count']==5 and result['superBlob']['length']=={'app':975,'provider':984}[kind]
  byslot={entry['slot']:entry for entry in result['slots']};assert set(byslot)=={0,2,5,7,65536}
  for number,magic,size,expected in [(5,0xfade7171,336,XML_SHA),(7,0xfade7172,112,DER_SHA)]:
   entry=byslot[number];blob=read(root,entry['blobFile']);assert struct.unpack('>II',blob[:8])==(magic,size)
   assert len(blob)==size and digest(blob)==expected==entry['blobSHA256']
   assert entry['fileOffset']==result['codeSignatureCommand']['signatureFileOffset']+entry['superBlobOffset']
   assert digest(blob[8:])==entry['payloadSHA256']and len(blob[8:])==entry['payloadBytes']
  xml=plistlib.loads(read(root,byslot[5]['blobFile'])[8:]);der=parser.der_dictionary(read(root,byslot[7]['blobFile'])[8:])
  assert xml==der=={'com.apple.security.application-groups':[GROUP]}
  assert formats[name]['derSemanticallyEqualsXML']is True
  cd=read(root,byslot[0]['blobFile']);assert struct.unpack('>I',cd[:4])[0]==0xfade0c02
  version,flags,hoff,ioff,ns,nc,limit=struct.unpack_from('>7I',cd,8)
  assert version==0x20400 and flags==2 and ns==7 and nc==3 and limit==result['codeSignatureCommand']['signatureFileOffset']
  hs,ht,platform,page=struct.unpack_from('>4B',cd,36);assert (hs,ht,platform,page)==(32,2,0,14)
  ident=cd[ioff:cd.index(0,ioff)].decode();assert ident==APP+('.provider'if kind=='provider'else'')
  for number in [5,7]:assert cd[hoff-number*hs:hoff-(number-1)*hs].hex()==digest(read(root,byslot[number]['blobFile']))
  assert result['codeDirectories'][0]['allCodePageDigestsMatch']is True
  assert result['codeDirectories'][0]['codePageCount']==3
  assert plistlib.loads(read(root,name+'-codesign-xml.stdout'))==xml
  assert read(root,name+'-codesign-der.stdout')==read(root,byslot[7]['blobFile'])[8:]
 replay=document(root,'explicit-source-replay.json');assert replay['exitCode']==0 and replay['parserDriverSHA256']==digest(read(root,'inspect-binary.py'))
 assert replay['inspection']['sha256']==EXPECTED['app']and all(x['allCodePageDigestsMatch']for x in replay['inspection']['codeDirectories'])
 assert len(images)==8
 for name,image in images.items():
  assert not any(s['section']in['__entitlements','__ents_der','__info_plist']for s in image['sections'])
  assert image['absentRequestedSections']==['__entitlements','__info_plist','__ents_der']
  header=read(root,name+'-otool-headers-loadcommands.stdout').decode()
  names=re.findall(r'^\s+sectname\s+(\S+)\s*$',header,re.M)
  assert names==[s['section']for s in image['sections']]
  assert len(names)==({'app':14,'provider':13}[name.split('-')[1]]+(20 if name.endswith('debug-dylib')else 0))
 for command in document(root,'commands.json'):
  assert command['exitCode']==0 and command['argv'][0]in['codesign','openssl','xcrun']
  if command['argv'][0]=='codesign':assert command['argv'][1:4]==['--display','--entitlements','-']and command['argv'][4]in['--xml','--der']
  if command['argv'][0]=='openssl':assert command['argv'][1:3]==['asn1parse','-inform']
  if command['argv'][0]=='xcrun':assert command['argv'][1:4]==['otool','-h','-l']
  for stream in ['stdout','stderr']:
   info=command[stream];data=read(root,info['file']);assert len(data)==info['bytes']and digest(data)==info['sha256']
 build=document(root,'build-link-extracts.json');assert len(build['linkTasks'])==6
 compressed=read(root,'original-build.log.gz');assert compressed[:3]==b'\x1f\x8b\x08'and compressed[4:8]==b'\0'*4
 rawbuild=gzip.decompress(compressed);assert len(rawbuild)==build['retainedBuildLogBytes']and digest(rawbuild)==build['retainedBuildLogSHA256']
 buildlines=rawbuild.decode().splitlines()
 assert [(i+1,line)for i,line in enumerate(buildlines)if line.startswith('Ld ')]==[(item['originalLine'],item['task'])for item in build['linkTasks']]
 assert not any('ProcessProductPackaging'in line or 'Simulated.xcent'in line for line in buildlines)
 for link in build['linkTasks']:
  index=link['originalLine']-1;assert buildlines[index]==link['task']and link['command']in[line.strip()for line in buildlines[index+1:index+5]]
 assert build['processProductPackagingLines']==[]and build['simulatedXcentLines']==[]and build['entitlementsSectionCreateLines']==[]
 assert all('Simulated.xcent'not in x['command']and '__entitlements'not in x['command']and '__ents_der'not in x['command']for x in build['linkTasks'])
 rc=document(root,'registry/commands.json');assert len(rc)==3
 for command in rc:
  assert command['exitCode']==0
  for ch in ['stdout','stderr']:
   data=read(root,'registry/'+command['name']+'.'+ch);assert len(data)==command[ch]['bytes']and digest(data)==command[ch]['sha256']
 appinfo=read(root,'registry/appinfo.stdout').decode();assert re.search(r'GroupContainers\s*=\s*\{\s*\};',appinfo)
 assert read(root,'registry/group-registry.stdout')==b''
 plugin=read(root,'registry/plugin-match.stdout').decode();assert '(1 plug-in)'in plugin and 'com.apple.fileprovider-nonui'in plugin
 assert 'UUID = F8E78B69-61F8-495B-A993-E0BB85E025F4'in plugin and 'Parent Bundle ='in plugin
 assert '/8EA27060-12CE-46F0-BE04-AC91EAD53EE4/RevocationFixture.app'in plugin
 return {'status':'ARCHIVE_CONSISTENCY_PASS','scope':'Static retained-file evidence only','codeSignatureXMLandDER':'PRESENT_AND_EQUIVALENT','simulatorSections':'ABSENT_IN_EIGHT_OBSERVED_IMAGES','fullFileCodePageHashes':'CAPTURED_RESULT_NOT_RECOMPUTED_WITHOUT_EXPLICIT_BINARY','runtimeTrust':'NOT_ESTABLISHED','causalAttribution':'NOT_ESTABLISHED','nativeTestExecutedByThisArchive':False}
if __name__=='__main__':print(json.dumps(verify(),indent=2))
