import subprocess, re, sys, time, xml.etree.ElementTree as ET
ADB=[str(__import__('pathlib').Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s','emulator-5584']
PKG='thalesmms.logisim.mobile.e2e'; TEST=PKG+'.test'; OUT='/tmp/and345'
def adb(*a, input=None): return subprocess.run(ADB+list(a), input=input, capture_output=True, timeout=60).stdout.decode(errors='replace')
def dump(name):
    adb('shell','uiautomator','dump','/sdcard/ui.xml'); x=adb('shell','cat','/sdcard/ui.xml')
    open(f'{OUT}/{name}.xml','w').write(x); return ET.fromstring(x[x.index('<'):])
def bounds(n): return tuple(map(int, re.findall(r'-?\d+', n.get('bounds'))))
def find(root, **kw): return [n for n in root.iter('node') if all(v in (n.get(k) or '') for k,v in kw.items())]
def shot(name): open(f'{OUT}/{name}.png','wb').write(subprocess.run(ADB+['exec-out','screencap','-p'],capture_output=True).stdout)
def wait(pred, name, timeout=60):
    end=time.time()+timeout
    while time.time()<end:
        r=dump(name); 
        if pred(r): return r
        time.sleep(2)
    raise SystemExit(f'timeout waiting {name}')
step=sys.argv[1]
if step=='open':
    r=dump('start')
    for n in find(r, text='Close')+find(r, text='Got it'):
        b=bounds(n); adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)); time.sleep(2)
    print(adb('shell','am','start','-W','-f','0x18000000','-n',f'{TEST}/dev.logisim.mobile.documents.DocumentFixtureLauncherActivity','--es','action','view','--es','fixtureUri',f'content://{TEST}.documents/document/e2e-345347000000000000000000000000ab.circ'))
    r=wait(lambda r: find(r, text='Open fixture'), 'launcher'); b=bounds(find(r,text='Open fixture')[0])
    adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
    r=wait(lambda r: any('canvas' in (n.get('resource-id') or '') for n in r.iter('node')), 'opened', 90); shot('opened')
    print([ (n.get('resource-id'), n.get('content-desc'), n.get('bounds')) for n in r.iter('node') if (n.get('resource-id') or '').startswith('canvas')][:20])
def tap_text(r, t):
    b=bounds(find(r,text=t)[0]); adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)); time.sleep(2)
if step=='probe':
    r=dump('probe0')
    if find(r,text='Close'): tap_text(r,'Close')
    r=dump('probe'); shot('probe')
    for n in r.iter('node'):
        rid=n.get('resource-id') or ''
        if rid.startswith('canvas') or rid.startswith('editor-status') or 'mode' in rid: print(rid, n.get('content-desc'), n.get('text'), n.get('bounds'))
S=1.95; PX=-46; PY=704
def scr(x,y): return str(round(x*S+PX)), str(round(y*S+PY))
def status(name):
    r=dump(name); return [n.get('text') for n in r.iter('node') if n.get('resource-id')=='editor-status'], r
if step=='tools':
    r=dump('tools')
    for n in r.iter('node'):
        d=(n.get('content-desc') or '')+(n.get('text') or '')
        if n.get('clickable')=='true' and d: print(repr(d[:50]), n.get('resource-id'), n.get('bounds'), n.get('selected'))
if step.startswith('tap'):
    for i,(x,y) in enumerate([(97,100),(97,100),(87,100),(97,100)]):
        adb('shell','input','tap',*scr(x,y)); time.sleep(1.5)
        print((x,y), status(f'tap{i}')[0]); shot(f'tap{i}')
if step=='menu':
    for name,(x,y) in [('ram',(420,425)),('empty',(200,200)),('pin',(90,100))]:
        a=scr(x,y); adb('shell','input','swipe',a[0],a[1],a[0],a[1],'1200'); time.sleep(2)
        r=dump(f'menu-{name}'); shot(f'menu-{name}')
        ids=[n.get('resource-id') for n in r.iter('node') if (n.get('resource-id') or '').startswith('canvas-menu-') or n.get('resource-id')=='canvas-context-menu']
        texts=[n.get('text') for n in r.iter('node') if (n.get('resource-id') or '').startswith('canvas-menu-')]
        en=[n.get('enabled') for n in r.iter('node') if (n.get('resource-id') or '').startswith('canvas-menu-')]
        print(name, ids, [t for t in (n.get('text') for n in r.iter('node')) if t and t not in ('',)][:0])
        print('  texts:', [(n.get('text') or [c.get('text') for c in n.iter('node') if c.get('text')]) for n in r.iter('node') if (n.get('resource-id') or '').startswith('canvas-menu-')])
        adb('shell','input','keyevent','4'); time.sleep(1.5)
if step=='rotate':
    a=scr(90,100); adb('shell','input','swipe',a[0],a[1],a[0],a[1],'1200'); time.sleep(2)
    r=dump('rot0'); b=bounds([n for n in r.iter('node') if n.get('resource-id')=='canvas-menu-rotate_right'][0])
    adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)); time.sleep(2)
    print(status('rot1')[0]); shot('rotated')
    r=dump('rot2'); print([n.get('content-desc') for n in r.iter('node') if 'Undo' in (n.get('content-desc') or '')])
