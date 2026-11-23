# -*- coding: utf-8 -*-
from pathlib import Path
import re

# Fix this. -> host. in ChatSendSupport
p = Path("src/main/java/com/formacraft/client/ui/panel/chat/ChatSendSupport.java")
t = p.read_text(encoding="utf-8")
t = re.sub(r"(?<![\w.])this\.", "host.", t)
p.write_text(t, encoding="utf-8")

# Publicize needed ChatPanel methods
panel = Path("src/main/java/com/formacraft/client/ui/panel/ChatPanel.java")
pt = panel.read_text(encoding="utf-8")
for meth in [
    "private boolean hasReferenceImage()",
    "private void setPendingPhase(",
    "private void startElapsedTicker(",
    "private @NotNull String getString(",
    "private void renderPendingElapsed(",
]:
    pt = pt.replace(meth, meth.replace("private ", "public ", 1))
panel.write_text(pt, encoding="utf-8")

# Patch qualify in script for future
sp = Path("scripts/_chat_chunk.py")
s = sp.read_text(encoding="utf-8")
if 'out = out.replace("this.", "host.")' not in s:
    s = s.replace(
        "out = out.replace(\"host.host.\", \"host.\")",
        "out = re.sub(r\"(?<![\\w.])this\\.\", \"host.\", out)\n    out = out.replace(\"host.host.\", \"host.\")",
    )
    sp.write_text(s, encoding="utf-8")
print("patched")
