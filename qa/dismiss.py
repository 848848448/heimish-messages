#!/usr/bin/env python3
# Dismiss system dialogs (ANR "isn't responding", "has stopped") by tapping Wait/Close.
# These come from the emulator system (e.g. Pixel Launcher), never from the app under test;
# leaving them up blocks taps and poisons later screenshots.
import sys,subprocess; sys.path.insert(0,"qa")
from tap import dump,nodes
for _ in range(3):
    try: r=dump()
    except Exception: break
    ns=list(nodes(r))
    bad=[n for n,b in ns if any(k in (n.get("text","") or "") for k in ("isn't responding","has stopped","keeps stopping"))]
    if not bad: break
    tgt=None
    for n,b in ns:
        if (n.get("text","") or "")=="Wait": tgt=b; break
    if tgt is None:
        for n,b in ns:
            if (n.get("text","") or "")=="OK": tgt=b; break
    if tgt is None: break
    subprocess.run(["adb","shell","input","tap",str((tgt[0]+tgt[2])//2),str((tgt[1]+tgt[3])//2)])
    print("dismissed system dialog")
    import time; time.sleep(1)
