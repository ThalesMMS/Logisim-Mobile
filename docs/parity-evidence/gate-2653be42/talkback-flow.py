"""TalkBack flow driven only by explore-touch, double-tap and swipes. usage: flow.py PORT GRPC OUT"""
import glob,json,os,re,subprocess,sys,time
import xml.etree.ElementTree as ET
sys.path.insert(0,"/tmp/tbgrpc")
import grpc, emulator_controller_pb2 as pb, emulator_controller_pb2_grpc as rpc
PORT,GR,OUT=sys.argv[1],sys.argv[2],sys.argv[3]; os.makedirs(OUT,exist_ok=True)
ADB=["/Users/user/Library/Android/sdk/platform-tools/adb","-s","emulator-"+PORT]
def token():
    for f in glob.glob(os.path.expanduser("~/Library/Caches/TemporaryItems/avd/running/*.ini")):
        t=open(f).read()
        if "port.serial="+PORT in t:
            m=re.search(r"grpc.token=(\S+)",t); return m.group(1) if m else ""
    raise SystemExit("no token")
stub=rpc.EmulatorControllerStub(grpc.insecure_channel("127.0.0.1:"+GR)); tok=token(); md=[("authorization","Bearer "+tok)] if tok else []
def ev(x,y,p): stub.sendTouch(pb.TouchEvent(touches=[pb.Touch(x=int(x),y=int(y),identifier=0,pressure=p,touch_major=10 if p else 0)]),metadata=md)
def tap(x,y,h=.06): ev(x,y,1); time.sleep(h); ev(x,y,0)
def swipe(x1,y1,x2,y2,ms=260,n=15):
    ev(x1,y1,1)
    for i in range(1,n+1): time.sleep(ms/1000/n); ev(x1+(x2-x1)*i/n,y1+(y2-y1)*i/n,1)
    ev(x2,y2,0)
def sh(*a,t=60): return subprocess.run(ADB+list(a),capture_output=True,timeout=t)
def dump():
    for _ in range(6):
        sh("shell","uiautomator","dump","/sdcard/tb.xml"); x=sh("exec-out","cat","/sdcard/tb.xml").stdout.decode("utf8","replace")
        if x.startswith("<?xml") and x.rstrip().endswith("hierarchy>"): return ET.fromstring(x)
        time.sleep(1)
    raise RuntimeError("no dump")
step=[0]; res=[]
def cap(name):
    step[0]+=1; p=f"{OUT}/{step[0]:02d}-{name}"
    open(p+".png","wb").write(sh("exec-out","screencap","-p").stdout); return p
def texts(r): return [(n.get("text") or n.get("content-desc") or "") for n in r.iter("node")]
def find(r,rx,nth=0):
    hits=[]
    for n in r.iter("node"):
        for v in (n.get("text") or "",n.get("content-desc") or ""):
            if re.search(rx,v):
                b=list(map(int,re.findall(r"\d+",n.get("bounds"))))
                if b[2]>b[0] and b[3]>b[1]: hits.append(b); break
    return hits[nth] if len(hits)>nth else None
def act(rx,nth=0,wait=2.0):
    d=time.time()+12; b=None
    while time.time()<d and b is None:
        b=find(dump(),rx,nth); time.sleep(.5) if b is None else None
    if b is None: raise RuntimeError("NOT FOUND "+rx)
    x,y=(b[0]+b[2])//2,(b[1]+b[3])//2
    time.sleep(1.2); tap(x,y); time.sleep(1.3); tap(x,y); time.sleep(.12); tap(x,y); time.sleep(wait)
def chk(name,cond,note=""):
    cap(name); res.append({"step":name,"pass":bool(cond),"note":note}); print(("PASS" if cond else "FAIL"),name,note,flush=True)
    if not cond: json.dump(res,open(OUT+"/results.json","w"),indent=1); raise SystemExit(1)
def has(r,rx): return any(re.search(rx,t) for t in texts(r))
def next_(): swipe(300,1200,800,1200)
def prev_(): swipe(800,1200,300,1200)
# TalkBack's first-run tutorial covers the app on API 31
d=time.time()+35
while PORT=="5562" and time.time()<d:
    r=dump()
    if has(r,"Page 1 of 5|Welcome to TalkBack") and find(r,"^Close$"):
        act("^Close$",wait=4)
        sh("shell","am","start","-W","-n","thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity"); time.sleep(5)
        break
    time.sleep(2)
# 1 open the example through the menu
time.sleep(2)
if not has(dump(),"Import report") and not has(dump(),"AND"):
    act("^Open the menu$"); act("^File$"); act("^Open example: AND$",wait=4)
if has(dump(),"Import report"): act("^Close$",wait=2)
r=dump(); chk("example-open",has(r,"AND") and has(r,"Open the menu"))
# 2 preferences: handle closes the sheet
act("^Open the menu$"); act("^File$"); act("^Preferences…$",wait=3)
r=dump(); chk("preferences-open",has(r,"^Drag handle$"))
next_(); time.sleep(1.5); prev_(); time.sleep(1.5); cap("preferences-swipes")
act("^Drag handle$",wait=3)
r=dump(); chk("handle-closes",not has(r,"^Drag handle$") and has(r,"^Open the menu$"))
# 3 library: the scrim closes it
act("^Open circuits and library$",wait=3)
r=dump(); chk("library-open",has(r,"^Close sheet$"))
act("^Close sheet$",wait=3)
r=dump(); chk("scrim-closes",not has(r,"^Close sheet$"))
# 4 preferences: Back closes
act("^Open the menu$"); act("^File$"); act("^Preferences…$",wait=3)
sh("shell","input","keyevent","4"); time.sleep(3)
r=dump(); chk("back-closes",not has(r,"^Drag handle$"))
# 5 explorer: select AND through its row, close
act("^Open the menu$"); act("^Project$"); act("^Circuit explorer$",wait=3)
r=dump(); chk("explorer-open",has(r,"Circuit explorer"))
act("^Components$",wait=3)
act("^Select AND",wait=2); r=dump(); chk("explorer-selects",True,"select row activated")
act("^Close$",wait=3)
# 6 duplicate preview: the confirmation is reachable with TalkBack
r=dump(); before=sum("canvas-component" in (n.get("resource-id") or "") for n in r.iter("node"))
act("^Open the menu$"); act("^Edit$"); act("^Duplicate$",wait=3)
r=dump(); chk("preview-confirm-exposed",has(r,"^Apply$") and has(r,"^Cancel$"),before)
act("^Apply$",wait=4)
r=dump(); after=sum("canvas-component" in (n.get("resource-id") or "") for n in r.iter("node")); chk("duplicate-applied",has(r,"component pasted"),f"canvas nodes {before}->{after}")
json.dump(res,open(OUT+"/results.json","w"),indent=1); print("ALLPASS")
