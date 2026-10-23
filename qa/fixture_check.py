#!/usr/bin/env python3
# usage: fixture_check.py <dump.txt> -> verifies the QA list fixture rendering:
# group titles wrap to two lines (EN + RTL), timestamps and unread badges render.
# exit 0 ok, exit 1 with reasons on stdout.
import sys, re
lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
def h(match):
    for l in lines:
        m = re.match(r"\[(\d+), (\d+), (\d+), (\d+)\]", l)
        if m and match in l:
            return int(m.group(4)) - int(m.group(2))
    return None
errs = []
one, grp, rtl, rtl2 = h("TextView 'Duvid Katz'"), h("Yankel Friedman"), h("שמואל בערגר"), h("אברהם יצחק")
if one is None: errs.append("control title missing")
if grp is None: errs.append("EN group title missing")
if rtl is None: errs.append("RTL group title missing")
if rtl2 is None: errs.append("RTL group title 2 missing")
for label, v in (("EN group", grp), ("RTL group", rtl), ("RTL group 2", rtl2)):
    if one and v is not None and v < one * 3 // 2:
        errs.append("%s title not two lines (h %s vs control %s)" % (label, v, one))
times = sum(1 for l in lines if re.search(r"TextView '\d{1,2}:\d{2} [AP]M'", l))
if times < 4: errs.append("expected 4 row timestamps, found %d" % times)
for badge in ("TextView '12'", "TextView '3'"):
    if not any(badge in l for l in lines): errs.append("unread badge missing: %s" % badge)
if errs:
    print("; ".join(errs)); sys.exit(1)
print("fixture ok")
