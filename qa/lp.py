#!/usr/bin/env python3
# usage: lp.py "text" -> long-press (900ms) center of first node whose text/desc contains it. exit 1 if missing.
import sys,subprocess; sys.path.insert(0,"qa")
from tap import dump,nodes
q=sys.argv[1].lower()
hits=[(n,b) for n,b in nodes(dump()) if q in (n.get("text","")+" "+n.get("content-desc","")).lower()]
if not hits: print("MISSING",q); sys.exit(1)
b=hits[0][1]; x=(b[0]+b[2])//2; y=(b[1]+b[3])//2
subprocess.run(["adb","shell","input","swipe",str(x),str(y),str(x),str(y),"900"]); print("long-pressed",q)
