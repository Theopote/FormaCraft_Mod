# -*- coding: utf-8 -*-
"""Extract one ChatPanel method by name into a helper class. Usage: python scripts/_chat_chunk.py ClassName methodName [methodName...]"""
from __future__ import annotations
import re
import sys
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/ChatPanel.java")
CHAT = Path("src/main/java/com/formacraft/client/ui/panel/chat")

FIELDS = sorted([
    "messages", "sessionId", "inputBox", "inputHistory", "historyIndex", "historyDraft",
    "scrollOffset", "scrollRemainder", "chatMaxScrollOffset", "currentPrinter",
    "pendingRequestToken", "pendingThinkingIndex", "pendingStartMs", "pendingPhase",
    "selectable", "selectableMsgIndex", "selectingText", "renderedMessages",
    "sendButton", "stopButton", "referenceButton", "referenceImageUrl", "referenceImageFile",
    "readingReferenceImage", "promptMode", "client", "panelX", "panelY", "panelWidth", "panelHeight",
], key=len, reverse=True)

CONSTS = sorted([
    "CHAT_PADDING_H", "CHAT_PADDING_V", "CHAT_BUBBLE_INSET", "CHAT_BUBBLE_TEXT_PAD",
    "CHAT_BOTTOM_INSET", "CHAT_MESSAGE_GAP", "CHAT_INPUT_MIN_VISIBLE_LINES",
    "CHAT_INPUT_MAX_VISIBLE_LINES", "CHAT_INPUT_BOX_VERTICAL_PAD", "CHAT_INPUT_MAX_CHARS",
    "CHAT_INPUT_MAX_LINES", "CHAT_DIVIDER_HEIGHT", "SEND_BUTTON_SIZE", "STOP_BUTTON_SIZE",
    "CHAT_SPEC_SUMMARY_HEIGHT", "SOFT_TIMEOUT_SEC", "HARD_TIMEOUT_SEC", "LOG",
], key=len, reverse=True)

HOST_METHODS = sorted([
    "ensureLayout", "getContentY", "getContentHeight", "drawContentBackground", "drawContentBackgroundPublic",
    "blurInput", "sendCurrentMessage", "stopGenerating", "addAIError", "addAIMessage", "addAIStatus",
    "initWidgets", "toggleReferenceImage", "chooseReferenceImageFile", "hasReferenceImage",
    "computeChatLayout", "computeInputBounds", "drawMessages", "drawInputArea", "drawSpecSummary",
    "tickTextSelectionDrag", "tickInputSelectionDrag", "tryStartSelection", "getInputHeight",
    "getScaledMouseX", "getScaledMouseY", "chatLineHeight", "enableScissor", "disableScissor",
    "tryHandlePreviewAdjust", "notifyComponentCatalogStatus", "getStrings", "isHealthy",
    "buildHealthUrlOrNull", "formatThinkingElapsed", "mightReferencePlayerComponents",
    "setPendingPhase", "renderPendingElapsed", "startElapsedTicker", "superMouseClicked",
    "isMouseOver", "isReferenceImageUrl", "parse", "parseStep", "parseAbsoluteDirection",
    "parseRelativeDirection", "containsAny", "getString",
], key=len, reverse=True)


def find_method(text: str, name: str, prefer_override_full_key: bool = False) -> tuple[int, int, str]:
    if name == "keyPressed":
        pat = r"^[ \t]*public void keyPressed\(int keyCode, int scanCode, int modifiers\)[ \t]*\{"
    elif name == "mouseClicked":
        pat = r"^[ \t]*public boolean mouseClicked\(double mouseX, double mouseY, int button\)[ \t]*\{"
    elif name == "mouseDragged":
        pat = r"^[ \t]*public boolean mouseDragged\([^)]*\)[ \t]*\{"
    elif name == "mouseReleased":
        pat = r"^[ \t]*public boolean mouseReleased\([^)]*\)[ \t]*\{"
    elif name == "mouseScrolled":
        pat = r"^[ \t]*public void mouseScrolled\([^)]*\)[ \t]*\{"
    elif name == "charTyped":
        pat = r"^[ \t]*public void charTyped\(char chr\)[ \t]*\{"
    elif name == "drawCustomTooltip":
        pat = r"^[ \t]*protected boolean drawCustomTooltip\([^)]*\)[ \t]*\{"
    elif name == "drawContents":
        pat = r"^[ \t]*protected void drawContents\(DrawContext ctx\)[ \t]*\{"
    else:
        # Signature only (no leading javadoc group — optional /**...*/ backtracking ate the class body).
        pat = (
            rf"^[ \t]*(?:public|private|protected)[ \t]+(?:static[ \t]+)?"
            rf"(?:@\w+(?:\([^)]*\))?[ \t]+)*"
            rf"[A-Za-z0-9_.<>,?\[\]@ \t]+[ \t]+{re.escape(name)}[ \t]*\([^;\n]*\)[ \t]*\{{"
        )
    m = re.search(pat, text, re.M)
    if not m:
        raise SystemExit(f"not found: {name}")
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
                return start, i + 1, text[start : i + 1]
        i += 1
    raise SystemExit("unbalanced " + name)


def qualify(inner: str, sibling: set[str]) -> str:
    out = inner
    for c in CONSTS:
        out = re.sub(rf"(?<![\w.]){c}(?!\w)", f"ChatPanel.{c}", out)
    for f in FIELDS:
        out = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", out)
    for meth in HOST_METHODS:
        if meth in sibling:
            out = re.sub(rf"(?<![\w.]){meth}\(", f"{meth}(host, ", out)
        else:
            out = re.sub(rf"(?<![\w.]){meth}\(", f"host.{meth}(", out)
    out = re.sub(r"(?<![\w.])this\.", "host.", out)
    out = out.replace("host.host.", "host.")
    out = re.sub(r"(\w+)\(host, host,", r"\1(host,", out)
    out = re.sub(r"\(host,\s*\)", "(host)", out)
    # nested types
    for nt in ("RenderedMessage", "ChatLayout", "InputBounds", "PreviewAdjustCommand"):
        out = re.sub(rf"(?<![\w.]){nt}(?!\w)", f"ChatPanel.{nt}", out)
    out = out.replace("ChatPanel.ChatPanel.", "ChatPanel.")
    out = out.replace("host.drawContentBackground(", "host.drawContentBackgroundPublic(")
    out = out.replace("super.mouseClicked(", "host.superMouseClicked(")
    # static zero-arg / non-host helpers wrongly given host
    for z in ("chatLineHeight", "enableScissor", "disableScissor", "isReferenceImageUrl",
              "mightReferencePlayerComponents", "formatThinkingElapsed", "buildHealthUrlOrNull",
              "parseStep", "parseAbsoluteDirection", "parseRelativeDirection", "containsAny"):
        out = re.sub(rf"(?:host\.)?{z}\(host,\s*", f"{z}(", out)
        out = re.sub(rf"host\.{z}\(", f"{z}(", out)
    # PreviewAdjustCommand.parse is static on record — keep ChatPanel.PreviewAdjustCommand.parse
    out = out.replace("host.PreviewAdjustCommand", "ChatPanel.PreviewAdjustCommand")
    out = re.sub(r"ChatPanel\.PreviewAdjustCommand\.parse\(host,\s*", "ChatPanel.PreviewAdjustCommand.parse(", out)
    return out


def to_helper_method(body: str, name: str, sibling: set[str]) -> str:
    m = re.match(
        r"(?:/\*\*[\s\S]*?\*/\s*)?(?:@Override\s+)?((?:public|private|protected)\s+(?:static\s+)?(?:@\w+(?:\([^)]*\))?\s+)*([\w.<>,?\[\]]+(?:\s*<[^;{]+>)?)\s+"
        + re.escape(name)
        + r"\s*\(([^;]*)\))\s*\{",
        body,
    )
    if not m:
        # fallback simpler
        m2 = re.match(r"[^{]+\{", body, re.S)
        if not m2:
            raise SystemExit("sig fail " + name + "\n" + body[:180])
        header = m2.group(0)[:-1].strip()
        inner = body[m2.end() : -1]
        # rebuild
        is_static = " static " in header or header.startswith("static ")
        params = re.search(r"\((.*)\)", header, re.S).group(1).strip()
        ret = "void"
        rm = re.search(r"(?:public|private|protected)\s+(?:static\s+)?(?:@\w+\s+)?(.+?)\s+" + re.escape(name) + r"\s*\(", header)
        if rm:
            ret = rm.group(1).strip()
            ret = re.sub(r"^(public|private|protected)\s+", "", ret)
            ret = re.sub(r"^static\s+", "", ret)
        if is_static and name in ("isReferenceImageUrl", "enableScissor", "disableScissor", "chatLineHeight",
                                  "mightReferencePlayerComponents", "formatThinkingElapsed", "buildHealthUrlOrNull"):
            sig = f"public static {ret} {name}({params})"
        else:
            if params:
                sig = f"public static {ret} {name}(ChatPanel host, {params})"
            else:
                sig = f"public static {ret} {name}(ChatPanel host)"
        return f"    {sig} {{\n{qualify(inner, sibling)}\n    }}\n"

    full_sig, ret, params = m.group(1), m.group(2).strip(), m.group(3).strip()
    is_static = "static" in full_sig.split(name)[0]
    inner = body[m.end() : -1]
    if is_static and name in ("isReferenceImageUrl", "enableScissor", "disableScissor", "chatLineHeight",
                              "mightReferencePlayerComponents", "formatThinkingElapsed", "buildHealthUrlOrNull"):
        sig = f"public static {ret} {name}({params})"
    else:
        if params:
            sig = f"public static {ret} {name}(ChatPanel host, {params})"
        else:
            sig = f"public static {ret} {name}(ChatPanel host)"
    return f"    {sig} {{\n{qualify(inner, sibling)}\n    }}\n"


def ensure_panel_host_api(text: str) -> str:
    if "superMouseClicked" not in text:
        text = text.replace(
            "public class ChatPanel extends BasePanel {",
            "public class ChatPanel extends BasePanel {\n"
            "    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }\n"
            "    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }\n",
        )
    # publicize commonly needed
    reps = [
        ("private static final FcaLog LOG", "public static final FcaLog LOG"),
        ("private final MinecraftClient client = MinecraftClient.getInstance();",
         "public final MinecraftClient client = MinecraftClient.getInstance();"),
        ("private static class RenderedMessage", "public static class RenderedMessage"),
        ("private record ChatLayout", "public record ChatLayout"),
        ("private record InputBounds", "public record InputBounds"),
        ("private record PreviewAdjustCommand", "public record PreviewAdjustCommand"),
    ]
    for a, b in reps:
        text = text.replace(a, b)
    for c in CONSTS:
        text = text.replace(f"private static final int {c}", f"public static final int {c}")
        text = text.replace(f"private static final long {c}", f"public static final long {c}")
    field_reps = [
        (r"private final List<ChatMessage> messages", "public final List<ChatMessage> messages"),
        (r"private String sessionId", "public String sessionId"),
        (r"private MultilineTextInput inputBox", "public MultilineTextInput inputBox"),
        (r"private final List<String> inputHistory", "public final List<String> inputHistory"),
        (r"private int historyIndex", "public int historyIndex"),
        (r"private String historyDraft", "public String historyDraft"),
        (r"private int scrollOffset", "public int scrollOffset"),
        (r"private double scrollRemainder", "public double scrollRemainder"),
        (r"private int chatMaxScrollOffset", "public int chatMaxScrollOffset"),
        (r"private AIStreamPrinter currentPrinter", "public AIStreamPrinter currentPrinter"),
        (r"private long pendingRequestToken", "public long pendingRequestToken"),
        (r"private int pendingThinkingIndex", "public int pendingThinkingIndex"),
        (r"private long pendingStartMs", "public long pendingStartMs"),
        (r"private String pendingPhase", "public String pendingPhase"),
        (r"private final SelectableTextBlock selectable", "public final SelectableTextBlock selectable"),
        (r"private int selectableMsgIndex", "public int selectableMsgIndex"),
        (r"private boolean selectingText", "public boolean selectingText"),
        (r"private final List<RenderedMessage> renderedMessages", "public final List<RenderedMessage> renderedMessages"),
        (r"private ButtonWidget sendButton", "public ButtonWidget sendButton"),
        (r"private ButtonWidget stopButton", "public ButtonWidget stopButton"),
        (r"private ButtonWidget referenceButton", "public ButtonWidget referenceButton"),
        (r"private String referenceImageUrl", "public String referenceImageUrl"),
        (r"private byte\[\] referenceImageFile", "public byte[] referenceImageFile"),
        (r"private boolean readingReferenceImage", "public boolean readingReferenceImage"),
        (r"private PromptMode promptMode", "public PromptMode promptMode"),
    ]
    for a, b in field_reps:
        text = re.sub(rf"^(\s*){a}", rf"\1{b}", text, count=1, flags=re.M)
    return text


def delegate_stub(name: str, body: str, class_name: str) -> str:
    # Keep original signature lines up to '{', replace body with one call.
    brace = body.find("{")
    if brace < 0:
        raise SystemExit("delegate no brace " + name)
    header = body[:brace].rstrip()  # may include @Override lines
    # params from last (...) in header
    pm = re.search(r"\(([^)]*)\)\s*$", header)
    if not pm:
        raise SystemExit("delegate params " + name)
    params = pm.group(1).strip()
    args = []
    if params:
        for part in params.split(","):
            part = part.strip()
            if part:
                args.append(part.split()[-1])
    is_static = re.search(r"\bstatic\b", header) is not None
    call_args = ", ".join(args)
    ret_m = re.search(
        r"(?:public|private|protected)\s+(?:static\s+)?(?:@\w+(?:\([^)]*\))?\s+)*(.+?)\s+"
        + re.escape(name)
        + r"\s*\(",
        header,
    )
    ret = ret_m.group(1).strip() if ret_m else "void"

    fq = f"com.formacraft.client.ui.panel.chat.{class_name}.{name}"
    if is_static and name in (
        "isReferenceImageUrl",
        "enableScissor",
        "disableScissor",
        "chatLineHeight",
        "mightReferencePlayerComponents",
        "formatThinkingElapsed",
        "buildHealthUrlOrNull",
    ):
        call = f"{fq}({call_args})"
    else:
        call = f"{fq}(this, {call_args})" if call_args else f"{fq}(this)"
    if ret == "void":
        return f"{header} {{ {call}; }}"
    return f"{header} {{ return {call}; }}"


def main():
    if len(sys.argv) < 3:
        print("usage: ClassName method [method...]")
        sys.exit(2)
    class_name = sys.argv[1]
    methods = sys.argv[2:]
    text = PANEL.read_text(encoding="utf-8")
    text = ensure_panel_host_api(text)

    sibling = set(methods)
    extracted = []
    spans = []
    for name in methods:
        s, e, body = find_method(text, name)
        extracted.append(to_helper_method(body, name, sibling - {name}))
        spans.append((s, e, name, body))

    # write/merge helper file
    helper_path = CHAT / f"{class_name}.java"
    if helper_path.exists():
        existing = helper_path.read_text(encoding="utf-8")
        # insert before final }
        idx = existing.rfind("}")
        helper = existing[:idx] + "\n".join(extracted) + "\n" + existing[idx:]
    else:
        helper = (
            f"package com.formacraft.client.ui.panel.chat;\n\n"
            f"import com.formacraft.client.ui.panel.ChatPanel;\n"
            f"import com.formacraft.client.ui.UiTheme;\n"
            f"import com.formacraft.client.ui.panel.chat.ChatMessage;\n"
            f"import com.formacraft.client.ui.text.SelectableTextBlock;\n"
            f"import com.formacraft.common.model.build.BuildingSpec;\n"
            f"import net.minecraft.client.gui.DrawContext;\n"
            f"import net.minecraft.client.gui.Click;\n"
            f"import net.minecraft.client.gui.tooltip.Tooltip;\n"
            f"import net.minecraft.client.gui.widget.ButtonWidget;\n"
            f"import net.minecraft.client.input.MouseInput;\n"
            f"import net.minecraft.text.Text;\n"
            f"import net.minecraft.util.math.BlockPos;\n"
            f"import net.minecraft.util.math.Direction;\n"
            f"import com.formacraft.client.ui.widget.HudClickSupport;\n"
            f"import com.formacraft.client.ui.widget.MultilineTextInput;\n"
            f"import com.formacraft.client.ai.context.SelectionContext;\n"
            f"import com.formacraft.client.ai.prompt.PromptMode;\n"
            f"import com.formacraft.client.ai.prompt.PromptModeResolver;\n"
            f"import com.formacraft.client.component.ClientComponentCatalogState;\n"
            f"import com.formacraft.client.network.FormaCraftClientNetworking;\n"
            f"import com.formacraft.client.preview.BuildingPreviewState;\n"
            f"import com.formacraft.client.preview.OutlinePreviewState;\n"
            f"import com.formacraft.client.preview.PromptModeState;\n"
            f"import com.formacraft.common.model.request.FormaRequest;\n"
            f"import com.formacraft.config.SettingsConfig;\n"
            f"import org.jetbrains.annotations.NotNull;\n"
            f"import org.lwjgl.glfw.GLFW;\n"
            f"import java.util.*;\n"
            f"import java.util.concurrent.*;\n"
            f"import java.util.regex.*;\n"
            f"import java.net.*;\n"
            f"import java.net.http.*;\n"
            f"import java.time.Duration;\n"
            f"\npublic final class {class_name} {{\n"
            f"    private {class_name}() {{}}\n"
            + "\n".join(extracted)
            + "}\n"
        )
    helper_path.write_text(helper, encoding="utf-8")

    for s, e, name, body in sorted(spans, key=lambda x: -x[0]):
        text = text[:s] + delegate_stub(name, body, class_name) + text[e:]

    PANEL.write_text(text, encoding="utf-8")
    print("ok", class_name, methods, "panel", text.count("\n") + 1, "helper", helper.count("\n") + 1)


if __name__ == "__main__":
    main()
