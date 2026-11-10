#!/usr/bin/env python3
"""Pure source/specification checks; no SDK tool, target, store or UI is invoked."""
from pathlib import Path
import hashlib,json,re
OUT=Path(__file__).resolve().parent
SDK=Path('/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator27.2.sdk/System/Library/Frameworks/FileProvider.framework/Headers')
HELPER=Path('/Users/thales/GitHub/logisim-mobile-worktrees/ios-provider-modify-diagnostics/scripts/ios_fileprovider_revocation/Provider/RevocationModifyDiagnostics.swift')
INPUTS={name:(SDK/name).read_bytes()for name in ['NSFileProviderItem.h','NSFileProviderModifyItemOptions.h','NSFileProviderDefines.h']};helper=HELPER.read_bytes();item=INPUTS['NSFileProviderItem.h'].decode();options=INPUTS['NSFileProviderModifyItemOptions.h'].decode();defines=INPUTS['NSFileProviderDefines.h'].decode();text=helper.decode()
def lower(value):return value[0].lower()+value[1:]
fields_block=item.split('typedef NS_OPTIONS(NSUInteger, NSFileProviderItemFields) {',1)[1].split('}',1)[0]
fields=[(1<<int(bit),lower(name))for name,bit in re.findall(r'^\s*NSFileProviderItem(\w+)[^=]*=\s*1\s*<<\s*(\d+)',fields_block,re.M)]
assert fields==[(1<<i,name)for i,name in enumerate(['contents','filename','parentItemIdentifier','lastUsedDate','tagData','favoriteRank','creationDate','contentModificationDate','fileSystemFlags','extendedAttributes','typeAndCreator'])]
helper_fields=[(1<<int(bit),name)for bit,name in re.findall(r'\(1 << (\d+), "([^"]+)"\)',text)]
assert helper_fields==fields
policy_block=item.split('typedef NS_ENUM(NSInteger, NSFileProviderContentPolicy) {',1)[1].split('}',1)[0]
policy_names=[lower(name)for name in re.findall(r'^\s*NSFileProviderContentPolicy(\w+)\s+',policy_block,re.M)]
assert policy_names==['inherited','downloadLazily','downloadLazilyAndEvictOnRemoteUpdate','downloadEagerlyAndKeepDownloaded']
assert [(int(n),name)for n,name in re.findall(r'case (\d+): return "([^"]+)"',text)]==list(enumerate(policy_names))
option_names=[(1<<int(bit),lower(name))for name,bit in re.findall(r'^\s*NSFileProviderModifyItem(\w+)[^=]*=\s*1\s*<<\s*(\d+)',options,re.M)]
assert option_names==[(1,'mayAlreadyExist'),(2,'failOnConflict'),(4,'isImmediateUploadRequestByPresentingApplication')]
assert re.search(r'#define FILEPROVIDER_API_AVAILABILITY_SYNC_CONTROLS API_AVAILABLE\(macos\(26\.0\), ios\(26\.0\)\)',defines)
assert '@optional'in item[:item.index('@property (nonatomic, readonly) NSFileProviderContentPolicy contentPolicy')]
MASK=(1<<64)-1;KNOWN=(1<<11)-1

def decode(raw):
 assert isinstance(raw,int)and not isinstance(raw,bool)and 0<=raw<=MASK
 return {'rawDecimal':str(raw),'names':[name for bit,name in fields if raw&bit],'unknownDecimal':str(raw&(MASK^KNOWN))}
checks=0
for bit,name in fields:
 assert decode(bit)=={'rawDecimal':str(bit),'names':[name],'unknownDecimal':'0'};checks+=1
assert decode(0)=={'rawDecimal':'0','names':[],'unknownDecimal':'0'};checks+=1
assert decode(KNOWN)['names']==[name for _,name in fields]and decode(KNOWN)['unknownDecimal']=='0';checks+=1
for raw in [1<<11,1<<63, MASK]:
 result=decode(raw);assert int(result['rawDecimal'])==raw and int(result['unknownDecimal'])==raw&(MASK^KNOWN);checks+=1
for contents in [False,True]:
 for present in [False,True]:
  raw=(1 if contents else 0)|(1<<3)|(1<<63)
  assert ((raw&1)!=0 and present)==(contents and present);checks+=1
# Optional absence must remain separate from an observed inherited(0) value.
assert None!=0 and 'requestedContentPolicyPresent' in text and 'requestedContentPolicyRaw' in text and '?? "absent"'in text;checks+=1
assert 'fixtureDeclaredContentPolicyRaw'in text and 'storedContentPolicyRaw'not in text;checks+=1
assert not any(symbol in text for symbol in ['NSFileProviderContentPolicy.', 'NSFileProviderModifyItemOptions.', 'FileManager', 'Data(contentsOf:', 'snapshot(']);checks+=1
sources={name:{'path':str(SDK/name),'sha256':hashlib.sha256(b).hexdigest()}for name,b in INPUTS.items()};sources['helper']={'path':str(HELPER),'sha256':hashlib.sha256(helper).hexdigest()}
spec={'scope':'Source-only SDK27.2 interpretation and pure mask checks; not an observation of Auth004 arguments or a Swift runtime test','sources':sources,'fieldBits':[{'mask':bit,'name':name}for bit,name in fields],'fieldsKnownMask':KNOWN,'optionBits':[{'mask':bit,'name':name,'iOSAvailability':'16.0+'if bit==1 else'26.0+'}for bit,name in option_names],'policyValues':[{'raw':i,'name':name,'iOSAvailability':'16.0+'if i in [0,2]else'not available to iOS in SDK27.2'}for i,name in enumerate(policy_names)],'observability':{'requestedPolicy':'Optional incoming NSFileProviderItem property; absent is not inherited(0)','fixtureDeclaredPolicy':'Source getter currently advertises raw2; not a value persisted in FixtureState','currentItemWithoutExtraStoreRead':'If an actual current item is needed, construct it from the state already loaded by modifyItem and read its getter. Do not fetch/snapshot again merely for logging.','IOAddedByDetailsHelper':'None; fields/options/newContents presence are arguments, policy reads use supplied metadata/getter. Structured event persistence remains the existing logging path.'},'swiftAvailabilityNote':'Do not refer to macOS-only policy cases in iOS Swift. Raw-name table is observation schema. failOnConflict/immediate-upload symbolic references require iOS26 availability; rawValue logging needs no such symbol.','guard':'Unchanged: changedFields.contains(.contents) && newContents != nil','actualAuth004Fields':'UNKNOWN_UNTIL_FUTURE_DIAGNOSTIC','pureChecksPassed':checks}
(OUT/'spec.json').write_text(json.dumps(spec,indent=2)+'\n');print(json.dumps({'status':'SDK_SOURCE_COMPARISON_PASS','fieldEntries':len(fields),'policyEntries':len(policy_names),'optionsEntries':len(option_names),'pureChecksPassed':checks,'actualSwiftHelperRuntimeTested':False,'nativeExecution':'NOT_RUN','helperSHA256':sources['helper']['sha256']},indent=2))
