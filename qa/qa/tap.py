#!/usr/bin/env python3
# usage: tap.py "text" [index] -> taps first node whose text/desc contains it (case-insens). exit 1 if missing.
import subprocess,sys,re,xml.etree.ElementTree as ET
def dump():
    subprocess.run(["adb","shell","uiautomator","dump","/sdcard/u.xml"],capture_output=True)
    x=subprocess.run(["adb","exec-out","cat","/sdcard/u.xml"],capture_output=True,text=True).stdout
    return ET.fromstring(x[x.find("<"):])
def nodes(r):
    for n in r.iter("node"):
        m=re.findall(r"\d+",n.get("bounds",""))
        if len(m)==4:
            yield n,[int(i) for i in m]
if __name__=="__main__":
    q=sys.argv[1].lower(); idx=int(sys.argv[2]) if len(sys.argv)>2 else 0
    hits=[(n,b) for n,b in nodes(dump()) if q in (n.get("text","")+" "+n.get("content-desc","")).lower()]
    if len(hits)<=idx: print("MISSING",q); sys.exit(1)
    b=hits[idx][1]; subprocess.run(["adb","shell","input","tap",str((b[0]+b[2])//2),str((b[1]+b[3])//2)]); print("tapped",q)
