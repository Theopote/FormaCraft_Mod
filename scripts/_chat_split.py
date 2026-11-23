"""Extract ChatPanel into chat/ collaborators (UTF-8 safe)."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/ChatPanel.java")
CHAT = Path("src/main/java/com/formacraft/client/ui/panel/chat")
lines = PANEL.read_text(encoding="utf-8").splitlines(keepends=True)

FIELDS = [
    "LOG",
    "client",
    "messages",
    "sessionId",
    "inputBox",
    "inputHistory",
    "historyIndex",
    "historyDraft",
    "scrollOffset",
    "scrollRemainder",
    "chatMaxScrollOffset",
    "currentPrinter",
    "pendingRequestToken",
    "pendingThinkingIndex",
    "pendingStartMs",
    "pendingPhase",
    "SOFT_TIMEOUT_SEC",
    "HARD_TIMEOUT_SEC",
    "healthHttp",
    "selectable",
    "selectableMsgIndex",
    "selectingText",
    "renderedMessages",
    "sendButton",
    "stopButton",
    "referenceButton",
    "referenceImageUrl",
    "referenceImageFile",
    "readingReferenceImage",
    "promptMode",
]

CONSTANTS = [
    "CHAT_PADDING_H",
    "CHAT_PADDING_V",
    "CHAT_BUBBLE_INSET",
    "CHAT_BUBBLE_TEXT_PAD",
    "CHAT_BOTTOM_INSET",
    "CHAT_MESSAGE_GAP",
    "CHAT_INPUT_MIN_VISIBLE_LINES",
    "CHAT_INPUT_MAX_VISIBLE_LINES",
    "CHAT_INPUT_BOX_VERTICAL_PAD",
    "CHAT_INPUT_MAX_CHARS",
    "CHAT_INPUT_MAX_LINES",
    "CHAT_DIVIDER_HEIGHT",
    "SEND_BUTTON_SIZE",
    "STOP_BUTTON_SIZE",
    "CHAT_SPEC_SUMMARY_HEIGHT",
]


def find_line(substr: str) -> int:
    for i, l in enumerate(lines):
        if substr in l:
            return i + 1
    raise KeyError(substr)


def method_end(start_line: int) -> int:
    i = start_line - 1
    while i < len(lines) and "{" not in lines[i]:
        i += 1
    depth = 0
    started = False
    while i < len(lines):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
                if started and depth == 0:
                    return i + 1
        i += 1
    raise RuntimeError(start_line)


def grab(substr: str):
    a = find_line(substr)
    # include preceding @Override and javadoc
    while a > 1:
        prev = lines[a - 2].strip()
        if prev == "@Override" or prev.startswith("/**") or prev.startswith("*") or prev.startswith("//"):
            a -= 1
            continue
        if prev == "" and a > 2:
            # keep one blank if previous is javadoc end
            look = lines[a - 3].strip() if a > 2 else ""
            if look.startswith("*") or look.startswith("/**") or look == "*/":
                a -= 1
                continue
        break
    # if we landed on blank before method, skip blank
    while a < len(lines) and lines[a - 1].strip() == "":
        a += 1
    # find actual method signature line for end calc
    sig = a
    while sig <= len(lines) and not re.search(
        r"\b(void|boolean|int|double|String|List|ChatLayout|InputBounds|PreviewAdjustCommand)\b.+\(",
        lines[sig - 1],
    ) and "record " not in lines[sig - 1] and "class " not in lines[sig - 1]:
        # also match annotated methods
        if re.search(r"^\s*(private|public|protected).+\(", lines[sig - 1]):
            break
        sig += 1
        if sig - a > 30:
            sig = a
            break
    for i in range(a, min(a + 40, len(lines) + 1)):
        if re.search(r"^\s*(private|public|protected).+\(", lines[i - 1]) or lines[i - 1].lstrip().startswith("private record ") or lines[i - 1].lstrip().startswith("private static class "):
            sig = i
            break
    b = method_end(sig)
    return a, b, "".join(lines[a - 1 : b])


def rewrite_body(body: str) -> str:
    body = re.sub(r"(?m)^    @Override\n", "", body)
    # strip leading javadoc — keep it for readability
    # convert method visibility to public static with host
    body = re.sub(
        r"(?m)^    private static void ",
        "    public static void ",
        body,
    )
    body = re.sub(
        r"(?m)^    private static boolean ",
        "    public static boolean ",
        body,
    )
    body = re.sub(
        r"(?m)^    private static int ",
        "    public static int ",
        body,
    )
    body = re.sub(
        r"(?m)^    private static @NotNull String ",
        "    public static @NotNull String ",
        body,
    )
    body = re.sub(
        r"(?m)^    private static String ",
        "    public static String ",
        body,
    )
    body = re.sub(
        r"(?m)^    private static double ",
        "    public static double ",
        body,
    )

    # instance methods -> static with ChatPanel p
    def add_host(m: re.Match) -> str:
        ret = m.group(1)
        name = m.group(2)
        rest = m.group(3)
        if rest.startswith(")"):
            return f"    public static {ret}{name}(ChatPanel p{rest}"
        return f"    public static {ret}{name}(ChatPanel p, {rest}"

    body = re.sub(
        r"(?m)^    private ((?:@NotNull\s+)?(?:void|boolean|int|double|String|List<String>|ChatLayout|InputBounds)\s+)(\w+)\((.*)$",
        add_host,
        body,
    )
    body = re.sub(
        r"(?m)^    public ((?:void|boolean|int|double)\s+)(\w+)\((.*)$",
        add_host,
        body,
    )
    body = re.sub(
        r"(?m)^    protected ((?:void|boolean)\s+)(\w+)\((.*)$",
        add_host,
        body,
    )

    body = body.replace("this.", "p.")
    for f in sorted(FIELDS + CONSTANTS, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){re.escape(f)}(?!\w)", f"p.{f}", body)
    body = body.replace("p.p.", "p.")

    # geometry / protected BasePanel
    body = body.replace("p.panelX", "p.getPanelX()")
    body = body.replace("p.panelY", "p.getPanelY()")
    body = body.replace("p.panelWidth", "p.getPanelWidth()")
    body = body.replace("p.panelHeight", "p.getPanelHeight()")
    body = re.sub(r"(?<![\w.])getContentY\(", "p.getContentY(", body)
    body = re.sub(r"(?<![\w.])getContentHeight\(", "p.getContentHeight(", body)
    body = re.sub(r"(?<![\w.])drawContentBackground\(", "p.drawContentBackgroundPublic(", body)
    body = re.sub(r"(?<![\w.])drawTooltipCompat\(", "p.drawTooltipCompatPublic(", body)
    body = re.sub(r"(?<![\w.])ensureLayout\(", "p.ensureLayout(", body)
    body = re.sub(r"(?<![\w.])isMouseOver\(", "p.isMouseOver(", body)
    body = re.sub(r"(?<![\w.])super\.mouseClicked\(", "p.superMouseClicked(", body)
    body = re.sub(r"(?<![\w.])super\.mouseReleased\(", "p.superMouseReleased(", body)

    # unqualified local calls that stay as host methods / other extractors — fixed later
    for meth in [
        "initWidgets",
        "computeChatLayout",
        "computeInputBounds",
        "getInputHeight",
        "chatLineHeight",
        "enableScissor",
        "disableScissor",
        "drawMessages",
        "drawSpecSummary",
        "tickTextSelectionDrag",
        "tickInputSelectionDrag",
        "drawInputArea",
        "getScaledMouseX",
        "getScaledMouseY",
        "hasReferenceImage",
        "toggleReferenceImage",
        "chooseReferenceImageFile",
        "isReferenceImageUrl",
        "tryStartSelection",
        "sendCurrentMessage",
        "stopGenerating",
        "blurInput",
        "tryHandlePreviewAdjust",
        "getStrings",
        "notifyComponentCatalogStatus",
        "mightReferencePlayerComponents",
        "getString",
        "formatThinkingElapsed",
        "buildHealthUrlOrNull",
        "isHealthy",
        "setPendingPhase",
        "renderPendingElapsed",
        "startElapsedTicker",
        "addAIError",
        "addAIMessage",
        "addAIStatus",
        "resetSession",
    ]:
        body = re.sub(rf"(?<![\w.]){meth}\(", f"__CALL_{meth}__(", body)

    # undo bad prefixes on types / keywords / static imports
    for bad in [
        "Math", "Text", "List", "ArrayList", "System", "Locale", "Collections",
        "CompletableFuture", "TimeUnit", "URI", "HttpRequest", "HttpResponse",
        "Duration", "ButtonWidget", "Tooltip", "Click", "MouseInput", "DrawContext",
        "HudClickSupport", "SelectableTextBlock", "ChatMessage", "BuildingSpec",
        "FormaRequest", "PromptMode", "PromptModeResolver", "PromptModeState",
        "BuildingPreviewState", "OutlinePreviewState", "SelectionContext",
        "FormaCraftClientNetworking", "ClientComponentCatalogState", "SettingsConfig",
        "UiTheme", "InputRouter", "BuildConfirmPanel", "Direction", "BlockPos",
        "Pattern", "Matcher", "NotNull", "GLFW", "true", "false", "null",
        "Override", "Integer", "String", "Boolean", "Objects", "FcaLog",
        "MinecraftClient", "Soft", "RenderedMessage", "ChatLayout", "InputBounds",
        "PreviewAdjustCommand", "SOFT_TIMEOUT_SEC", "HARD_TIMEOUT_SEC",
    ]:
        body = body.replace(f"p.{bad}", bad)

    # restore constants as ChatPanel.CONST
    for c in CONSTANTS:
        body = body.replace(f"p.{c}", f"ChatPanel.{c}")
        body = body.replace(f"__CALL_{c}__(", f"ChatPanel.{c}")  # noop safety

    # LOG is static-ish field on panel
    body = body.replace("p.LOG", "ChatPanel.LOG")

    return body


# Cross-call rewrites after all groups known
CROSS = {
    "initWidgets": "ChatWidgetBuilder.initWidgets",
    "computeChatLayout": "ChatPanelLayout.computeChatLayout",
    "computeInputBounds": "ChatPanelLayout.computeInputBounds",
    "getInputHeight": "ChatPanelLayout.getInputHeight",
    "chatLineHeight": "ChatPanelLayout.chatLineHeight",
    "enableScissor": "ChatPanelLayout.enableScissor",
    "disableScissor": "ChatPanelLayout.disableScissor",
    "drawMessages": "ChatMessageDrawer.drawMessages",
    "drawSpecSummary": "ChatMessageDrawer.drawSpecSummary",
    "tickTextSelectionDrag": "ChatMessageDrawer.tickTextSelectionDrag",
    "tickInputSelectionDrag": "ChatMessageDrawer.tickInputSelectionDrag",
    "drawInputArea": "ChatInputArea.drawInputArea",
    "getScaledMouseX": "ChatPanelLayout.getScaledMouseX",
    "getScaledMouseY": "ChatPanelLayout.getScaledMouseY",
    "hasReferenceImage": "ChatReferenceHelper.hasReferenceImage",
    "toggleReferenceImage": "ChatReferenceHelper.toggleReferenceImage",
    "chooseReferenceImageFile": "ChatReferenceHelper.chooseReferenceImageFile",
    "isReferenceImageUrl": "ChatReferenceHelper.isReferenceImageUrl",
    "tryStartSelection": "ChatPanelInteraction.tryStartSelection",
    "sendCurrentMessage": "ChatSendCoordinator.sendCurrentMessage",
    "stopGenerating": "ChatSendCoordinator.stopGenerating",
    "blurInput": "p.blurInput",
    "tryHandlePreviewAdjust": "ChatSendCoordinator.tryHandlePreviewAdjust",
    "getStrings": "ChatSendCoordinator.getStrings",
    "notifyComponentCatalogStatus": "ChatSendCoordinator.notifyComponentCatalogStatus",
    "mightReferencePlayerComponents": "ChatSendCoordinator.mightReferencePlayerComponents",
    "getString": "ChatSendCoordinator.getString",
    "formatThinkingElapsed": "ChatSendCoordinator.formatThinkingElapsed",
    "buildHealthUrlOrNull": "ChatSendCoordinator.buildHealthUrlOrNull",
    "isHealthy": "ChatSendCoordinator.isHealthy",
    "setPendingPhase": "p.setPendingPhase",
    "renderPendingElapsed": "p.renderPendingElapsed",
    "startElapsedTicker": "p.startElapsedTicker",
    "addAIError": "p.addAIError",
    "addAIMessage": "p.addAIMessage",
    "addAIStatus": "p.addAIStatus",
    "resetSession": "p.resetSession",
}


def finalize_calls(body: str) -> str:
    for meth, target in CROSS.items():
        # __CALL_foo__(  -> Target(p,   or Target( for static-only
        if target.startswith("p."):
            body = body.replace(f"__CALL_{meth}__(", f"{target}(")
        elif meth in (
            "enableScissor",
            "disableScissor",
            "chatLineHeight",
            "isReferenceImageUrl",
            "mightReferencePlayerComponents",
            "getString",
            "formatThinkingElapsed",
            "buildHealthUrlOrNull",
        ):
            # static helpers without host (or host not first for some)
            if meth in ("enableScissor", "disableScissor", "chatLineHeight"):
                body = body.replace(f"__CALL_{meth}__(", f"{target}(")
            elif meth == "isReferenceImageUrl":
                body = body.replace(f"__CALL_{meth}__(", f"{target}(")
            elif meth in ("mightReferencePlayerComponents", "getString", "formatThinkingElapsed", "buildHealthUrlOrNull"):
                body = body.replace(f"__CALL_{meth}__(", f"{target}(")
            else:
                body = body.replace(f"__CALL_{meth}__(", f"{target}(p, ")
        else:
            # instance-style extracted: already have (ChatPanel p, ...) at def; calls need p,
            # but if already rewritten as Method(ChatPanel p, — calls are Method(
            body = body.replace(f"__CALL_{meth}__(", f"{target}(p, ")
    # fix double p for static defs that incorrectly got host in signature — handled per file
    body = body.replace("(ChatPanel p, )", "(ChatPanel p)")
    body = body.replace("(p, )", "(p)")
    # fix p.p.blur etc
    body = body.replace("p.p.", "p.")
    # RenderedMessage type
    body = body.replace("new RenderedMessage()", "new ChatPanel.RenderedMessage()")
    body = body.replace("RenderedMessage rm", "ChatPanel.RenderedMessage rm")
    body = body.replace("List<RenderedMessage>", "List<ChatPanel.RenderedMessage>")
    # ChatLayout / InputBounds
    body = body.replace("ChatLayout ", "ChatPanel.ChatLayout ")
    body = body.replace("new ChatLayout(", "new ChatPanel.ChatLayout(")
    body = body.replace("InputBounds ", "ChatPanel.InputBounds ")
    body = body.replace("new InputBounds(", "new ChatPanel.InputBounds(")
    body = body.replace("(ChatLayout ", "(ChatPanel.ChatLayout ")
    body = body.replace("(InputBounds ", "(ChatPanel.InputBounds ")
    # PreviewAdjustCommand
    body = body.replace("PreviewAdjustCommand.", "ChatPreviewAdjustCommand.")
    body = body.replace("PreviewAdjustCommand ", "ChatPreviewAdjustCommand ")
    body = body.replace("new PreviewAdjustCommand(", "new ChatPreviewAdjustCommand(")
    return body


HEADERS = {
    "ChatWidgetBuilder.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class ChatWidgetBuilder {
    private ChatWidgetBuilder() {}
''',
    "ChatPanelLayout.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ai.context.SelectionContext;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.DrawContext;

public final class ChatPanelLayout {
    private ChatPanelLayout() {}
''',
    "ChatMessageDrawer.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.text.SelectableTextBlock;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

public final class ChatMessageDrawer {
    private ChatMessageDrawer() {}
''',
    "ChatInputArea.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ai.context.SelectionContext;
import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class ChatInputArea {
    private ChatInputArea() {}
''',
    "ChatPanelLayoutRenderer.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

public final class ChatPanelLayoutRenderer {
    private ChatPanelLayout() {}
''',
    "ChatPanelInteraction.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;

public final class ChatPanelInteraction {
    private ChatPanelInteraction() {}
''',
    "ChatReferenceHelper.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ChatReferenceHelper {
    private ChatReferenceHelper() {}
''',
    "ChatSendCoordinator.java": '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ai.prompt.PromptMode;
import com.formacraft.client.ai.prompt.PromptModeResolver;
import com.formacraft.client.component.ClientComponentCatalogState;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.preview.BuildingPreviewState;
import com.formacraft.client.preview.OutlinePreviewState;
import com.formacraft.client.preview.PromptModeState;
import com.formacraft.client.ui.panel.ChatPanel;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.config.SettingsConfig;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ChatSendCoordinator {
    private ChatSendCoordinator() {}
''',
}

# Fix typo in layout renderer header
HEADERS["ChatPanelLayoutRenderer.java"] = '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

public final class ChatPanelLayoutRenderer {
    private ChatPanelLayoutRenderer() {}
'''

GROUPS = {
    "ChatWidgetBuilder.java": [
        "private void initWidgets()",
    ],
    "ChatPanelLayout.java": [
        "private ChatLayout computeChatLayout()",
        "private InputBounds computeInputBounds(",
        "private static void enableScissor(",
        "private static void disableScissor(",
        "private static int chatLineHeight()",
        "private int getInputHeight(",
        "private double getScaledMouseX()",
        "private double getScaledMouseY()",
    ],
    "ChatMessageDrawer.java": [
        "private void drawMessages(",
        "private void tickTextSelectionDrag()",
        "private void tickInputSelectionDrag()",
        "private void drawSpecSummary(",
    ],
    "ChatInputArea.java": [
        "private void drawInputArea(",
    ],
    "ChatPanelLayoutRenderer.java": [
        "protected void drawContents(",
        "protected boolean drawCustomTooltip(",
    ],
    "ChatPanelInteraction.java": [
        "public boolean mouseClicked(",
        "public boolean mouseDragged(",
        "public boolean mouseReleased(",
        "private boolean tryStartSelection(",
        "public void mouseScrolled(",
        "public void keyPressed(int keyCode) {",
        "public void keyPressed(int keyCode, int scanCode, int modifiers)",
        "public void charTyped(",
    ],
    "ChatReferenceHelper.java": [
        "private void toggleReferenceImage()",
        "private boolean hasReferenceImage()",
        "private void chooseReferenceImageFile()",
        "private static boolean isReferenceImageUrl(",
    ],
    "ChatSendCoordinator.java": [
        "private void sendCurrentMessage()",
        "private @NotNull List<String> getStrings()",
        "private void notifyComponentCatalogStatus(",
        "private static boolean mightReferencePlayerComponents(",
        "private static @NotNull String getString(",
        "private static String formatThinkingElapsed(",
        "private boolean tryHandlePreviewAdjust(",
        "private static String buildHealthUrlOrNull(",
        "private boolean isHealthy(",
        "private void stopGenerating()",
    ],
}

remove: set[int] = set()
CHAT.mkdir(parents=True, exist_ok=True)

# Extract PreviewAdjustCommand nested record to its own file first
pac_start = find_line("private record PreviewAdjustCommand")
pac_end = method_end(pac_start)
pac_body = "".join(lines[pac_start - 1 : pac_end])
remove.update(range(pac_start, pac_end + 1))
# rewrite nested record to top-level
pac = pac_body
pac = pac.replace("private record PreviewAdjustCommand", "public record ChatPreviewAdjustCommand")
pac = re.sub(r"(?m)^            private static ", "    public static ", pac)
pac = re.sub(r"(?m)^        private static ", "    public static ", pac)
pac = pac.replace("PreviewAdjustCommand(", "ChatPreviewAdjustCommand(")
pac = pac.replace("LOG.debug", "ChatPanel.LOG.debug")
# fix indentation of record body (was nested)
pac_lines = pac.splitlines(keepends=True)
# write ChatPreviewAdjustCommand
preview_file = '''package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

''' + "".join(pac_lines) + "\n"
# The record may still have odd indent; normalize lightly
(CHAT / "ChatPreviewAdjustCommand.java").write_text(preview_file, encoding="utf-8")
print("ChatPreviewAdjustCommand", pac_start, pac_end)

for fname, subs in GROUPS.items():
    bodies = []
    for s in subs:
        a, b, body = grab(s)
        # skip if already removed
        if a in remove:
            # try unique search within remaining — shouldn't happen
            print("WARN already removed", s, a)
        remove.update(range(a, b + 1))
        bodies.append(finalize_calls(rewrite_body(body)))
        print(fname, a, b, s.split("(")[0].strip())
    joined = "\n".join(bodies)
    # static methods that incorrectly received ChatPanel p first arg
    for static_name in [
        "enableScissor",
        "disableScissor",
        "chatLineHeight",
        "isReferenceImageUrl",
        "mightReferencePlayerComponents",
        "getString",
        "formatThinkingElapsed",
        "buildHealthUrlOrNull",
    ]:
        joined = re.sub(
            rf"public static (\w+(?:<[^>]+>)?) {static_name}\(ChatPanel p, ",
            rf"public static \1 {static_name}(",
            joined,
        )
        joined = re.sub(
            rf"public static (\w+(?:<[^>]+>)?) {static_name}\(ChatPanel p\)",
            rf"public static \1 {static_name}()",
            joined,
        )
        joined = re.sub(
            rf"public static @NotNull (\w+(?:<[^>]+>)?) {static_name}\(ChatPanel p, ",
            rf"public static @NotNull \1 {static_name}(",
            joined,
        )
    # keyPressed(int) delegates — keep both
    (CHAT / fname).write_text(HEADERS[fname] + joined + "}\n", encoding="utf-8")

# Rebuild panel: strip removed lines
kept = [l for i, l in enumerate(lines, 1) if i not in remove]
text = "".join(kept)

# Make fields/constants public
text = re.sub(r"(?m)^    private static final FcaLog LOG", "    public static final FcaLog LOG", text)
text = re.sub(r"(?m)^    private final MinecraftClient client", "    public final MinecraftClient client", text)
text = re.sub(r"(?m)^    private final List<ChatMessage> messages", "    public final List<ChatMessage> messages", text)
text = re.sub(r"(?m)^    private String sessionId", "    public String sessionId", text)
text = re.sub(r"(?m)^    private MultilineTextInput inputBox", "    public MultilineTextInput inputBox", text)
text = re.sub(r"(?m)^    private final List<String> inputHistory", "    public final List<String> inputHistory", text)
text = re.sub(r"(?m)^    private int historyIndex", "    public int historyIndex", text)
text = re.sub(r"(?m)^    private String historyDraft", "    public String historyDraft", text)
text = re.sub(r"(?m)^    private int scrollOffset", "    public int scrollOffset", text)
text = re.sub(r"(?m)^    private double scrollRemainder", "    public double scrollRemainder", text)
text = re.sub(r"(?m)^    private int chatMaxScrollOffset", "    public int chatMaxScrollOffset", text)
text = re.sub(r"(?m)^    private AIStreamPrinter currentPrinter", "    public AIStreamPrinter currentPrinter", text)
text = re.sub(r"(?m)^    private long pendingRequestToken", "    public long pendingRequestToken", text)
text = re.sub(r"(?m)^    private int pendingThinkingIndex", "    public int pendingThinkingIndex", text)
text = re.sub(r"(?m)^    private long pendingStartMs", "    public long pendingStartMs", text)
text = re.sub(r"(?m)^    private String pendingPhase", "    public String pendingPhase", text)
text = re.sub(r"(?m)^    private static final long SOFT_TIMEOUT_SEC", "    public static final long SOFT_TIMEOUT_SEC", text)
text = re.sub(r"(?m)^    private static final long HARD_TIMEOUT_SEC", "    public static final long HARD_TIMEOUT_SEC", text)
text = re.sub(r"(?m)^    private final HttpClient healthHttp", "    public final HttpClient healthHttp", text)
text = re.sub(r"(?m)^    private final SelectableTextBlock selectable", "    public final SelectableTextBlock selectable", text)
text = re.sub(r"(?m)^    private int selectableMsgIndex", "    public int selectableMsgIndex", text)
text = re.sub(r"(?m)^    private boolean selectingText", "    public boolean selectingText", text)
text = re.sub(r"(?m)^    private static class RenderedMessage", "    public static class RenderedMessage", text)
text = re.sub(r"(?m)^    private final List<RenderedMessage> renderedMessages", "    public final List<RenderedMessage> renderedMessages", text)
for c in CONSTANTS:
    text = text.replace(f"    private static final int {c}", f"    public static final int {c}")
text = re.sub(r"(?m)^    private record ChatLayout", "    public record ChatLayout", text)
text = re.sub(r"(?m)^    private record InputBounds", "    public record InputBounds", text)
text = re.sub(r"(?m)^    private ButtonWidget sendButton", "    public ButtonWidget sendButton", text)
text = re.sub(r"(?m)^    private ButtonWidget stopButton", "    public ButtonWidget stopButton", text)
text = re.sub(r"(?m)^    private ButtonWidget referenceButton", "    public ButtonWidget referenceButton", text)
text = re.sub(r"(?m)^    private String referenceImageUrl", "    public String referenceImageUrl", text)
text = re.sub(r"(?m)^    private byte\[\] referenceImageFile", "    public byte[] referenceImageFile", text)
text = re.sub(r"(?m)^    private boolean readingReferenceImage", "    public boolean readingReferenceImage", text)
text = re.sub(r"(?m)^    private PromptMode promptMode", "    public PromptMode promptMode", text)

# Make RenderedMessage fields public
text = text.replace(
    """    public static class RenderedMessage {
        int msgIndex;
        int bubbleX, bubbleTop, bubbleBottom, bubbleW;
        int textX, textY, maxWidthPx, lineHeight;
        String displayText;
        boolean selectable;
    }""",
    """    public static class RenderedMessage {
        public int msgIndex;
        public int bubbleX, bubbleTop, bubbleBottom, bubbleW;
        public int textX, textY, maxWidthPx, lineHeight;
        public String displayText;
        public boolean selectable;
    }""",
)

delegates = '''
    public void drawContentBackgroundPublic(DrawContext ctx) { drawContentBackground(ctx); }
    public void drawTooltipCompatPublic(DrawContext ctx, List<Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }
    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }
    public boolean superMouseReleased(double x, double y, int button) { return super.mouseReleased(x, y, button); }

    public void initWidgets() { ChatWidgetBuilder.initWidgets(this); }
    public ChatLayout computeChatLayout() { return ChatPanelLayout.computeChatLayout(this); }
    public InputBounds computeInputBounds(ChatLayout layout) { return ChatPanelLayout.computeInputBounds(this, layout); }

    @Override protected void drawContents(DrawContext ctx) { ChatPanelLayoutRenderer.drawContents(this, ctx); }
    @Override protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) { return ChatPanelLayoutRenderer.drawCustomTooltip(this, ctx, mouseX, mouseY); }
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return ChatPanelInteraction.mouseClicked(this, mouseX, mouseY, button); }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return ChatPanelInteraction.mouseDragged(this, mouseX, mouseY, button, deltaX, deltaY); }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) { return ChatPanelInteraction.mouseReleased(this, mouseX, mouseY, button); }
    @Override public void mouseScrolled(double mouseX, double mouseY, double amount) { ChatPanelInteraction.mouseScrolled(this, mouseX, mouseY, amount); }
    @Override public void keyPressed(int keyCode) { ChatPanelInteraction.keyPressed(this, keyCode); }
    @Override public void keyPressed(int keyCode, int scanCode, int modifiers) { ChatPanelInteraction.keyPressed(this, keyCode, scanCode, modifiers); }
    @Override public void charTyped(char chr) { ChatPanelInteraction.charTyped(this, chr); }

    public void sendCurrentMessage() { ChatSendCoordinator.sendCurrentMessage(this); }
    public void stopGenerating() { ChatSendCoordinator.stopGenerating(this); }
    public void setPendingPhasePublic(String phase) { setPendingPhase(phase); }
    public void renderPendingElapsedPublic() { renderPendingElapsed(); }
    public void startElapsedTickerPublic(long token) { startElapsedTicker(token); }

'''

# Insert imports + delegates
text = text.replace(
    "package com.formacraft.client.ui.panel;",
    "package com.formacraft.client.ui.panel;\n\n"
    "import com.formacraft.client.ui.panel.chat.ChatInputArea;\n"
    "import com.formacraft.client.ui.panel.chat.ChatMessageDrawer;\n"
    "import com.formacraft.client.ui.panel.chat.ChatPanelInteraction;\n"
    "import com.formacraft.client.ui.panel.chat.ChatPanelLayout;\n"
    "import com.formacraft.client.ui.panel.chat.ChatPanelLayoutRenderer;\n"
    "import com.formacraft.client.ui.panel.chat.ChatReferenceHelper;\n"
    "import com.formacraft.client.ui.panel.chat.ChatSendCoordinator;\n"
    "import com.formacraft.client.ui.panel.chat.ChatWidgetBuilder;",
)

# constructor still calls initWidgets — OK public
text = text.replace("    public ChatPanel() {", delegates + "\n    public ChatPanel() {")

# setPendingPhase / renderPendingElapsed / startElapsedTicker stay private but send coordinator needs them —
# make them public
text = text.replace("    private void setPendingPhase(", "    public void setPendingPhase(")
text = text.replace("    private void renderPendingElapsed(", "    public void renderPendingElapsed(")
text = text.replace("    private void startElapsedTicker(", "    public void startElapsedTicker(")

# formatThinkingElapsed moved — update renderPendingElapsed body
text = text.replace(
    "ChatMessage.thinking(formatThinkingElapsed(pendingPhase, sec))",
    "ChatMessage.thinking(ChatSendCoordinator.formatThinkingElapsed(pendingPhase, sec))",
)

# Trim unused imports from ChatPanel (best-effort)
unused = [
    "import com.formacraft.client.ai.context.SelectionContext;\n",
    "import com.formacraft.client.ai.prompt.PromptModeResolver;\n",
    "import com.formacraft.client.component.ClientComponentCatalogState;\n",
    "import com.formacraft.client.network.FormaCraftClientNetworking;\n",
    "import com.formacraft.client.preview.BuildingPreviewState;\n",
    "import com.formacraft.client.preview.OutlinePreviewState;\n",
    "import com.formacraft.client.preview.PromptModeState;\n",
    "import com.formacraft.client.ui.widget.HudClickSupport;\n",
    "import com.formacraft.client.ui.input.InputRouter;\n",
    "import com.formacraft.common.model.request.FormaRequest;\n",
    "import com.formacraft.client.ui.UiTheme;\n",
    "import com.formacraft.config.SettingsConfig;\n",
    "import net.minecraft.client.gui.Click;\n",
    "import net.minecraft.client.gui.tooltip.Tooltip;\n",
    "import net.minecraft.client.input.MouseInput;\n",
    "import net.minecraft.util.math.BlockPos;\n",
    "import net.minecraft.util.math.Direction;\n",
    "import org.jetbrains.annotations.NotNull;\n",
    "import org.lwjgl.glfw.GLFW;\n",
    "import java.util.Locale;\n",
    "import java.util.regex.Matcher;\n",
    "import java.util.regex.Pattern;\n",
    "import java.util.concurrent.CompletableFuture;\n",
    "import java.util.concurrent.TimeUnit;\n",
    "import java.net.URI;\n",
    "import java.net.http.HttpRequest;\n",
    "import java.net.http.HttpResponse;\n",
]
# Keep HttpClient Duration for healthHttp field
for u in unused:
    text = text.replace(u, "")

# Remove unused chat helper imports that panel doesn't call directly in remaining code
# Panel still needs ChatMessage, AIStreamPrinter, MultilineTextInput, etc.

text = re.sub(r"\n{3,}", "\n\n", text)
PANEL.write_text(text, encoding="utf-8")
print("panel lines", text.count("\n") + 1)
print("removed methods spanning", len(remove), "lines")
