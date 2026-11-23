# -*- coding: utf-8 -*-
import importlib.util
import re
from pathlib import Path

spec = importlib.util.spec_from_file_location("chunk", "scripts/_chat_chunk.py")
chunk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chunk)

text = Path("src/main/java/com/formacraft/client/ui/panel/ChatPanel.java").read_text(encoding="utf-8")
# methods may already be stubs — pull bodies from git
import subprocess

orig = subprocess.check_output(
    ["git", "show", "HEAD:src/main/java/com/formacraft/client/ui/panel/ChatPanel.java"],
    text=True,
    encoding="utf-8",
)

methods = [
    "computeChatLayout",
    "computeInputBounds",
    "enableScissor",
    "disableScissor",
    "chatLineHeight",
    "getScaledMouseX",
    "getScaledMouseY",
]
parts = []
for name in methods:
    body = chunk.find_method(orig, name)[2]
    parts.append(chunk.to_helper_method(body, name, set(methods) - {name}))

helper = (
    "package com.formacraft.client.ui.panel.chat;\n\n"
    "import com.formacraft.client.ai.context.SelectionContext;\n"
    "import com.formacraft.client.ui.panel.ChatPanel;\n"
    "import com.formacraft.client.ui.UiTheme;\n"
    "import net.minecraft.client.gui.DrawContext;\n"
    "\npublic final class ChatLayoutSupport {\n"
    "    private ChatLayoutSupport() {}\n"
    + "\n".join(parts)
    + "}\n"
)
helper = re.sub(r"\(host,\s*\)", "(host)", helper)
# Qualify nested types carefully (not class name)
helper = re.sub(r"(?<![\w.])ChatLayout(?!\w)", "ChatPanel.ChatLayout", helper)
helper = re.sub(r"(?<![\w.])InputBounds(?!\w)", "ChatPanel.InputBounds", helper)
helper = helper.replace("ChatPanel.ChatPanel.", "ChatPanel.")
# undo if class name mangled
helper = helper.replace("class ChatPanel.ChatLayoutSupport", "class ChatLayoutSupport")
helper = helper.replace("ChatLayoutSupport()", "ChatLayoutSupport()")
Path("src/main/java/com/formacraft/client/ui/panel/chat/ChatLayoutSupport.java").write_text(
    helper, encoding="utf-8"
)
print(helper.splitlines()[7])
print("methods ok", "computeChatLayout" in helper)
