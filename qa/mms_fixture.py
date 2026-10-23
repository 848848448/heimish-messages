#!/usr/bin/env python3
# Insert attachment-only MMS fixtures into the system MMS provider so the conversation
# list must label each by its real part type (Voice message / Video / GIF / Contact card).
# Exits 0 and prints FIXTURE_OK only if rows land and are readable back; otherwise prints
# the failure and exits 1 (caller treats that as "fixture unavailable", not a gate failure).
import subprocess,sys,time
def run(*a):
    r=subprocess.run(["adb","shell"]+list(a),capture_output=True,text=True)
    return (r.stdout+r.stderr).strip()
def insert(uri,*binds):
    return run("content","insert","--uri",uri,*sum([["--bind",b] for b in binds],[]))
CASES=[("5557770011","audio/amr","voice.amr"),("5557770012","video/mp4","clip.mp4"),
       ("5557770013","image/gif","dance.gif"),("5557770014","text/x-vcard","contact.vcf")]
now=int(time.time()); ok=0
for i,(num,ct,nm) in enumerate(CASES):
    out=insert("content://mms","thread_id:i:0","date:i:%d"%(now-60+i),"msg_box:i:1","read:i:1",
               "m_type:s:132","ct_t:s:application/vnd.wap.multipart.related","sub:s:")
    if "content://" not in out and "content" not in out.lower():
        print("INSERT_FAIL",num,out[:200]); continue
    mid=out.rstrip("/").split("/")[-1]
    if not mid.isdigit():
        q=run("content","query","--uri","content://mms","--projection","_id","--sort","date DESC LIMIT 1")
        ids=[w.split("=")[-1] for w in q.split() if w.startswith("_id=")]
        if not ids: print("NO_MID",num,q[:200]); continue
        mid=ids[0]
    # address the MMS to a recipient via addr table so the thread has members
    insert("content://mms/%s/addr"%mid,"msg_id:i:%s"%mid,"address:s:%s"%num,"type:i:137","charset:i:106")
    insert("content://mms/part","mid:i:%s"%mid,"ct:s:%s"%ct,"cl:s:%s"%nm,"name:s:%s"%nm,"text:s:")
    chk=run("content","query","--uri","content://mms/%s/part"%mid,"--projection","ct")
    print("PARTS",num,ct,"->",chk[:120])
    if ct in chk: ok+=1
print("FIXTURE_OK" if ok==len(CASES) else "FIXTURE_PARTIAL %d/%d"%(ok,len(CASES)))
sys.exit(0 if ok==len(CASES) else 1)
