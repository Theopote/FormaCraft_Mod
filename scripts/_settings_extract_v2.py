"""Reliable SettingsPanel split into settings/SettingsProbeController + SettingsWidgetAssembler."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
SETTINGS = Path("src/main/java/com/formacraft/client/ui/panel/settings")

raw = PANEL.read_text(encoding="utf-8")
lines = raw.splitlines(keepends=True)


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


# Make nested records + fields used by extractors public
def make_accessible(t: str) -> str:
    t = t.replace("    private record ", "    public record ")
    # fields: only change private field decls (have = or end with ; and no ()
    out = []
    for l in t.splitlines(keepends=True):
        if re.match(r"    private (?!static |void |boolean \w+\(|int \w+\(|float |long |String \w+\(|Text |List<.*>\s+\w+\(|BaseUrl|Connection|Search)", l):
            # heuristic: field if '(' not in signature part before {
            strip = l.strip()
            if "(" in strip and ")" in strip and "{" not in strip and "=" not in strip:
                out.append(l)  # method signature continuation?
                continue
            if strip.endswith(";") or "=" in strip or strip.endswith(",") or "HttpClient" in strip or "HudTextInput" in strip or "ButtonWidget" in strip or "SettingsSliders" in strip or "EnumMap" in strip or "EnumSet" in strip or "MinecraftClient" in strip or "BackendSection" in strip or "ChoicePanel" in strip or "DetailsPanel" in strip or "UiBindings" in strip or re.match(r"private (boolean|int|float|long|String|List)", strip):
                out.append(l.replace("    private ", "    public ", 1))
                continue
        out.append(l)
    return "".join(out)


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
    "details", "ui", "statusButton", "toast", "isToastError",
]

PANEL_ONLY = {"showToast", "saveSettings", "loadFromConfig", "afterTextEdit", "clamp01", "clampReach", "reachToValue", "valueToReach"}
CTRL_LOCAL = {
    "syncConnectionIdentity", "currentConnectionIdentity", "syncSearchIdentity",
    "currentSearchIdentity", "sanitizeEndpoint", "readSearchValidateOk", "readSearchValidateMessage",
    "getDetectModelButtonText", "getLlmProviderButtonText", "getSearchProviderButtonText",
    "getDebugWarningsButtonText", "getShowHideText", "getBaseUrlPresetButtonText",
    "getSelectedBaseUrlPreset", "applyBaseUrlPreset", "blurInputs", "openModels",
    "openProviders", "openBaseUrlPresets", "openSearchProviders", "openResetGroups",
    "toggleHideKey", "toggleDebugWarnings", "initWidgets", "syncWidgetStateFromDraft",
    "syncBaseUrlPresetFromValue", "buildPresetsFromCatalog", "startDetectModel",
    "startValidateKey", "startValidateSearchKey",
}

JAVA_KEYWORDS_TYPES = {
    "SettingsHttpProbe", "SettingsModelCatalog", "SettingsSearchProviders", "SettingsChoicePanel",
    "SettingsGroup", "SettingsSliders", "SettingsConfig", "SettingsPreferenceValues",
    "SettingsBaseUrlPresets", "SettingsConfigCoordinator", "FormacraftMod", "CompletableFuture",
    "Duration", "URLEncoder", "StandardCharsets", "URI", "URISyntaxException", "ArrayList",
    "List", "Text", "ButtonWidget", "Tooltip", "HudTextInput", "System", "Boolean", "Integer",
    "Math", "Locale", "Map", "Objects", "String", "StringBuilder", "Exception", "IllegalArgumentException",
    "Override", "var", "new", "return", "if", "else", "for", "while", "try", "catch", "true", "false",
    "null", "boolean", "int", "float", "long", "void", "case", "switch", "break", "continue",
}


def rewrite_body(body: str) -> str:
    body = re.sub(r"^    private ", "    public ", body, count=1, flags=re.M)
    body = re.sub(r"^    @Override\r?\n    public ", "    public ", body, count=1)
    for m in sorted(FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){re.escape(m)}(?!\w)", f"p.{m}", body)
    body = body.replace("p.p.", "p.")
    for bad in JAVA_KEYWORDS_TYPES:
        body = body.replace(f"p.{bad}", bad)
    # nested types
    for nt in ("DetectResponse", "ConnectionIdentity", "SearchIdentity", "BaseUrlPreset"):
        body = re.sub(rf"(?<![\w.]){nt}\b", f"SettingsPanel.{nt}", body)
    body = body.replace("SettingsPanel.SettingsPanel.", "SettingsPanel.")
    for m in CTRL_LOCAL:
        body = body.replace(f"p.{m}(", f"{m}(")
    for m in PANEL_ONLY:
        body = re.sub(rf"(?<![\w.]){m}\(", f"p.{m}(", body)
    # LOG
    body = re.sub(r"(?<![\w.])LOG\.", "SettingsPanel.LOG.", body)
    return body


probe_subs = [
    "private void startValidateSearchKey()",
    "private static boolean readSearchValidateOk",
    "private static String readSearchValidateMessage",
    "private SearchIdentity currentSearchIdentity",
    "private void syncSearchIdentity",
    "private ConnectionIdentity currentConnectionIdentity",
    "private void syncConnectionIdentity",
    "private void startDetectModel()",
    "private void startValidateKey()",
    "private static String sanitizeEndpoint",
]
widget_subs = [
    "private void initWidgets()",
    "public void syncWidgetStateFromDraft()",
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
    "private static List<BaseUrlPreset> buildPresetsFromCatalog",
    "private void blurInputs()",
]

probe = []
widget = []
remove = set()
for s in probe_subs:
    a, b, body = grab(s)
    probe.append(rewrite_body(body))
    remove.update(range(a, b + 1))
    print("P", a, b, s)
for s in widget_subs:
    a, b, body = grab(s)
    widget.append(rewrite_body(body))
    remove.update(range(a, b + 1))
    print("W", a, b, s)

PROBE_JAVA = '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.panel.SettingsPanel;
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

/** HTTP probe / identity helpers carved from SettingsPanel (zero behavior change). */
public final class SettingsProbeController {
    private final SettingsPanel p;

    public SettingsProbeController(SettingsPanel panel) {
        this.p = panel;
    }

''' + "\n".join(probe) + "}\n"

WIDGET_JAVA = '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Widget assembly / dialogs carved from SettingsPanel (zero behavior change). */
public final class SettingsWidgetAssembler {
    private final SettingsPanel p;

    public SettingsWidgetAssembler(SettingsPanel panel) {
        this.p = panel;
    }

''' + "\n".join(widget) + "}\n"

(SETTINGS / "SettingsProbeController.java").write_text(PROBE_JAVA, encoding="utf-8")
(SETTINGS / "SettingsWidgetAssembler.java").write_text(WIDGET_JAVA, encoding="utf-8")

# Rebuild panel without removed lines; make accessible; inject delegates
new_lines = [l for i, l in enumerate(lines, 1) if i not in remove]
new_text = "".join(new_lines)
new_text = make_accessible(new_text)

# Ensure LOG is public static for probe
new_text = new_text.replace(
    "private static final FcaLog LOG",
    "public static final FcaLog LOG",
)

delegates = '''
    public final SettingsProbeController probes = new SettingsProbeController(this);
    public final SettingsWidgetAssembler widgets = new SettingsWidgetAssembler(this);

    void startValidateSearchKey() { probes.startValidateSearchKey(); }
    void startDetectModel() { probes.startDetectModel(); }
    void startValidateKey() { probes.startValidateKey(); }
    void syncConnectionIdentity() { probes.syncConnectionIdentity(); }
    void syncSearchIdentity() { probes.syncSearchIdentity(); }
    static String sanitizeEndpoint(String endpoint) { return SettingsProbeController.sanitizeEndpoint(endpoint); }

    void initWidgets() { widgets.initWidgets(); }
    @Override public void syncWidgetStateFromDraft() { widgets.syncWidgetStateFromDraft(); }
    void openResetGroups() { widgets.openResetGroups(); }
    void openModels() { widgets.openModels(); }
    void openProviders() { widgets.openProviders(); }
    void openBaseUrlPresets() { widgets.openBaseUrlPresets(); }
    void openSearchProviders() { widgets.openSearchProviders(); }
    void toggleHideKey() { widgets.toggleHideKey(); }
    Text getShowHideText() { return widgets.getShowHideText(); }
    Text getDebugWarningsButtonText() { return widgets.getDebugWarningsButtonText(); }
    void toggleDebugWarnings() { widgets.toggleDebugWarnings(); }
    Text getDetectModelButtonText() { return widgets.getDetectModelButtonText(); }
    Text getLlmProviderButtonText() { return widgets.getLlmProviderButtonText(); }
    Text getSearchProviderButtonText() { return widgets.getSearchProviderButtonText(); }
    Text getBaseUrlPresetButtonText() { return widgets.getBaseUrlPresetButtonText(); }
    @Override public void syncBaseUrlPresetFromValue(String baseUrl) { widgets.syncBaseUrlPresetFromValue(baseUrl); }
    void blurInputs() { widgets.blurInputs(); }

'''

# Remove buildPresetsFromCatalog leftover field init dependency — BASE_URL_PRESETS still references it
# widgets.buildPresetsFromCatalog moved — update field init
new_text = new_text.replace(
    "private static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog();",
    "public static final List<BaseUrlPreset> BASE_URL_PRESETS = SettingsWidgetAssembler.buildPresetsFromCatalog();",
)
new_text = new_text.replace(
    "public static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog();",
    "public static final List<BaseUrlPreset> BASE_URL_PRESETS = SettingsWidgetAssembler.buildPresetsFromCatalog();",
)

new_text = new_text.replace(
    "    public SettingsPanel() {",
    delegates + "\n    public SettingsPanel() {",
)

# Make remaining private helpers used by widgets package-accessible via public wrappers if needed
# afterTextEdit, clamp01, reachToValue, valueToReach, saveSettings, loadFromConfig, showToast stay on panel
# Ensure showToast stays public (already is via host)

PANEL.write_text(new_text, encoding="utf-8")
print("panel", new_text.count("\n") + 1)
print("probe", PROBE_JAVA.count("\n") + 1)
print("widget", WIDGET_JAVA.count("\n") + 1)
