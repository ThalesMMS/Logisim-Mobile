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
    if b is None:
        cap("not-found"); open(OUT+"/not-found.xml","wb").write(ET.tostring(dump())); raise RuntimeError("NOT FOUND "+rx)
    x,y=(b[0]+b[2])//2,(b[1]+b[3])//2
    time.sleep(1.2); tap(x,y); time.sleep(1.3); tap(x,y); time.sleep(.12); tap(x,y); time.sleep(wait)
def chk(name,cond,note=""):
    cap(name); res.append({"step":name,"pass":bool(cond),"note":note}); print(("PASS" if cond else "FAIL"),name,note,flush=True)
    if not cond: json.dump(res,open(OUT+"/results.json","w"),indent=1); raise SystemExit(1)
def has(r,rx): return any(re.search(rx,t) for t in texts(r))
def next_(): swipe(300,1200,800,1200)
def prev_(): swipe(800,1200,300,1200)

def canvas(r):
    out=[]
    for n in r.iter("node"):
        rid=n.get("resource-id") or ""
        if re.search(r"canvas-component:",rid):
            out.append(dict(id=rid,desc=n.get("content-desc") or "",state=n.get("checked"),b=list(map(int,re.findall(r"\d+",n.get("bounds"))))))
    return out
def menu(item):
    for _ in range(4):
        if find(dump(),item): break
        act("^Open the menu$",wait=2.5)
        if find(dump(),item): break
        time.sleep(2)
    act(item)
def go(item,target,done,wait=3):
    for _ in range(6):
        r=dump()
        if has(r,done): return
        try:
            if find(r,target): act(target,wait=wait)
            else: menu(item); time.sleep(1.5)
        except RuntimeError as e: print("retry",e,flush=True); time.sleep(2)
    raise RuntimeError("NOT REACHED "+done)
def overlap(a,b): return a[0]<b[2] and a[2]>b[0] and a[1]<b[3] and a[3]>b[1]
# 1 example
time.sleep(2)
if not has(dump(),"Import report") and not has(dump(),"AND"):
    act("^Open the menu$"); act("^File$"); act("^Open example: AND$",wait=4)
if has(dump(),"Import report"): act("^Close$",wait=2)
r=dump(); chk("example-open",has(r,"AND") and has(r,"Open the menu"))
# 2 select AND through the explorer and duplicate it
go("^Project$","^Circuit explorer$","^Components$")
act("^Components$",wait=3); act("^Select AND",wait=2); act("^Close$",wait=3)
r=dump(); before=canvas(r); print("BEFORE",json.dumps(before),flush=True)
go("^Edit$","^Duplicate$","^Apply$")
r=dump(); chk("preview-confirm-exposed",has(r,"^Apply$") and has(r,"^Cancel$"),len(before))
act("^Apply$",wait=4)
r=dump(); after=canvas(r); print("AFTER",json.dumps(after),flush=True)
chk("duplicate-applied",has(r,"component pasted"),"status present")
chk("one-node-per-component",len(after)==len(before)+1,f"canvas nodes {len(before)}->{len(after)}")
chk("nodes-disjoint",not any(overlap(a["b"],b["b"]) for i,a in enumerate(after) for b in after[i+1:]))
ands=[n for n in after if "AND" in n["desc"] or re.search(r"\bAND\b",n["desc"])]
print("ANDS",json.dumps(ands),flush=True)
chk("two-and-nodes",len(ands)==2,f"{len(ands)} AND nodes")
sel=[n for n in ands if n["state"]=="true"]
other=[n for n in ands if n["state"]!="true"]
chk("unselected-and-exists",len(other)>=1,f"selected={len(sel)} unselected={len(other)}")
# 3 reach the other AND with TalkBack: explore-touch to focus, double-tap to activate
t=other[0]; x,y=(t["b"][0]+t["b"][2])//2,(t["b"][1]+t["b"][3])//2
time.sleep(1.2); tap(x,y); time.sleep(1.3); cap("explore-touch-unselected"); tap(x,y); time.sleep(.12); tap(x,y); time.sleep(3)
r=dump(); now=canvas(r); print("AFTER_TAP",json.dumps(now),flush=True)
hit=[n for n in now if n["id"]==t["id"]]
chk("double-tap-selects-other",len(hit)==1 and hit[0]["state"]=="true",t["id"])
chk("still-one-node-per-component",len(now)==len(after),f"{len(now)} canvas nodes")
# 4 linear TalkBack swipes do not skip or crash
for _ in range(8): next_(); time.sleep(1.2)
r=dump(); chk("swipes-keep-tree",len(canvas(r))==len(after))
# 5 explorer lists both ANDs
go("^Project$","^Circuit explorer$","^Components$"); act("^Components$",wait=3)
r=dump(); print("EXPLORER",[t_ for t_ in texts(r) if re.search("Showing|^Select AND",t_)],flush=True)
chk("explorer-lists-both",has(r,"^Showing 5 components"),"count text")
act("^Close$",wait=3)
json.dump(res,open(OUT+"/results.json","w"),indent=1); print("ALLPASS")
