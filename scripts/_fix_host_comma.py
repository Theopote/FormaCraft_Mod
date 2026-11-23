# -*- coding: utf-8 -*-
from pathlib import Path
import re

p = Path("src/main/java/com/formacraft/client/ui/panel/chat/ChatSendSupport.java")
t = p.read_text(encoding="utf-8")
t2 = re.sub(r"\(host,\s*\)", "(host)", t)
p.write_text(t2, encoding="utf-8")
print("fixed commas", t != t2)

sp = Path("scripts/_chat_chunk.py")
s = sp.read_text(encoding="utf-8")
needle = 'out = re.sub(r"(\\w+)\\(host, host,", r"\\1(host,", out)'
insert = needle + '\n    out = re.sub(r"\\(host,\\s*\\)", "(host)", out)'
if needle in s and "(host,\\s*)" not in s and '"(host)"' not in s.split(needle)[1][:80]:
    s = s.replace(needle, insert, 1)
    sp.write_text(s, encoding="utf-8")
    print("script patched")
