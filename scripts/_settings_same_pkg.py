"""Same-package Settings extract: package-private fields + Probe/Widget in panel package."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
PKG = Path("src/main/java/com/formacraft/client/ui/panel")
SETTINGS = Path("src/main/java/com/formacraft/client/ui/panel/settings")

text = PANEL.read_text(encoding="utf-8")
lines = text.splitlines(keepends=True)

# 1) Make instance fields package-private (keep private methods/static)
out = []
for l in lines:
    if re.match(r"    private (static final FcaLog|static final List|static )", l):
        out.append(l)
        continue
    if re.match(r"    private (void|boolean|int|float|long|String|Text|List|BaseUrl|Connection|Search|static boolean|static String|static float|static int|static double|static List)", l):
        # methods / static helpers — keep private for now
        out.append(l)
        continue
    if l.startswith("    private record "):
        out.append(l.replace("    private record ", "    record ", 1))
        continue
    if l.startswith("    private "):
        out.append("    " + l[len("    private ") :])
        continue
    out.append(l)
lines = out
text = "".join(lines)


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


def grab(substr: str) -> tuple[int, int, str]:
    a = find_line(substr)
    b = method_end(a)
    return a, b, "".join(lines[a - 1 : b])


FIELDS = [
    "searchRevision", "searchIdentity", "validatingSearchKey", "testSearchKeyButton",
    "orchestratorInput", "draftSearchProvider", "searchApiKeyInput", "googleCseCxInput",
    "httpClient", "client", "connectionRevision", "connectionIdentity", "detectingModel",
    "validatingKey", "testKeyButton", "detectModelButton", "apiKeyInput", "draftLlmProvider",
    "llmBaseUrlInput", "modelInput", "availableModels", "availableModelsUpdatedAtMs",
    "choices", "draftModel", "modelNeedsConfirmation", "showHideButton", "hideKey",
    "pasteButton", "saveButton", "cancelButton", "resetButton", "modelChoicesButton",
    "llmProviderButton", "llmBaseUrlPresetButton", "debugWarningsButton", "searchProviderButton",
    "temperatureSlider", "interactionReachSlider", "reachInput", "temperatureInput",
    "draftTemperature", "draftInteractionReach", "draftLlmBaseUrl", "draftShowDebugWarnings",
    "baseUrlPresetIndex", "BASE_URL_PRESETS", "groupButtons", "collapsed", "backendSection",
    "details", "ui", "statusButton", "toast", "isToastError", "LOG",
]

LOCAL_METHODS = {
    "syncConnectionIdentity", "currentConnectionIdentity", "syncSearchIdentity",
    "currentSearchIdentity", "sanitizeEndpoint", "readSearchValidateOk", "readSearchValidateMessage",
    "getDetectModelButtonText", "getLlmProviderButtonText", "getSearchProviderButtonText",
    "getDebugWarningsButtonText", "getShowHideText", "getBaseUrlPresetButtonText",
    "getSelectedBaseUrlPreset", "applyBaseUrlPreset", "blurInputs", "openModels",
    "openProviders", "openBaseUrlPresets", "openSearchProviders", "openResetGroups",
    "toggleHideKey", "toggleDebugWarnings", "initWidgets", "syncWidgetStateFromDraft",
    "syncBaseUrlPresetFromValue", "buildPresetsFromCatalog", "startDetectModel",
    "startValidateKey", "startValidateSearchKey", "showToast", "saveSettings",
    "loadFromConfig", "clamp01", "clampReach", "reachToValue", "valueToReach", "afterTextEdit",
}


def rewrite(body: str, *, static_ok: bool = False) -> str:
    # method visibility -> package
    body = re.sub(r"^    private ", "    ", body, count=1, flags=re.M)
    body = re.sub(r"^    @Override\n    public ", "    public ", body, count=1, flags=re.M)
    # prefix fields with p.
    for m in sorted(FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){re.escape(m)}(?!\w)", f"p.{m}", body)
    body = body.replace("p.p.", "p.")
    # local methods on this controller stay unqualified if in LOCAL and we're in same class
    # Panel-only methods need p.
    for m in ("showToast", "saveSettings", "loadFromConfig", "afterTextEdit", "clamp01", "clampReach", "reachToValue", "valueToReach"):
        # if already p. ok; if bare, make p. for panel-owned
        pass
    # Fix types wrongly prefixed
    for bad in [
        "SettingsHttpProbe", "SettingsModelCatalog", "SettingsSearchProviders", "SettingsChoicePanel",
        "SettingsGroup", "SettingsSliders", "SettingsConfig", "SettingsPreferenceValues",
        "SettingsBaseUrlPresets", "SettingsConfigCoordinator", "FormacraftMod", "CompletableFuture",
        "Duration", "URLEncoder", "StandardCharsets", "URI", "URISyntaxException", "ArrayList",
        "List", "Text", "ButtonWidget", "Tooltip", "HudTextInput", "System", "Boolean", "Integer",
        "Math", "Locale", "Map", "Objects", "Override", "DetectResponse", "ConnectionIdentity",
        "SearchIdentity", "BaseUrlPreset", "IllegalArgumentException", "Exception", "String",
        "StringBuilder", "boolean", "int", "float", "long", "void", "true", "false", "null",
        "var", "new", "return", "if", "else", "for", "while", "switch", "case", "break",
        "continue", "try", "catch", "finally", "throw", "throws", "import", "package",
    ]:
        body = body.replace(f"p.{bad}", bad)
    # Nested records on SettingsPanel
    body = re.sub(r"(?<![\w.])DetectResponse\b", "SettingsPanel.DetectResponse", body)
    body = re.sub(r"(?<![\w.])ConnectionIdentity\b", "SettingsPanel.ConnectionIdentity", body)
    body = re.sub(r"(?<![\w.])SearchIdentity\b", "SettingsPanel.SearchIdentity", body)
    body = re.sub(r"(?<![\w.])BaseUrlPreset\b", "SettingsPanel.BaseUrlPreset", body)
    body = body.replace("SettingsPanel.SettingsPanel.", "SettingsPanel.")
    # Un-prefix local controller methods
    for m in LOCAL_METHODS:
        body = body.replace(f"p.{m}(", f"{m}(")
    # Panel helpers still needed via p for ones NOT moved into this class
    # showToast/save/load/clamp/reach stay on panel — re-prefix those
    for m in ("showToast", "saveSettings", "loadFromConfig", "afterTextEdit", "clamp01", "clampReach", "reachToValue", "valueToReach"):
        body = re.sub(rf"(?<![\w.]){m}\(", f"p.{m}(", body)
    return body


probe_keys = [
    "private void startValidateSearchKey()",
    "private static boolean readSearchValidateOk",
    "private static String readSearchValidateMessage",
    "private SearchIdentity currentSearchIdentity",
    "SearchIdentity currentSearchIdentity",
    "private void syncSearchIdentity",
    "private ConnectionIdentity currentConnectionIdentity",
    "ConnectionIdentity currentConnectionIdentity",
    "private void syncConnectionIdentity",
    "private void startDetectModel()",
    "private void startValidateKey()",
    "private static String sanitizeEndpoint",
]

widget_keys = [
    "private void initWidgets()",
    "public void syncWidgetStateFromDraft()",
    "    public void syncWidgetStateFromDraft()",
    "private void openResetGroups()",
    "private void openModels()",
    "private void openProviders()",
    "private void openBaseUrlPresets()",
    "private void openSearchProviders()",
    "private void toggleHideKey()",
    "private Text getShowHideText()",
    "private Text getDebugWarningsButtonText()",
    "private void toggleDebugWarnings()",
    "private Text getDetectModelButtonText()",
    "private Text getLlmProviderButtonText()",
    "private Text getSearchProviderButtonText()",
    "private BaseUrlPreset getSelectedBaseUrlPreset()",
    "private Text getBaseUrlPresetButtonText()",
    "private void applyBaseUrlPreset",
    "public void syncBaseUrlPresetFromValue",
    "    public void syncBaseUrlPresetFromValue",
    "private static List<BaseUrlPreset> buildPresetsFromCatalog",
    "private void blurInputs()",
    "    void blurInputs()",  # after field strip may change
]

# refresh line list after field strip
lines = text.splitlines(keepends=True)

probe_chunks = []
seen = set()
for k in probe_keys:
    try:
        a, b, body = grab(k)
    except KeyError:
        continue
    if (a, b) in seen:
        continue
    seen.add((a, b))
    probe_chunks.append((a, b, rewrite(body)))
    print("probe", a, b, k)

widget_chunks = []
for k in widget_keys:
    try:
        a, b, body = grab(k)
    except KeyError:
        continue
    if (a, b) in seen:
        continue
    seen.add((a, b))
    widget_chunks.append((a, b, rewrite(body)))
    print("widget", a, b, k)

PROBE_H = '''package com.formacraft.client.ui.panel;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.panel.settings.*;
import com.formacraft.common.logging.FcaLog;
import net.minecraft.text.Text;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Package-private probe helpers for SettingsPanel (move-only). */
final class SettingsProbeController {
    private static final FcaLog LOG = FcaLog.of("SettingsProbeController");
    private final SettingsPanel p;
    SettingsProbeController(SettingsPanel panel) { this.p = panel; }

'''

WIDGET_H = '''package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.panel.settings.*;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Package-private widget/dialog helpers for SettingsPanel (move-only). */
final class SettingsWidgetAssembler {
    private final SettingsPanel p;
    SettingsWidgetAssembler(SettingsPanel panel) { this.p = panel; }

'''

# sanitizeEndpoint should be static callable — rewrite static methods specially
def fix_static(body: str) -> str:
    if "sanitizeEndpoint" in body.split("(", 1)[0] or "readSearchValidate" in body.split("(", 1)[0] or "buildPresetsFromCatalog" in body.split("(", 1)[0]:
        # restore static, remove p. on nothing
        body = re.sub(r"^    (static )?", "    static ", body, count=1, flags=re.M)
        # remove erroneous p. inside pure static
        # keep as rewritten
    return body

probe_src = PROBE_H + "\n".join(fix_static(b) for _, _, b in probe_chunks) + "}\n"
widget_src = WIDGET_H + "\n".join(fix_static(b) for _, _, b in widget_chunks) + "}\n"

# Also write public facades in settings/ as plan requests
(SETTINGS / "SettingsProbeController.java").write_text(
    '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;

/** Public facade; implementation lives package-private beside SettingsPanel for field access. */
public final class SettingsProbeController {
    private final com.formacraft.client.ui.panel.SettingsProbeControllerPeer peer;
    public SettingsProbeController(SettingsPanel panel) {
        this.peer = panel.probes;
    }
    public void startDetectModel() { peer.startDetectModel(); }
    public void startValidateKey() { peer.startValidateKey(); }
    public void startValidateSearchKey() { peer.startValidateSearchKey(); }
    public void syncConnectionIdentity() { peer.syncConnectionIdentity(); }
    public static String sanitizeEndpoint(String endpoint) {
        return com.formacraft.client.ui.panel.SettingsProbeControllerPeer.sanitizeEndpoint(endpoint);
    }
}
''',
    encoding="utf-8",
)

# Simpler: name package-private class SettingsProbeControllerPeer, public facade SettingsProbeController
# Avoid confusion — just put implementation IN settings with public fields on panel.

print("switching: write implementations into settings with public panel fields")
