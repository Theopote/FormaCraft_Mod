# -*- coding: utf-8 -*-
from pathlib import Path
import re

panel = Path("src/main/java/com/formacraft/client/ui/panel/ChatPanel.java")
t = panel.read_text(encoding="utf-8")

for meth in [
    "private void initWidgets()",
    "private ChatLayout computeChatLayout()",
    "private InputBounds computeInputBounds(",
    "private void chooseReferenceImageFile()",
    "private void sendCurrentMessage()",
    "private void toggleReferenceImage()",
]:
    t = t.replace(meth, meth.replace("private ", "public ", 1))

if "superMouseReleased" not in t:
    t = t.replace(
        "public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }",
        "public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }\n"
        "    public boolean superMouseReleased(double x, double y, int button) { return super.mouseReleased(x, y, button); }\n"
        "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }",
    )
panel.write_text(t, encoding="utf-8")

inter = Path("src/main/java/com/formacraft/client/ui/panel/chat/ChatInteraction.java")
s = inter.read_text(encoding="utf-8")
s = s.replace("super.mouseReleased(", "host.superMouseReleased(")
s = re.sub(r"(?<![\w.])drawTooltipCompat\(", "host.drawTooltipCompatPublic(", s)
s = re.sub(r"\(host,\s*\)", "(host)", s)
inter.write_text(s, encoding="utf-8")
print("fixed")
