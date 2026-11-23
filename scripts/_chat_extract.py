# -*- coding: utf-8 -*-
"""Extract ChatPanel methods into chat/* helpers. Parameter name: host (avoids int p shadow)."""
from __future__ import annotations
from pathlib import Path
import re

ROOT = Path("src/main/java/com/formacraft/client/ui/panel")
PANEL = ROOT / "ChatPanel.java"
CHAT = ROOT / "chat"

FIELDS = [
    "messages", "sessionId", "inputBox", "inputHistory", "historyIndex", "historyDraft",
    "scrollOffset", "scrollRemainder", "chatMaxScrollOffset", "currentPrinter",
    "pendingRequestToken", "pendingThinkingIndex", "pendingStartMs", "pendingPhase",
    "selectable", "selectableMsgIndex", "selectingText", "renderedMessages",
    "sendButton", "stopButton", "referenceButton", "referenceImageUrl", "referenceImageFile",
    "readingReferenceImage", "promptMode", "client", "panelX", "panelY", "panelWidth", "panelHeight",
]
# BasePanel geometry often via getters
METHODS_ON_HOST = [
    "ensureLayout", "getContentY", "getContentHeight", "drawContentBackground",
    "blurInput", "sendCurrentMessage", "stopGenerating", "addAIError", "addAIMessage",
    "addAIStatus", "initWidgets", "toggleReferenceImage", "chooseReferenceImageFile",
    "hasReferenceImage", "computeChatLayout", "computeInputBounds", "drawMessages",
    "drawInputArea", "drawSpecSummary", "tickTextSelectionDrag", "tickInputSelectionDrag",
    "tryStartSelection", "getInputHeight", "getScaledMouseX", "getScaledMouseY",
    "chatLineHeight", "enableScissor", "disableScissor", "tryHandlePreviewAdjust",
    "notifyComponentCatalogStatus", "getStrings", "isHealthy", "buildHealthUrlOrNull",
    "formatThinkingElapsed", "mightReferencePlayerComponents", "setPendingPhase",
    "renderPendingElapsed", "startElapsedTicker", "superMouseClicked",
    "isMouseOver", "drawTooltipCompat",
]
CONSTANTS = [
    "CHAT_PADDING_H", "CHAT_PADDING_V", "CHAT_BUBBLE_INSET", "CHAT_BUBBLE_TEXT_PAD",
    "CHAT_BOTTOM_INSET", "CHAT_MESSAGE_GAP", "CHAT_INPUT_MIN_VISIBLE_LINES",
    "CHAT_INPUT_MAX_VISIBLE_LINES", "CHAT_INPUT_BOX_VERTICAL_PAD", "CHAT_INPUT_MAX_CHARS",
    "CHAT_INPUT_MAX_LINES", "CHAT_DIVIDER_HEIGHT", "SEND_BUTTON_SIZE", "STOP_BUTTON_SIZE",
    "CHAT_SPEC_SUMMARY_HEIGHT", "SOFT_TIMEOUT_SEC", "HARD_TIMEOUT_SEC", "LOG",
]

GROUPS = {
    "ChatMessageList": {
        "file": "ChatMessageList.java",
        "methods": ["drawMessages", "tickTextSelectionDrag", "tickInputSelectionDrag", "drawSpecSummary"],
        "imports": """
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.panel.chat.ChatMessage;
import com.formacraft.client.ui.text.SelectableTextBlock;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.util.List;
""",
    },
    "ChatInputSupport": {
        "file": "ChatInputSupport.java",
        "methods": ["initWidgets", "drawInputArea", "getInputHeight", "toggleReferenceImage",
                    "hasReferenceImage", "chooseReferenceImageFile", "isReferenceImageUrl"],
        "imports": """
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.concurrent.CompletableFuture;
""",
    },
    "ChatLayoutSupport": {
        "file": "ChatLayoutSupport.java",
        "methods": ["computeChatLayout", "computeInputBounds", "enableScissor", "disableScissor",
                    "chatLineHeight", "getScaledMouseX", "getScaledMouseY"],
        "imports": """
import com.formacraft.client.ai.context.SelectionContext;
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.UiTheme;
import net.minecraft.client.gui.DrawContext;
""",
    },
    "ChatInteraction": {
        "file": "ChatInteraction.java",
        "methods": ["mouseClicked", "mouseDragged", "mouseReleased", "tryStartSelection",
                    "mouseScrolled", "keyPressedFull", "charTyped", "drawCustomTooltip"],
        "imports": """
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
""",
    },
    "ChatSendSupport": {
        "file": "ChatSendSupport.java",
        "methods": ["sendCurrentMessage", "getStrings", "notifyComponentCatalogStatus",
                    "mightReferencePlayerComponents", "formatThinkingElapsed",
                    "tryHandlePreviewAdjust", "buildHealthUrlOrNull", "isHealthy", "stopGenerating"],
        "imports": """
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.panel.chat.AIStreamPrinter;
import com.formacraft.client.ui.panel.chat.ChatMessage;
import com.formacraft.client.ui.panel.chat.ChatBuildRequestFactory;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.preview.BuildingPreviewState;
import com.formacraft.client.preview.OutlinePreviewState;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.client.ai.prompt.PromptMode;
import com.formacraft.client.ai.prompt.PromptModeResolver;
import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.config.SettingsConfig;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
""",
    },
}


def extract_method(text: str, name: str) -> tuple[int, int, str]:
    # Prefer exact private/public method named name
    pattern = rf"(?:/\*\*[\s\S]*?\*/\s*)?(?:@Override\s+)?(?:public|private|protected)\s+(?:static\s+)?(?:@\w+\s+)?[\w.<>,?\[\]\s]+\s+{name}\s*\([^;]*\)\s*\{{"
    matches = list(re.finditer(pattern, text))
    if not matches:
        raise SystemExit(f"method not found: {name}")
    # For overloaded keyPressed, take the 3-arg one later via alias
    m = matches[-1] if name == "keyPressed" and len(matches) > 1 else matches[0]
    if name == "keyPressedFull":
        raise SystemExit("use keyPressed")
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


def strip_signature(body: str) -> tuple[str, str]:
    """Return (return_type_and_name_sig_without_brace, inner)."""
    m = re.match(r"(?:/\*\*[\s\S]*?\*/\s*)?(?:@Override\s+)?((?:public|private|protected)\s+(?:static\s+)?(?:@\w+\s+)?[\w.<>,?\[\]\s]+\s+\w+\s*\([^;]*\))\s*\{", body)
    if not m:
        raise SystemExit("bad sig\n" + body[:200])
    sig = m.group(1)
    inner = body[m.end() : -1]
    return sig, inner


def qualify(inner: str, local_methods: set[str]) -> str:
    out = inner
    # constants -> ChatPanel.CONST
    for c in sorted(CONSTANTS, key=len, reverse=True):
        out = re.sub(rf"(?<![\w.]){c}(?!\w)", f"ChatPanel.{c}", out)
    # fields -> host.field
    for f in sorted(FIELDS, key=len, reverse=True):
        out = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", out)
    # method calls
    for meth in sorted(set(METHODS_ON_HOST) | local_methods, key=len, reverse=True):
        if meth in local_methods:
            out = re.sub(rf"(?<![\w.]){meth}\(", f"{meth}(host, ", out)
        else:
            out = re.sub(rf"(?<![\w.]){meth}\(", f"host.{meth}(", out)
    out = out.replace("host.host.", "host.")
    out = re.sub(r"(\w+)\(host, host,", r"\1(host,", out)
    # Nested types
    out = out.replace("host.RenderedMessage", "ChatPanel.RenderedMessage")
    out = out.replace("host.ChatLayout", "ChatPanel.ChatLayout")
    out = out.replace("host.InputBounds", "ChatPanel.InputBounds")
    out = out.replace("host.PreviewAdjustCommand", "ChatPanel.PreviewAdjustCommand")
    out = re.sub(r"(?<![\w.])RenderedMessage(?!\w)", "ChatPanel.RenderedMessage", out)
    out = re.sub(r"(?<![\w.])ChatLayout(?!\w)", "ChatPanel.ChatLayout", out)
    out = re.sub(r"(?<![\w.])InputBounds(?!\w)", "ChatPanel.InputBounds", out)
    out = re.sub(r"(?<![\w.])PreviewAdjustCommand(?!\w)", "ChatPanel.PreviewAdjustCommand", out)
    # Fix ChatPanel.ChatPanel.
    out = out.replace("ChatPanel.ChatPanel.", "ChatPanel.")
    # super. calls stay invalid in static — Interaction uses host.superMouseClicked
    out = out.replace("host.super.", "SUPER.")  # mark
    return out


def make_static_sig(orig_sig: str, name: str) -> str:
    # change modifiers to public static, inject ChatPanel host as first arg (unless static already with no instance)
    sig = orig_sig
    sig = re.sub(r"^(?:public|private|protected)\s+", "", sig)
    is_static = sig.startswith("static ")
    if is_static:
        sig = "public " + sig
        # static methods may not need host — still pass if they use fields; isReferenceImageUrl doesn't
        if name in ("isReferenceImageUrl", "enableScissor", "disableScissor", "chatLineHeight",
                    "mightReferencePlayerComponents", "formatThinkingElapsed", "buildHealthUrlOrNull"):
            return sig  # keep original params
        # else add host first after (
        sig = re.sub(r"\(", "(ChatPanel host, ", sig, count=1)
        return sig
    sig = re.sub(r"^static\s+", "", sig)
    # insert host
    sig = re.sub(r"\(", "(ChatPanel host, ", sig, count=1)
    # rename keyPressedFull
    sig = f"public static {sig}"
    # fix double public static
    sig = sig.replace("public static public ", "public static ")
    sig = sig.replace("public static private ", "public static ")
    sig = sig.replace("public static protected ", "public static ")
    return sig


def main():
    text = PANEL.read_text(encoding="utf-8")

    # Publicize fields/constants/nested types and LOG
    text = text.replace("private static final FcaLog LOG", "public static final FcaLog LOG")
    for c in CONSTANTS:
        text = text.replace(f"private static final long {c}", f"public static final long {c}")
        text = text.replace(f"private static final int {c}", f"public static final int {c}")
    # fields
    text = re.sub(r"^(\s*)private final List<ChatMessage> messages", r"\1public final List<ChatMessage> messages", text, flags=re.M)
    text = re.sub(r"^(\s*)private String sessionId", r"\1public String sessionId", text, flags=re.M)
    text = re.sub(r"^(\s*)private MultilineTextInput inputBox", r"\1public MultilineTextInput inputBox", text, flags=re.M)
    text = re.sub(r"^(\s*)private final List<String> inputHistory", r"\1public final List<String> inputHistory", text, flags=re.M)
    text = re.sub(r"^(\s*)private int historyIndex", r"\1public int historyIndex", text, flags=re.M)
    text = re.sub(r"^(\s*)private String historyDraft", r"\1public String historyDraft", text, flags=re.M)
    text = re.sub(r"^(\s*)private int scrollOffset", r"\1public int scrollOffset", text, flags=re.M)
    text = re.sub(r"^(\s*)private double scrollRemainder", r"\1public double scrollRemainder", text, flags=re.M)
    text = re.sub(r"^(\s*)private int chatMaxScrollOffset", r"\1public int chatMaxScrollOffset", text, flags=re.M)
    text = re.sub(r"^(\s*)private AIStreamPrinter currentPrinter", r"\1public AIStreamPrinter currentPrinter", text, flags=re.M)
    text = re.sub(r"^(\s*)private long pendingRequestToken", r"\1public long pendingRequestToken", text, flags=re.M)
    text = re.sub(r"^(\s*)private int pendingThinkingIndex", r"\1public int pendingThinkingIndex", text, flags=re.M)
    text = re.sub(r"^(\s*)private long pendingStartMs", r"\1public long pendingStartMs", text, flags=re.M)
    text = re.sub(r"^(\s*)private String pendingPhase", r"\1public String pendingPhase", text, flags=re.M)
    text = re.sub(r"^(\s*)private final SelectableTextBlock selectable", r"\1public final SelectableTextBlock selectable", text, flags=re.M)
    text = re.sub(r"^(\s*)private int selectableMsgIndex", r"\1public int selectableMsgIndex", text, flags=re.M)
    text = re.sub(r"^(\s*)private boolean selectingText", r"\1public boolean selectingText", text, flags=re.M)
    text = re.sub(r"^(\s*)private final List<RenderedMessage> renderedMessages", r"\1public final List<RenderedMessage> renderedMessages", text, flags=re.M)
    text = re.sub(r"^(\s*)private ButtonWidget sendButton", r"\1public ButtonWidget sendButton", text, flags=re.M)
    text = re.sub(r"^(\s*)private ButtonWidget stopButton", r"\1public ButtonWidget stopButton", text, flags=re.M)
    text = re.sub(r"^(\s*)private ButtonWidget referenceButton", r"\1public ButtonWidget referenceButton", text, flags=re.M)
    text = re.sub(r"^(\s*)private String referenceImageUrl", r"\1public String referenceImageUrl", text, flags=re.M)
    text = re.sub(r"^(\s*)private byte\[\] referenceImageFile", r"\1public byte[] referenceImageFile", text, flags=re.M)
    text = re.sub(r"^(\s*)private boolean readingReferenceImage", r"\1public boolean readingReferenceImage", text, flags=re.M)
    text = re.sub(r"^(\s*)private PromptMode promptMode", r"\1public PromptMode promptMode", text, flags=re.M)
    text = text.replace("private static final class RenderedMessage", "public static final class RenderedMessage")
    text = text.replace("private record ChatLayout", "public record ChatLayout")
    text = text.replace("private record InputBounds", "public record InputBounds")
    text = text.replace("private record PreviewAdjustCommand", "public record PreviewAdjustCommand")
    # client accessor - BasePanel has protected client; ChatPanel shadows private final
    text = text.replace("private final MinecraftClient client = MinecraftClient.getInstance();",
                        "public final MinecraftClient client = MinecraftClient.getInstance();")

    # Rename 3-arg keyPressed body extraction via temporary alias in source? We'll extract both carefully.
    # Add bridges
    if "superMouseClicked" not in text:
        text = text.replace(
            "public class ChatPanel extends BasePanel {",
            "public class ChatPanel extends BasePanel {\n"
            "    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }\n"
            "    public void drawContentBackgroundPublic(DrawContext ctx) { drawContentBackground(ctx); }\n",
        )

    # Extract groups — process Interaction keyPressed specially
    all_spans: list[tuple[int, int, str, str]] = []  # start,end,group,name
    bodies: dict[tuple[str, str], str] = {}

    for gname, meta in GROUPS.items():
        local = set(meta["methods"])
        for name in meta["methods"]:
            extract_name = "keyPressed" if name == "keyPressedFull" else name
            if name == "keyPressedFull":
                # find 3-arg
                pattern = r"(?:/\*\*[\s\S]*?\*/\s*)?@Override\s+public void keyPressed\(int keyCode, int scanCode, int modifiers\)\s*\{"
                m = re.search(pattern, text)
                if not m:
                    raise SystemExit("keyPressed 3-arg missing")
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
                            body = text[start : i + 1]
                            bodies[(gname, name)] = body
                            all_spans.append((start, i + 1, gname, name))
                            break
                    i += 1
                continue
            if name == "drawCustomTooltip":
                # protected override
                pattern = r"(?:/\*\*[\s\S]*?\*/\s*)?@Override\s+protected boolean drawCustomTooltip\(DrawContext ctx, double mouseX, double mouseY\)\s*\{"
                m = re.search(pattern, text)
                if not m:
                    raise SystemExit("drawCustomTooltip missing")
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
                            bodies[(gname, name)] = text[start : i + 1]
                            all_spans.append((start, i + 1, gname, name))
                            break
                    i += 1
                continue
            if name == "mouseClicked":
                pattern = r"@Override\s+public boolean mouseClicked\(double mouseX, double mouseY, int button\)\s*\{"
                m = re.search(pattern, text)
                start = m.start(); brace = text.find("{", m.end()-1); depth=0; i=brace
                while i < len(text):
                    depth += (text[i]=="{") - (text[i]=="}")
                    i += 1
                    if depth == 0:
                        bodies[(gname, name)] = text[start:i]
                        all_spans.append((start, i, gname, name))
                        break
                continue
            for special in ("mouseDragged", "mouseReleased", "mouseScrolled", "charTyped"):
                if name == special:
                    pattern = rf"@Override\s+public \w+ {name}\([^)]*\)\s*\{{"
                    m = re.search(pattern, text)
                    start = m.start(); brace = text.find("{", m.end()-1); depth=0; i=brace
                    while i < len(text):
                        depth += (text[i]=="{") - (text[i]=="}")
                        i += 1
                        if depth == 0:
                            bodies[(gname, name)] = text[start:i]
                            all_spans.append((start, i, gname, name))
                            break
                    break
            else:
                if name in ("mouseClicked", "mouseDragged", "mouseReleased", "mouseScrolled", "charTyped", "keyPressedFull", "drawCustomTooltip"):
                    continue
                s, e, body = extract_method(text, extract_name)
                # avoid extracting 1-arg keyPressed
                if name == "sendCurrentMessage" or True:
                    bodies[(gname, name)] = body
                    all_spans.append((s, e, gname, name))

    # Deduplicate spans by start
    seen = set()
    uniq = []
    for sp in sorted(all_spans, key=lambda x: x[0]):
        if sp[0] in seen:
            continue
        seen.add(sp[0])
        uniq.append(sp)
    all_spans = uniq

    # Build helper files
    for gname, meta in GROUPS.items():
        local = set(meta["methods"])
        parts = [f"package com.formacraft.client.ui.panel.chat;\n{meta['imports']}\npublic final class {gname} {{\n    private {gname}() {{}}\n"]
        for name in meta["methods"]:
            body = bodies[(gname, name)]
            sig, inner = strip_signature(body)
            if name == "keyPressedFull":
                # force name
                sig = re.sub(r"\bkeyPressed\b", "keyPressed", sig)
            static_sig = make_static_sig(sig, name if name != "keyPressedFull" else "keyPressed")
            if name == "keyPressedFull":
                static_sig = re.sub(r"\bkeyPressed\b", "keyPressed", static_sig)
                # ensure name keyPressed in helper
                static_sig = static_sig.replace("keyPressedFull", "keyPressed")
            qualified = qualify(inner, local - {name} | (local if name != "keyPressedFull" else local))
            # For keyPressedFull local set uses keyPressedFull name — fix mutual calls
            if name == "keyPressedFull":
                qualified = qualify(inner, {"tryStartSelection"})  # mostly host methods
            # drawContentBackground -> public
            qualified = qualified.replace("host.drawContentBackground(", "host.drawContentBackgroundPublic(")
            qualified = qualified.replace("SUPER.mouseClicked(", "host.superMouseClicked(")
            # Fix zero-arg static helpers incorrectly getting host
            for z in ("chatLineHeight", "enableScissor", "disableScissor", "isReferenceImageUrl",
                      "mightReferencePlayerComponents", "formatThinkingElapsed", "buildHealthUrlOrNull"):
                qualified = re.sub(rf"{z}\(host,\s*", f"{z}(", qualified)
            # parse helpers of PreviewAdjust stay in panel for now — tryHandlePreviewAdjust may call parse
            parts.append(f"    {static_sig} {{{qualified}\n    }}\n")
        parts.append("}\n")
        (CHAT / meta["file"]).write_text("\n".join(parts), encoding="utf-8")
        print("wrote", meta["file"])

    # Replace extracted methods in panel with delegates
    DELEGATES = {
        "drawMessages": "private void drawMessages(DrawContext ctx, int innerX, int chatTop, int innerW, int chatBottom, int lineHeight) { com.formacraft.client.ui.panel.chat.ChatMessageList.drawMessages(this, ctx, innerX, chatTop, innerW, chatBottom, lineHeight); }",
        "tickTextSelectionDrag": "private void tickTextSelectionDrag() { com.formacraft.client.ui.panel.chat.ChatMessageList.tickTextSelectionDrag(this); }",
        "tickInputSelectionDrag": "private void tickInputSelectionDrag() { com.formacraft.client.ui.panel.chat.ChatMessageList.tickInputSelectionDrag(this); }",
        "drawSpecSummary": "private void drawSpecSummary(DrawContext ctx, BuildingSpec spec, int x, int y, int w, int lineHeight) { com.formacraft.client.ui.panel.chat.ChatMessageList.drawSpecSummary(this, ctx, spec, x, y, w, lineHeight); }",
        "initWidgets": "private void initWidgets() { com.formacraft.client.ui.panel.chat.ChatInputSupport.initWidgets(this); }",
        "drawInputArea": "private void drawInputArea(DrawContext ctx, ChatLayout layout) { com.formacraft.client.ui.panel.chat.ChatInputSupport.drawInputArea(this, ctx, layout); }",
        "getInputHeight": "private int getInputHeight(int selectionHintHeight) { return com.formacraft.client.ui.panel.chat.ChatInputSupport.getInputHeight(this, selectionHintHeight); }",
        "toggleReferenceImage": "private void toggleReferenceImage() { com.formacraft.client.ui.panel.chat.ChatInputSupport.toggleReferenceImage(this); }",
        "hasReferenceImage": "private boolean hasReferenceImage() { return com.formacraft.client.ui.panel.chat.ChatInputSupport.hasReferenceImage(this); }",
        "chooseReferenceImageFile": "private void chooseReferenceImageFile() { com.formacraft.client.ui.panel.chat.ChatInputSupport.chooseReferenceImageFile(this); }",
        "isReferenceImageUrl": "private static boolean isReferenceImageUrl(String value) { return com.formacraft.client.ui.panel.chat.ChatInputSupport.isReferenceImageUrl(value); }",
        "computeChatLayout": "private ChatLayout computeChatLayout() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.computeChatLayout(this); }",
        "computeInputBounds": "private InputBounds computeInputBounds(ChatLayout layout) { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.computeInputBounds(this, layout); }",
        "enableScissor": "private static void enableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) { com.formacraft.client.ui.panel.chat.ChatLayoutSupport.enableScissor(ctx, x0, y0, x1, y1); }",
        "disableScissor": "private static void disableScissor(DrawContext ctx, int x0, int y0, int x1, int y1) { com.formacraft.client.ui.panel.chat.ChatLayoutSupport.disableScissor(ctx, x0, y0, x1, y1); }",
        "chatLineHeight": "private static int chatLineHeight() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.chatLineHeight(); }",
        "getScaledMouseX": "private double getScaledMouseX() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.getScaledMouseX(this); }",
        "getScaledMouseY": "private double getScaledMouseY() { return com.formacraft.client.ui.panel.chat.ChatLayoutSupport.getScaledMouseY(this); }",
        "mouseClicked": "@Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.chat.ChatInteraction.mouseClicked(this, mouseX, mouseY, button); }",
        "mouseDragged": "@Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return com.formacraft.client.ui.panel.chat.ChatInteraction.mouseDragged(this, mouseX, mouseY, button, deltaX, deltaY); }",
        "mouseReleased": "@Override public boolean mouseReleased(double mouseX, double mouseY, int button) { return com.formacraft.client.ui.panel.chat.ChatInteraction.mouseReleased(this, mouseX, mouseY, button); }",
        "tryStartSelection": "private boolean tryStartSelection(double mouseX, double mouseY) { return com.formacraft.client.ui.panel.chat.ChatInteraction.tryStartSelection(this, mouseX, mouseY); }",
        "mouseScrolled": "@Override public void mouseScrolled(double mouseX, double mouseY, double amount) { com.formacraft.client.ui.panel.chat.ChatInteraction.mouseScrolled(this, mouseX, mouseY, amount); }",
        "keyPressedFull": "@Override public void keyPressed(int keyCode, int scanCode, int modifiers) { com.formacraft.client.ui.panel.chat.ChatInteraction.keyPressed(this, keyCode, scanCode, modifiers); }",
        "charTyped": "@Override public void charTyped(char chr) { com.formacraft.client.ui.panel.chat.ChatInteraction.charTyped(this, chr); }",
        "drawCustomTooltip": "@Override protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return com.formacraft.client.ui.panel.chat.ChatInteraction.drawCustomTooltip(this, ctx, mouseX, mouseY); }",
        "sendCurrentMessage": "private void sendCurrentMessage() { com.formacraft.client.ui.panel.chat.ChatSendSupport.sendCurrentMessage(this); }",
        "getStrings": "private @NotNull List<String> getStrings() { return com.formacraft.client.ui.panel.chat.ChatSendSupport.getStrings(this); }",
        "notifyComponentCatalogStatus": "private void notifyComponentCatalogStatus(String userText) { com.formacraft.client.ui.panel.chat.ChatSendSupport.notifyComponentCatalogStatus(this, userText); }",
        "mightReferencePlayerComponents": "private static boolean mightReferencePlayerComponents(String text) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.mightReferencePlayerComponents(text); }",
        "formatThinkingElapsed": "private static String formatThinkingElapsed(String phase, long seconds) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.formatThinkingElapsed(phase, seconds); }",
        "tryHandlePreviewAdjust": "private boolean tryHandlePreviewAdjust(String text) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.tryHandlePreviewAdjust(this, text); }",
        "buildHealthUrlOrNull": "private static String buildHealthUrlOrNull(String endpoint) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.buildHealthUrlOrNull(endpoint); }",
        "isHealthy": "private boolean isHealthy(String healthUrl) { return com.formacraft.client.ui.panel.chat.ChatSendSupport.isHealthy(this, healthUrl); }",
        "stopGenerating": "private void stopGenerating() { com.formacraft.client.ui.panel.chat.ChatSendSupport.stopGenerating(this); }",
    }

    # Replace from end to start
    for start, end, gname, name in sorted(all_spans, key=lambda x: -x[0]):
        text = text[:start] + DELEGATES[name] + text[end:]

    text = re.sub(r"\n{3,}", "\n\n", text)
    PANEL.write_text(text, encoding="utf-8")
    print("ChatPanel lines", text.count("\n") + 1)


if __name__ == "__main__":
    main()
