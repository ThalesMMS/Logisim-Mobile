#!/usr/bin/env python3
"""Generate I/O geometry expectations from the pinned desktop JAR."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
TARGET = ROOT / 'core/src/commonTest/kotlin/dev/logisim/mobile/core/IoGeometryOracleTest.kt'
CASES = []

def case(kind, factory, **attributes):
    CASES.append((f'case{len(CASES)}', kind, factory, attributes))

for facing in ['east', 'north', 'west', 'south']:
    case('BUTTON', 'Button', facing=facing)
    case('LED', 'LED', facing=facing)
    case('RGB_LED', 'RGBLED', facing=facing)
    for count in [1, 8, 32]:
        case('DIP_SWITCH', 'DipSwitch', facing=facing, number=str(count))
    for bits in [2, 5]:
        case('JOYSTICK', 'Joystick', facing=facing, bits=str(bits))
    for mode in ['row', 'column']:
        case('LED_BAR', 'LedBar', facing=facing, inputtype=mode, matrixcols='8', height='3')
for height in [1, 8]:
    for mode in ['row', 'column']:
        case('LED_BAR', 'LedBar', facing='east', inputtype=mode, matrixcols='4', height=str(height))
for decimal in ['true', 'false']:
    case('HEX_DISPLAY', 'Hex Digit Display', decimalPoint=decimal)
    case('SEVEN_SEGMENT_DISPLAY', '7-Segment Display', inputmode='separate', decimalPoint=decimal)
    for facing in ['east', 'north', 'west', 'south']:
        case('SEVEN_SEGMENT_DISPLAY', '7-Segment Display', inputmode='bus', busside=facing, decimalPoint=decimal)
for mode in ['column', 'row', 'select']:
    for rows in [1, 7]:
        for side in ['bl', 'tr']:
            case('DOT_MATRIX', 'DotMatrix', inputtype=mode, matrixrows=str(rows), matrixcols='5', selloc=side)
for rows, cols in [(1, 1), (8, 32), (48, 120)]:
    case('TTY', 'TTY', rows=str(rows), cols=str(cols))
for size in ['normal', 'large']:
    for mode in ['raw', 'pmod_kypd']:
        case('MATRIX_KEYPAD', 'MatrixKeypad', size=size, mode=mode)
case('KEYBOARD', 'Keyboard', buflen='1')
case('KEYBOARD', 'Keyboard', buflen='256')


def quoted(value):
    return json.dumps(value, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('desktop_jar', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='logisim-io-oracle-') as temporary:
        temporary = Path(temporary)
        project = ET.Element('project', source='5.1.0-dev', version='1.0')
        ET.SubElement(project, 'lib', desc='#I/O', name='0')
        ET.SubElement(project, 'main', name=CASES[0][0])
        for name, _, factory, attributes in CASES:
            circuit = ET.SubElement(project, 'circuit', name=name)
            part = ET.SubElement(circuit, 'comp', lib='0', name=factory, loc='(200,200)')
            for key, value in attributes.items():
                ET.SubElement(part, 'a', name=key, val=value)
        fixture = temporary / 'io.circ'
        ET.ElementTree(project).write(fixture, encoding='utf-8', xml_declaration=True)
        subprocess.run(['javac', '-cp', str(args.desktop_jar), '-d', str(temporary),
                        str(ROOT / 'scripts/Sap1DesktopOracle.java')], check=True)
        raw = subprocess.check_output(['java', '-Djava.awt.headless=true', '-cp', f'{temporary}:{args.desktop_jar}',
                                       'Sap1DesktopOracle', str(fixture), 'ports'], text=True)
    oracle = {}
    for line in raw.splitlines():
        fields = line.split('\t')
        if len(fields) < 4 or not fields[0].startswith('case'):
            continue
        geometry = list(map(int, re.findall(r'-?\d+', fields[3])))
        ports = [list(map(int, re.findall(r'-?\d+', port))) for port in fields[4:]]
        oracle[fields[0]] = geometry, ports
    assert len(oracle) == len(CASES), (len(oracle), len(CASES), raw)
    lines = ['package dev.logisim.mobile.core', '', 'import kotlin.test.Test', 'import kotlin.test.assertEquals', '',
             '/** Expected coordinates were generated from desktop fbb1eca6, not from the mobile layout. */',
             'class IoGeometryOracleTest {', '    @Test', '    fun layoutsMatchDesktopCoordinatesWidthsAndDirections() {',
             '        val cases = listOf(']
    for index, (name, kind, factory, attributes) in enumerate(CASES):
        geometry, ports = oracle[name]
        attrs = ', '.join(f'{quoted(key)} to {quoted(value)}' for key, value in attributes.items())
        expected = ', '.join('listOf(' + ', '.join(map(str, port)) + ')' for port in ports)
        lines.append(f'            Case(ComponentKind({quoted(kind)}), mapOf({attrs}), listOf({", ".join(map(str, geometry))}), listOf({expected})){"," if index < len(CASES)-1 else ""}')
    lines.extend(['        )', '        for (case in cases) {',
                  '            val definition = IoLibrary.definition(case.kind)!!',
                  '            val part = CircuitComponent("part", case.kind, GridPoint(200, 200), attributes = case.attributes)',
                  '            val actual = definition.layout(part, definition.schema.parse(case.attributes))',
                  '            val bounds = actual.bounds',
                  '            assertEquals(case.bounds, listOf(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top), "${case.kind.name} ${case.attributes} bounds")',
                  '            assertEquals(case.ports, actual.ports.map { listOf(it.position.x, it.position.y, it.width, when (it.direction) { PortDirection.INPUT -> 1; PortDirection.OUTPUT -> 2; PortDirection.INOUT -> 3 }) }, "${case.kind.name} ${case.attributes} ports")',
                  '        }', '    }', '',
                  '    private data class Case(val kind: ComponentKind, val attributes: Map<String, String>, val bounds: List<Int>, val ports: List<List<Int>>)', '}'])
    text = '\n'.join(lines) + '\n'
    if args.check:
        assert TARGET.read_text() == text, 'Desktop geometry fixtures changed'
    else:
        TARGET.write_text(text)
    print(f'PASS: generated {len(CASES)} independent desktop I/O geometry cases')

if __name__ == '__main__':
    main()
