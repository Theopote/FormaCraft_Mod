# -*- coding: utf-8 -*-
"""Rebuild ChatInputSupport from git HEAD ChatPanel method bodies."""
from __future__ import annotations
import re
import subprocess
from pathlib import Path

import importlib.util

spec = importlib.util.spec_from_file_location("chunk", "scripts/_chat_chunk.py")
chunk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chunk)

orig = subprocess.check_output(
    ["git", "show", "HEAD:src/main/java/com/formacraft/client/ui/panel/ChatPanel.java"],
    text=True,
    encoding="utf-8",
)

methods = [
    "initWidgets",
    "drawInputArea",
    "getInputHeight",
    "toggleReferenceImage",
    "hasReferenceImage",
    "chooseReferenceImageFile",
    "isReferenceImageUrl",
]
sibling = set(methods)
parts = []
for name in methods:
    _, _, body = chunk.find_method(orig, name)
    parts.append(chunk.to_helper_method(body, name, sibling - {name}))

helper = (
    "package com.formacraft.client.ui.panel.chat;\n\n"
    "import com.formacraft.client.ui.panel.ChatPanel;\n"
    "import com.formacraft.client.ui.UiTheme;\n"
    "import com.formacraft.client.ui.widget.HudClickSupport;\n"
    "import net.minecraft.client.gui.DrawContext;\n"
    "import net.minecraft.client.gui.tooltip.Tooltip;\n"
    "import net.minecraft.client.gui.widget.ButtonWidget;\n"
    "import net.minecraft.text.Text;\n"
    "import java.util.concurrent.CompletableFuture;\n"
    "\npublic final class ChatInputSupport {\n"
    "    private ChatInputSupport() {}\n"
    + "\n".join(parts)
    + "}\n"
)
helper = re.sub(r"\(host,\s*\)", "(host)", helper)
helper = re.sub(r"(?<![\w.])this\.", "host.", helper)
helper = helper.replace("ChatLayout", "ChatPanel.ChatLayout")
helper = helper.replace("ChatPanel.ChatPanel.", "ChatPanel.")
Path("src/main/java/com/formacraft/client/ui/panel/chat/ChatInputSupport.java").write_text(
    helper, encoding="utf-8"
)
print("rebuilt", helper.count("\n") + 1)
