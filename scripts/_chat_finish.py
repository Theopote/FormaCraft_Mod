# -*- coding: utf-8 -*-
"""Move layout + PreviewAdjust + AI status helpers out of ChatPanel."""
from __future__ import annotations
import importlib.util
import re
import subprocess
from pathlib import Path

spec = importlib.util.spec_from_file_location("chunk", "scripts/_chat_chunk.py")
chunk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chunk)

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/ChatPanel.java")
CHAT = Path("src/main/java/com/formacraft/client/ui/panel/chat")

# Prefer current panel text for methods still present; fall back to git for safety
text = PANEL.read_text(encoding="utf-8")
orig = subprocess.check_output(
    ["git", "show", "HEAD:src/main/java/com/formacraft/client/ui/panel/ChatPanel.java"],
    text=True,
    encoding="utf-8",
)


def extract_from(src: str, name: str) -> str:
    try:
        return chunk.find_method(src, name)[2]
    except SystemExit:
        return chunk.find_method(orig, name)[2]


# --- Layout ---
layout_methods = [
    "computeChatLayout",
    "computeInputBounds",
    "enableScissor",
    "disableScissor",
    "chatLineHeight",
    "getScaledMouseX",
    "getScaledMouseY",
]
parts = []
spans = []
for name in layout_methods:
    s, e, body = chunk.find_method(text, name)
    parts.append(chunk.to_helper_method(body, name, set(layout_methods) - {name}))
    spans.append((s, e, name, body))

layout = (
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
layout = re.sub(r"\(host,\s*\)", "(host)", layout)
layout = layout.replace("SelectionContext", "SelectionContext")  # ensure
# ChatLayout/InputBounds already qualified by to_helper_method
CHAT.joinpath("ChatLayoutSupport.java").write_text(layout, encoding="utf-8")

for s, e, name, body in sorted(spans, key=lambda x: -x[0]):
    text = text[:s] + chunk.delegate_stub(name, body, "ChatLayoutSupport") + text[e:]

# --- PreviewAdjustCommand: move whole record from current text ---
m = re.search(
    r"public record PreviewAdjustCommand\(int dx, int dy, int dz, String hint\)\s*\{",
    text,
)
if not m:
    raise SystemExit("PreviewAdjustCommand missing")
start = m.start()
brace = text.find("{", m.end() - 1)
depth = 0
i = brace
while i < len(text):
    if text[i] == "{":
        depth += 1
    elif text[i] == "}":
        depth -= 1
        if depth == 0:
            end = i + 1
            break
    i += 1
record_body = text[start:end]
# Convert nested record to top-level class with nested static helpers kept
# Replace private static helpers inside record - already public static from earlier
preview = (
    "package com.formacraft.client.ui.panel.chat;\n\n"
    "import net.minecraft.text.Text;\n"
    "import net.minecraft.util.math.Direction;\n"
    "import java.util.Locale;\n"
    "import java.util.regex.Matcher;\n"
    "import java.util.regex.Pattern;\n"
    "\n"
    + record_body.replace("public record PreviewAdjustCommand", "public record ChatPreviewAdjustCommand")
    + "\n"
)
# rename internal PreviewAdjustCommand refs
preview = preview.replace("PreviewAdjustCommand", "ChatPreviewAdjustCommand")
preview = preview.replace("ChatChatPreviewAdjustCommand", "ChatPreviewAdjustCommand")
CHAT.joinpath("ChatPreviewAdjustCommand.java").write_text(preview, encoding="utf-8")

# Replace record in panel with import alias via thin re-export type
text = (
    text[:start]
    + "/** @deprecated relocated — use ChatPreviewAdjustCommand */\n"
    "    public record PreviewAdjustCommand(int dx, int dy, int dz, String hint) {\n"
    "        public static PreviewAdjustCommand parse(String raw, Direction facing) {\n"
    "            var c = com.formacraft.client.ui.panel.chat.ChatPreviewAdjustCommand.parse(raw, facing);\n"
    "            return c == null ? null : new PreviewAdjustCommand(c.dx(), c.dy(), c.dz(), c.hint());\n"
    "        }\n"
    "    }\n"
    + text[end:]
)

# Fix ChatSendSupport to use ChatPreviewAdjustCommand directly (optional) — keep panel bridge

# --- AI message helpers ---
ai_methods = ["addAIMessage", "addAIError", "addAIStatus", "setPendingPhase", "renderPendingElapsed", "startElapsedTicker"]
parts = []
spans = []
# refresh text after previous edits — re-find
for name in ai_methods:
    s, e, body = chunk.find_method(text, name)
    parts.append(chunk.to_helper_method(body, name, set(ai_methods) - {name}))
    spans.append((s, e, name, body))

ai = (
    "package com.formacraft.client.ui.panel.chat;\n\n"
    "import com.formacraft.client.ui.panel.ChatPanel;\n"
    "import com.formacraft.client.ui.panel.chat.ChatMessage;\n"
    "import com.formacraft.common.model.build.BuildingSpec;\n"
    "import net.minecraft.text.Text;\n"
    "\npublic final class ChatAiMessages {\n"
    "    private ChatAiMessages() {}\n"
    + "\n".join(parts)
    + "}\n"
)
ai = re.sub(r"\(host,\s*\)", "(host)", ai)
ai = re.sub(r"(?<![\w.])this\.", "host.", ai)
CHAT.joinpath("ChatAiMessages.java").write_text(ai, encoding="utf-8")
for s, e, name, body in sorted(spans, key=lambda x: -x[0]):
    text = text[:s] + chunk.delegate_stub(name, body, "ChatAiMessages") + text[e:]

# Strip leftover big javadocs
text = re.sub(r"\n\s*/\*\*[\s\S]*?\*/", "\n", text)
text = re.sub(r"\n{3,}", "\n\n", text)

PANEL.write_text(text, encoding="utf-8")
print("panel", text.count("\n") + 1)
