"""Final Settings split: same-package peers + public facades in settings/."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
PANEL_PKG = Path("src/main/java/com/formacraft/client/ui/panel")
SETTINGS = Path("src/main/java/com/formacraft/client/ui/panel/settings")

lines = PANEL.read_text(encoding="utf-8").splitlines(keepends=True)

# Strip private from fields only (lines with private that don't look like methods)
new_lines = []
for l in lines:
    if re.match(r"    private record ", l):
        new_lines.append(l.replace("private record", "record", 1))
    elif re.match(r"    private (?!static |void |boolean \w+\(|int \w+\(|float \w+\(|long \w+\(|String \w+\(|Text \w+\(|List|BaseUrl|Connection|Search|@)", l):
        head = l.split("(", 1)[0]
        if "=" in l or l.strip().endswith(";") or "HttpClient" in l or "HudTextInput" in l or "ButtonWidget" in l or "Settings" in l and "(" not in head:
            new_lines.append(re.sub(r"^    private ", "    ", l, count=1))
            continue
        new_lines.append(l)
    else:
        new_lines.append(l)
lines = new_lines


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
    b = method_end(a)
    return a, b, "".join(lines[a - 1 : b])


# Same-package: methods can access package-private fields WITHOUT p. prefix if we pass SettingsPanel
# and use methods as instance methods on peer with field access via p.field — still need prefix.
# ALTERNATIVE: make peers nested... 
# BEST same-package trick: keep methods as package-private STATIC on peer taking SettingsPanel,
# rewrite body with p. only for fields — same as before.

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

PROBE_LOCAL = {
    "syncConnectionIdentity", "currentConnectionIdentity", "syncSearchIdentity",
    "currentSearchIdentity", "sanitizeEndpoint", "readSearchValidateOk", "readSearchValidateMessage",
    "startDetectModel", "startValidateKey", "startValidateSearchKey",
}
WIDGET_LOCAL = {
    "getDetectModelButtonText", "getLlmProviderButtonText", "getSearchProviderButtonText",
    "getDebugWarningsButtonText", "getShowHideText", "getBaseUrlPresetButtonText",
    "getSelectedBaseUrlPreset", "applyBaseUrlPreset", "blurInputs", "openModels",
    "openProviders", "openBaseUrlPresets", "openSearchProviders", "openResetGroups",
    "toggleHideKey", "toggleDebugWarnings", "initWidgets", "syncWidgetStateFromDraft",
    "syncBaseUrlPresetFromValue", "buildPresetsFromCatalog",
}
PANEL_FNS = {
    "showToast", "saveSettings", "loadFromConfig", "afterTextEdit", "clamp01", "clampReach",
    "reachToValue", "valueToReach", "openDetails",
}

SKIP_PREFIX = {
    "SettingsHttpProbe", "SettingsModelCatalog", "SettingsSearchProviders", "SettingsChoicePanel",
    "SettingsGroup", "SettingsSliders", "SettingsConfig", "SettingsPreferenceValues",
    "SettingsBaseUrlPresets", "SettingsConfigCoordinator", "FormacraftMod", "CompletableFuture",
    "Duration", "URLEncoder", "StandardCharsets", "URI", "URISyntaxException", "ArrayList",
    "List", "Text", "ButtonWidget", "Tooltip", "HudTextInput", "System", "Boolean", "Integer",
    "Math", "Locale", "String", "StringBuilder", "Exception", "IllegalArgumentException",
    "DetectResponse", "ConnectionIdentity", "SearchIdentity", "BaseUrlPreset", "Override",
}


def rewrite(body: str, local: set[str], other_ctrl: str | None) -> str:
    body = re.sub(r"^    (private |public |@Override\n    public |@Override\n    )", "    ", body, count=1, flags=re.M)
    body = re.sub(r"^    @Override\n", "    ", body, count=1, flags=re.M)
    # ensure method is package-visible (no modifier) or public
    if not re.match(r"\s*(public |static |void |Text |boolean |int |float |long |String |List|BaseUrl|Connection|Search|Detect)", body):
        body = "    " + body.lstrip()
    # If starts with static keep; if void/Text make public for facade
    body = re.sub(r"^    (static )", "    static ", body, count=1, flags=re.M)
    body = re.sub(r"^    (void |Text |boolean |BaseUrlPreset |ConnectionIdentity |SearchIdentity |List)", r"    \1", body, count=1, flags=re.M)

    for m in sorted(FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){re.escape(m)}(?!\w)", f"p.{m}", body)
    body = body.replace("p.p.", "p.")
    for bad in SKIP_PREFIX:
        body = body.replace(f"p.{bad}", bad)
    for m in local:
        body = body.replace(f"p.{m}(", f"{m}(")
    for m in PANEL_FNS:
        body = re.sub(rf"(?<![\w.]){m}\(", f"p.{m}(", body)
    if other_ctrl:
        for m in (PROBE_LOCAL | WIDGET_LOCAL) - local:
            body = re.sub(rf"(?<![\w.]){m}\(", f"p.{other_ctrl}.{m}(", body)
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

remove = set()
probe_bodies = []
widget_bodies = []
for s in probe_subs:
    a, b, body = grab(s)
    probe_bodies.append(rewrite(body, PROBE_LOCAL, "widgets"))
    remove.update(range(a, b + 1))
    print("P", a, b)
for s in widget_subs:
    a, b, body = grab(s)
    widget_bodies.append(rewrite(body, WIDGET_LOCAL, "probes"))
    remove.update(range(a, b + 1))
    print("W", a, b)

# Write SAME-PACKAGE peers (field access)
peer_probe = f'''package com.formacraft.client.ui.panel;

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

/** Package-private probe peer for SettingsPanel. */
final class SettingsProbePeer {{
    private final SettingsPanel p;
    SettingsProbePeer(SettingsPanel panel) {{ this.p = panel; }}

{chr(10).join(probe_bodies)}
}}
'''

peer_widget = f'''package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.panel.settings.*;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Package-private widget peer for SettingsPanel. */
final class SettingsWidgetPeer {{
    private final SettingsPanel p;
    SettingsWidgetPeer(SettingsPanel panel) {{ this.p = panel; }}

{chr(10).join(widget_bodies)}
}}
'''

(PANEL_PKG / "SettingsProbePeer.java").write_text(peer_probe, encoding="utf-8")
(PANEL_PKG / "SettingsWidgetPeer.java").write_text(peer_widget, encoding="utf-8")

# Public facades in settings/ as plan names
(SETTINGS / "SettingsProbeController.java").write_text(
    '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;

/** Plan-facing name; delegates to package-private SettingsProbePeer. */
public final class SettingsProbeController {
    private final SettingsPanel panel;
    public SettingsProbeController(SettingsPanel panel) { this.panel = panel; }
    public void startDetectModel() { panel.probes.startDetectModel(); }
    public void startValidateKey() { panel.probes.startValidateKey(); }
    public void startValidateSearchKey() { panel.probes.startValidateSearchKey(); }
    public void syncConnectionIdentity() { panel.probes.syncConnectionIdentity(); }
    public static String sanitizeEndpoint(String endpoint) {
        return com.formacraft.client.ui.panel.SettingsProbePeer.sanitizeEndpoint(endpoint);
    }
}
''',
    encoding="utf-8",
)
(SETTINGS / "SettingsWidgetAssembler.java").write_text(
    '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;
import net.minecraft.text.Text;

/** Plan-facing name; delegates to package-private SettingsWidgetPeer. */
public final class SettingsWidgetAssembler {
    private final SettingsPanel panel;
    public SettingsWidgetAssembler(SettingsPanel panel) { this.panel = panel; }
    public void initWidgets() { panel.widgets.initWidgets(); }
    public void syncWidgetStateFromDraft() { panel.widgets.syncWidgetStateFromDraft(); }
    public static java.util.List<SettingsPanel.BaseUrlPreset> buildPresetsFromCatalog() {
        return com.formacraft.client.ui.panel.SettingsWidgetPeer.buildPresetsFromCatalog();
    }
}
''',
    encoding="utf-8",
)

kept = [l for i, l in enumerate(lines, 1) if i not in remove]
text = "".join(kept)
text = text.replace(
    "private static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog();",
    "static final List<BaseUrlPreset> BASE_URL_PRESETS = SettingsWidgetPeer.buildPresetsFromCatalog();",
)
text = text.replace(
    "static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog();",
    "static final List<BaseUrlPreset> BASE_URL_PRESETS = SettingsWidgetPeer.buildPresetsFromCatalog();",
)

delegates = '''
    final SettingsProbePeer probes = new SettingsProbePeer(this);
    final SettingsWidgetPeer widgets = new SettingsWidgetPeer(this);

    void startValidateSearchKey() { probes.startValidateSearchKey(); }
    void startDetectModel() { probes.startDetectModel(); }
    void startValidateKey() { probes.startValidateKey(); }
    void syncConnectionIdentity() { probes.syncConnectionIdentity(); }
    void syncSearchIdentity() { probes.syncSearchIdentity(); }
    ConnectionIdentity currentConnectionIdentity() { return probes.currentConnectionIdentity(); }
    SearchIdentity currentSearchIdentity() { return probes.currentSearchIdentity(); }
    static String sanitizeEndpoint(String endpoint) { return SettingsProbePeer.sanitizeEndpoint(endpoint); }

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
text = text.replace("    public SettingsPanel() {", delegates + "\n    public SettingsPanel() {")
PANEL.write_text(text, encoding="utf-8")
print("panel lines", text.count("\n") + 1)
