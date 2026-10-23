import sys; sys.path.insert(0,"qa")
from tap import dump,nodes
for n,b in nodes(dump()):
    t=(n.get("text") or n.get("content-desc") or "")
    if t or n.get("clickable")=="true": print(b,n.get("class","").split(".")[-1],repr(t),"click" if n.get("clickable")=="true" else "")
