#!/usr/bin/env python3
"""Real HDL source -> approved Yosys importer -> same .circ in two engines. No Gradle."""
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from companion.api import JobContext
from companion.hdl_import import synthesize

CASES = [
    dict(name='hierarchy', files=['hierarchy.v'], format='verilog', top='hierarchy',
         inputs=[('a', 1), ('b', 1)], outputs=[('y', 1)]),
    dict(name='gates', files=['gates.v'], format='verilog', top='gates',
         inputs=[('a', 2), ('b', 2), ('select', 1)], outputs=[('and_y', 2), ('xor_y', 2), ('mux_y', 2)]),
    dict(name='macros', files=['macros.v', 'constants.vh'], format='verilog', top='macros',
         defines=["MASK=2"], inputs=[('a', 2)], outputs=[('y', 2)]),
    dict(name='blif-and', files=['and_gate.blif'], format='blif', top='and_gate',
         inputs=[('a', 1), ('b', 1)], outputs=[('y', 1)]),
]


class RecordingContext(JobContext):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.commands = []

    def run(self, argv, **kwargs):
        self.commands.append(dict(argv=[str(value) for value in argv], **kwargs))
        return super().run(argv, **kwargs)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def artifact(path, out):
    path = Path(path)
    try:
        name = str(path.relative_to(out))
    except ValueError:
        name = str(path)
    return dict(path=name, sha256=digest(path), size=path.stat().st_size)


def observations(text):
    lines = text.splitlines()
    if any(not re.match(r'^[CPHGV]\t', row) for row in lines):
        raise AssertionError('Unexpected oracle output')
    return sorted(row for row in lines if not row.startswith('V\t')), [row for row in lines if row.startswith('V\t')]


def compare(mobile, desktop, expected):
    ms, mv = observations(mobile)
    ds, dv = observations(desktop)
    if ms != ds:
        raise AssertionError('Circuit, pin attributes, gates or hierarchy differ')
    if mv != dv or mv != expected:
        raise AssertionError('Observed truth tables differ or omit rows')
    if any(row.split('\t')[1:3] != [str(index), 'PASS'] for index, row in enumerate(mv)):
        raise AssertionError('An engine disagrees with the original HDL reference')
    return ms, mv


def reject(label, action):
    try:
        action()
    except AssertionError as error:
        return dict(name=label, rejected=True, reason=str(error))
    raise AssertionError(f'False control accepted: {label}')


def check_archived_sources(circuit, uploads, names):
    root = ET.parse(circuit).getroot()
    if root.findall('hdl-source'):
        raise AssertionError('Unsupported root-level source archive emitted')
    archives = root.findall("./options/a[@name='logisim-mobile-hdl-source']")
    if len(archives) != len(names) or {node.attrib.get('file') for node in archives} != set(names):
        raise AssertionError('Source archive file inventory differs from explicit inputs')
    for node in archives:
        if (node.text or '').encode('utf-8') != (uploads/node.attrib['file']).read_bytes():
            raise AssertionError('Source archive text differs from explicit input bytes')


def testbench(case, values):
    declarations = [f"reg [{width-1}:0] {name};" for name, width in case['inputs']]
    declarations += [f"wire [{width-1}:0] {name};" for name, width in case['outputs']]
    ports = case['inputs'] + case['outputs']
    body = ['module testbench;', *declarations,
            case['top'] + ' dut(' + ','.join(f'.{name}({name})' for name, _ in ports) + ');', 'initial begin']
    for index, row in enumerate(values):
        body += [f'{name}={width}\'d{value};' for (name, width), value in zip(case['inputs'], row)]
        body += ['#1;', '$display("R\\t' + str(index) + ''.join('\\t%b' for _ in case['outputs'])
                 + '",' + ','.join(name for name, _ in case['outputs']) + ');']
    return '\n'.join(body + ['$finish;', 'end', 'endmodule', ''])


def check_netlist_structure(case, netlist, module_map, structure):
    expected_circuits = sorted('C\t' + module_map[name] for name in netlist['modules'])
    if [row for row in structure if row.startswith('C\t')] != expected_circuits:
        raise AssertionError('Imported module inventory differs from synthesized hierarchy')
    expected_links = sorted((module_map[parent], module_map[cell['type']])
                            for parent, module in netlist['modules'].items()
                            for cell in module.get('cells', {}).values() if cell['type'] in module_map)
    actual_links = sorted(tuple(row.split('\t')[1:3]) for row in structure if row.startswith('H\t'))
    if actual_links != expected_links:
        raise AssertionError('Imported instance hierarchy differs from synthesized hierarchy')
    expected_pins = sorted((module_map[name], label, port['direction'].upper(), str(len(port['bits'])))
                           for name, module in netlist['modules'].items() for label, port in module['ports'].items())
    actual_pins = sorted(tuple(row.split('\t')[1:5]) for row in structure if row.startswith('P\t'))
    if actual_pins != expected_pins:
        raise AssertionError('Imported pin labels/directions/widths differ from synthesized ports')
    top_pins = sorted((label, 'INPUT', str(width)) for label, width in case['inputs'])
    top_pins += sorted((label, 'OUTPUT', str(width)) for label, width in case['outputs'])
    if sorted(row[1:] for row in actual_pins if row[0] == module_map[case['top']]) != sorted(top_pins):
        raise AssertionError('Fixture declaration does not cover all top ports')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mobile-jar', type=Path, required=True)
    parser.add_argument('--desktop-jar', type=Path, required=True)
    parser.add_argument('--mobile-sha256', required=True)
    parser.add_argument('--desktop-sha256', required=True)
    parser.add_argument('--stdlib-jar', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs/hdl-import-desktop-oracle')
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    jars = dict(mobile=args.mobile_jar.resolve(), desktop=args.desktop_jar.resolve())
    if args.stdlib_jar:
        jars['stdlib'] = args.stdlib_jar.resolve()
    else:
        version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT/'build.gradle.kts').read_text()).group(1)
        cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))
        jars['stdlib'] = next((cache/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
    if digest(jars['mobile']) != args.mobile_sha256 or digest(jars['desktop']) != args.desktop_sha256:
        raise SystemExit('Input JAR SHA-256 does not match the explicitly selected artifact')
    tools = {name: shutil.which(name) for name in ['java', 'javac', 'yosys', 'iverilog', 'vvp']}
    if not all(tools.values()):
        raise SystemExit('Missing required local tools: ' + repr(tools))
    receipt = dict(startedAt=datetime.now(timezone.utc).isoformat(), mode='existing-production-jars-no-gradle',
                   sourceHead=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                   coreProductionTrees={folder:subprocess.check_output(['git','rev-parse','HEAD:'+folder],cwd=ROOT,text=True).strip() for folder in ['core/src/commonMain','core/src/jvmMain']},
                   artifactsBefore={key:artifact(path,out) for key,path in jars.items()}, commands=[], cases=[])

    def run(label, argv, cwd=ROOT, expected=0):
        command = [str(x) for x in argv]
        result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        (out/(label+'.stdout')).write_text(result.stdout)
        (out/(label+'.stderr')).write_text(result.stderr)
        receipt['commands'].append(dict(name=label, argv=command, returncode=result.returncode))
        if result.returncode != expected:
            raise AssertionError(f'{label}: exit {result.returncode}; see {label}.stderr')
        return result.stdout

    try:
        receipt['tools'] = {name:dict(path=tools[name], sha256=digest(tools[name])) for name in tools}
        for name, flags in [('java',['-version']),('javac',['-version']),('yosys',['-V']),('iverilog',['-V']),('vvp',['-V'])]:
            text=run('version-'+name,[tools[name],*flags])
            receipt['tools'][name]['version']=(text+(out/('version-'+name+'.stderr')).read_text()).strip()
        classes=out/'classes'; classes.mkdir(exist_ok=True)
        source_dir=Path(__file__).resolve().parent
        mcp=f"{classes}:{jars['mobile']}:{jars['stdlib']}"
        dcp=f"{classes}:{jars['desktop']}"
        run('compile-mobile',[tools['javac'],'-cp',f"{jars['mobile']}:{jars['stdlib']}",'-d',classes,source_dir/'HdlImportMobileOracle.java'])
        run('compile-desktop',[tools['javac'],'-cp',jars['desktop'],'-d',classes,source_dir/'HdlImportDesktopOracle.java'])
        receipt['sourceFiles']=[artifact(p,out) for p in [Path(__file__),source_dir/'HdlImportMobileOracle.java',source_dir/'HdlImportDesktopOracle.java',ROOT/'companion/hdl_import.py',ROOT/'companion/hdl_import_cells.v']]
        for case in CASES:
            folder=out/case['name']; folder.mkdir(exist_ok=True)
            uploads=folder/'uploads'; uploads.mkdir(exist_ok=True)
            for name in case['files']:
                shutil.copy2(ROOT/'fixtures/hdl/import'/name,uploads/name)
            work=folder/'job'; work.mkdir(exist_ok=True)
            context=RecordingContext(work,{name:uploads/name for name in case['files']},{'yosys':tools['yosys']},threading.Event(),user_confirmed=True)
            params=json.loads(run(case['name']+'-request',[tools['java'],'-cp',mcp,'HdlImportMobileOracle','request',case['format'].upper().replace('-','_'),case['top'],uploads,','.join(case.get('defines',[])),*case['files']]))
            synthesis=synthesize(context,params)
            netlist_path=context.output_path('netlist.json')
            netlist=json.loads(netlist_path.read_text())
            if synthesis['netlist']['sha256'] != digest(netlist_path):
                raise AssertionError('Companion artifact hash mismatch')
            (folder/'companion-result.json').write_text(json.dumps(synthesis,indent=2)+'\n')
            circuit=folder/'imported.circ'
            imported=run(case['name']+'-import',[tools['java'],'-cp',mcp,'HdlImportMobileOracle','import',netlist_path,case['top'],circuit,uploads,*case['files']])
            module_map=dict(row.split('\t')[1:] for row in imported.splitlines())
            repeated=folder/'repeated.circ'
            run(case['name']+'-repeat-import',[tools['java'],'-cp',mcp,'HdlImportMobileOracle','import',netlist_path,case['top'],repeated,uploads,*case['files']])
            if circuit.read_bytes()!=repeated.read_bytes():
                raise AssertionError('Importer output is not reproducible for identical input')
            check_archived_sources(circuit,uploads,case['files'])
            roundtrip=folder/'roundtrip.circ'
            run(case['name']+'-roundtrip',[tools['java'],'-cp',mcp,'HdlImportMobileOracle','roundtrip',circuit,roundtrip])
            check_archived_sources(roundtrip,uploads,case['files'])
            values=list(itertools.product(*(range(1<<width) for _,width in case['inputs'])))
            tb=folder/'testbench.v'; tb.write_text(testbench(case,values))
            source_files=[uploads/name for name in params['sourceNames']]
            if case['format']=='blif':
                reference=folder/'original-blif.v'
                ys=folder/'reference.ys'
                ys.write_text(f'read_blif "{source_files[0]}"\nhierarchy -check -top {case["top"]}\nwrite_verilog "{reference}"\n')
                run(case['name']+'-blif-reference',[tools['yosys'],'-Q','-T','-s',ys])
                source_files=[reference]
            reference_exe=folder/'reference.vvp'
            run(case['name']+'-compile-reference',[tools['iverilog'],'-g2012','-s','testbench','-I',uploads,*['-D'+d for d in case.get('defines',[])],'-o',reference_exe,*source_files,tb])
            reference=run(case['name']+'-reference',[tools['vvp'],reference_exe])
            samples=[row.split('\t') for row in reference.splitlines() if row.startswith('R\t')]
            if len(samples)!=len(values) or any(row[1]!=str(i) for i,row in enumerate(samples)):
                raise AssertionError('Reference simulator omitted or reordered samples')
            if any(len(sample[2:])!=len(case['outputs']) or any(not re.fullmatch('[01]{'+str(width)+'}',bits) for (_,width),bits in zip(case['outputs'],sample[2:])) for sample in samples):
                raise AssertionError('Reference outputs are not fully binary with the declared widths')
            ports=case['inputs']+case['outputs']
            vectors=folder/'vectors.txt'
            vectors.write_text(' '.join(name+(f'[{width}]' if width>1 else '') for name,width in ports)+'\n'+'\n'.join(' '.join([format(value,f'0{width}b') for (_,width),value in zip(case['inputs'],row)]+sample[2:]) for row,sample in zip(values,samples))+'\n')
            expected=['\t'.join(['V',str(index),'PASS',*[format(value,f'0{width}b') for (_,width),value in zip(case['inputs'],row)],*sample[2:]]) for index,(row,sample) in enumerate(zip(values,samples))]
            mobile=run(case['name']+'-mobile',[tools['java'],'-cp',mcp,'HdlImportMobileOracle','observe',circuit,module_map[case['top']],vectors])
            desktop=run(case['name']+'-desktop',[tools['java'],'-Djava.awt.headless=true','-cp',dcp,'HdlImportDesktopOracle',circuit,module_map[case['top']],vectors])
            structure,rows=compare(mobile,desktop,expected)
            check_netlist_structure(case,netlist,module_map,structure)
            entry=dict(name=case['name'],format=case['format'],parameters=params,moduleMap=module_map,rows=len(rows),structure=structure,observations=rows,
                       inputs=[artifact(uploads/name,out) for name in case['files']],netlist=artifact(netlist_path,out),circuit=artifact(circuit,out),vectors=artifact(vectors,out),
                       desktop=artifact(out/(case['name']+'-desktop.stdout'),out),mobile=artifact(out/(case['name']+'-mobile.stdout'),out),
                       synthesisCommands=context.commands,synthesisScript=artifact(work/'mobile_import.ys',out),
                       synthesisLogs=[artifact(path,out) for path in sorted(work.glob('command-*.*'))],
                       reproducible=True,sourceArchiveExactBeforeAndAfterRoundtrip=True)
            receipt['cases'].append(entry)
            if case['name']=='hierarchy':
                bad=desktop.replace('H\t','H\twrong_',1)
                receipt.setdefault('falseControls',[]).append(reject('changed-hierarchy',lambda:compare(mobile,bad,expected)))
                bad=desktop.replace('\tINPUT\t1\t','\tINPUT\t2\t',1)
                receipt['falseControls'].append(reject('changed-pin-width',lambda:compare(mobile,bad,expected)))
                missing_source=folder/'missing-source.circ'
                altered=ET.parse(circuit)
                altered.getroot().remove(altered.getroot().find('options'))
                altered.write(missing_source,encoding='unicode')
                receipt['falseControls'].append(reject('missing-source-archive',lambda:check_archived_sources(missing_source,uploads,case['files'])))
            if case['name']=='blif-and':
                bad_circuit=folder/'mutated-nand.circ'
                original=circuit.read_text()
                if original.count('name="AND Gate"')!=1:
                    raise AssertionError('False-control fixture must contain one AND gate')
                bad_circuit.write_text(original.replace('name="AND Gate"','name="NAND Gate"'))
                bad=run('negative-mutated-nand-desktop',[tools['java'],'-Djava.awt.headless=true','-cp',dcp,'HdlImportDesktopOracle',bad_circuit,module_map[case['top']],vectors])
                receipt['falseControls'].append(reject('actual-desktop-nand-mutation',lambda:compare(mobile,bad,expected)))
                if all(row.split('\t')[2]=='PASS' for row in observations(bad)[1]):
                    raise AssertionError('Actual desktop mutation did not change any truth-table result')
                actual_rows=observations(bad)[1]
                receipt['falseControls'][-1]['observedFailedRows']=sum(row.split('\t')[2]=='FAIL' for row in actual_rows)
                receipt['falseControls'][-1]['output']=artifact(out/'negative-mutated-nand-desktop.stdout',out)
                baseline_structure=observations(mobile)[0]
                receipt['falseControls'].append(reject('actual-desktop-nand-truth-table',lambda:compare(mobile,'\n'.join(baseline_structure+actual_rows),expected)))
        receipt['status']='PASS'
        receipt['rowsCompared']=sum(case['rows'] for case in receipt['cases'])
    except Exception as error:
        receipt['status']='FAIL';receipt['error']=str(error)
    finally:
        receipt['artifactsAfter']={key:artifact(path,out) for key,path in jars.items()}
        receipt['artifactsUnchanged']=receipt['artifactsBefore']==receipt['artifactsAfter']
        receipt['finishedAt']=datetime.now(timezone.utc).isoformat()
        if not receipt['artifactsUnchanged']:
            receipt['status']='FAIL';receipt['error']='Input artifact identity changed during execution'
        (out/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({key:receipt.get(key) for key in ['status','error','rowsCompared','artifactsUnchanged']},ensure_ascii=False))
    return 0 if receipt['status']=='PASS' else 1


if __name__=='__main__':
    raise SystemExit(main())
