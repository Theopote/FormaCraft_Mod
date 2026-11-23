"""Cut SettingsPanel methods into SettingsProbeController + SettingsWidgetAssembler."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
SETTINGS = Path("src/main/java/com/formacraft/client/ui/panel/settings")
text = PANEL.read_text(encoding="utf-8")
lines = text.splitlines(keepends=True)


def find_line(substr: str) -> int:
    for i, l in enumerate(lines):
        if substr in l:
            return i + 1
    raise KeyError(substr)


def method_end(start_line: int) -> int:
    """Brace-match from method signature line; return inclusive end line."""
    i = start_line - 1
    # find first { on this or following lines
    buf = ""
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
    raise RuntimeError(f"unclosed method at {start_line}")


def slice_m(start_sub: str) -> tuple[int, int, str]:
    a = find_line(start_sub)
    b = method_end(a)
    return a, b, "".join(lines[a - 1 : b])


# Collect probe methods
probe_subs = [
    "private void startValidateSearchKey()",
    "private static boolean readSearchValidateOk",
    "private static String readSearchValidateMessage",
    "SearchIdentity currentSearchIdentity",
    "private void syncSearchIdentity",
    "ConnectionIdentity currentConnectionIdentity",
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

probe_ranges = []
for s in probe_subs:
    try:
        probe_ranges.append(slice_m(s))
        print("probe", s, probe_ranges[-1][0], probe_ranges[-1][1])
    except Exception as e:
        print("FAIL probe", s, e)

widget_ranges = []
for s in widget_subs:
    try:
        widget_ranges.append(slice_m(s))
        print("widget", s, widget_ranges[-1][0], widget_ranges[-1][1])
    except Exception as e:
        print("FAIL widget", s, e)


def to_static(body: str, class_ref: str = "SettingsPanel p") -> str:
    """Rewrite instance method to static taking SettingsPanel p."""
    # private void foo( -> static void foo(SettingsPanel p
    body = re.sub(
        r"(private|public)\s+static\s+",
        "static ",
        body,
        count=1,
    )
    if re.match(r"\s*static\s+", body):
        # already static — add SettingsPanel only if not present; keep as-is for pure static helpers
        if "sanitizeEndpoint" in body or "readSearchValidate" in body or "buildPresetsFromCatalog" in body:
            return body
    body = re.sub(
        r"(private|public)\s+(void|Text|boolean|BaseUrlPreset|ConnectionIdentity|SearchIdentity)\s+(\w+)\(",
        rf"static \2 \3({class_ref}, ",
        body,
        count=1,
    )
    # fix empty extra comma: (SettingsPanel p, ) -> (SettingsPanel p)
    body = body.replace(f"({class_ref}, )", f"({class_ref})")
    body = body.replace(f"({class_ref},)", f"({class_ref})")
    # Qualify field access: this. already none; bare field refs are hard.
    # Instead prefix common fields with p.
    return body


# Better approach: keep methods as instance methods on the NEW class, constructor takes SettingsPanel.
PROBE_HEADER = '''package com.formacraft.client.ui.panel.settings;

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

/** HTTP probe / connection-identity helpers carved from SettingsPanel (move-only). */
public final class SettingsProbeController {
    private static final FcaLog LOG = FcaLog.of("SettingsProbeController");
    private final SettingsPanel p;

    public SettingsProbeController(SettingsPanel panel) {
        this.p = panel;
    }

'''

WIDGET_HEADER = '''package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;
import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Widget init / draft sync / dialogs carved from SettingsPanel (move-only). */
public final class SettingsWidgetAssembler {
    private final SettingsPanel p;

    public SettingsWidgetAssembler(SettingsPanel panel) {
        this.p = panel;
    }

'''


def rewrite_instance_body(body: str) -> str:
    """Strip private/public, keep as package methods; rewrite field access via p."""
    # Change method modifiers to public (called from panel)
    body = re.sub(r"^\s*private ", "    public ", body, count=1, flags=re.M)
    body = re.sub(r"^\s*public void syncWidgetStateFromDraft", "    public void syncWidgetStateFromDraft", body)
    body = re.sub(r"^\s*@Override\n\s*public void syncWidgetStateFromDraft", "    public void syncWidgetStateFromDraft", body)
    body = re.sub(r"^\s*@Override\n\s*public void syncBaseUrlPresetFromValue", "    public void syncBaseUrlPresetFromValue", body)

    # For static methods keep static
    # Prefix field/method references - use a list of known panel members
    members = [
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
        "details", "ui", "statusButton", "LOG",
    ]
    # Don't rewrite declarations of those as fields inside method
    # Simple approach: for each member, replace word boundary when not after p. or .
    for m in sorted(members, key=len, reverse=True):
        body = re.sub(rf"(?<![\w.]){re.escape(m)}(?![\w])", f"p.{m}", body)
    # Fix p.p. doubles
    body = body.replace("p.p.", "p.")
    # Fix method calls that should stay on this controller or static on Settings*
    body = body.replace("p.SettingsHttpProbe", "SettingsHttpProbe")
    body = body.replace("p.SettingsModelCatalog", "SettingsModelCatalog")
    body = body.replace("p.SettingsSearchProviders", "SettingsSearchProviders")
    body = body.replace("p.SettingsChoicePanel", "SettingsChoicePanel")
    body = body.replace("p.SettingsGroup", "SettingsGroup")
    body = body.replace("p.SettingsSliders", "SettingsSliders")
    body = body.replace("p.SettingsConfig", "SettingsConfig")
    body = body.replace("p.SettingsPreferenceValues", "SettingsPreferenceValues")
    body = body.replace("p.SettingsBaseUrlPresets", "SettingsBaseUrlPresets")
    body = body.replace("p.SettingsConfigCoordinator", "SettingsConfigCoordinator")
    body = body.replace("p.FormacraftMod", "FormacraftMod")
    body = body.replace("p.CompletableFuture", "CompletableFuture")
    body = body.replace("p.Duration", "Duration")
    body = body.replace("p.URLEncoder", "URLEncoder")
    body = body.replace("p.StandardCharsets", "StandardCharsets")
    body = body.replace("p.URI", "URI")
    body = body.replace("p.URISyntaxException", "URISyntaxException")
    body = body.replace("p.ArrayList", "ArrayList")
    body = body.replace("p.List", "List")
    body = body.replace("p.Text", "Text")
    body = body.replace("p.ButtonWidget", "ButtonWidget")
    body = body.replace("p.Tooltip", "Tooltip")
    body = body.replace("p.HudTextInput", "HudTextInput")
    # Cross-calls within same controller stay as this. or unqualified — they were private methods
    # startDetectModel calling syncConnectionIdentity -> should be this.sync or stay if moved together
    # showToast is on panel
    body = re.sub(r"(?<![\w.])showToast\(", "p.showToast(", body)
    body = re.sub(r"(?<![\w.])saveSettings\(", "p.saveSettingsPublic(", body)
    body = re.sub(r"(?<![\w.])loadFromConfig\(", "p.loadFromConfigPublic(", body)
    body = re.sub(r"(?<![\w.])clamp01\(", "SettingsPanel.clamp01Public(", body)
    body = re.sub(r"(?<![\w.])reachToValue\(", "SettingsPanel.reachToValuePublic(", body)
    body = re.sub(r"(?<![\w.])valueToReach\(", "SettingsPanel.valueToReachPublic(", body)
    body = re.sub(r"(?<![\w.])afterTextEdit\(", "p.afterTextEditPublic(", body)
    # DetectResponse / identities are nested on SettingsPanel
    body = body.replace("new DetectResponse", "new SettingsPanel.DetectResponse")
    body = body.replace("new ConnectionIdentity", "new SettingsPanel.ConnectionIdentity")
    body = body.replace("new SearchIdentity", "new SettingsPanel.SearchIdentity")
    body = body.replace("ConnectionIdentity ", "SettingsPanel.ConnectionIdentity ")
    body = body.replace("SearchIdentity ", "SettingsPanel.SearchIdentity ")
    body = body.replace("BaseUrlPreset ", "SettingsPanel.BaseUrlPreset ")
    body = body.replace("new BaseUrlPreset", "new SettingsPanel.BaseUrlPreset")
    body = body.replace("List<BaseUrlPreset>", "List<SettingsPanel.BaseUrlPreset>")
    # Fix p.SettingsPanel.
    body = body.replace("p.SettingsPanel.", "SettingsPanel.")
    # Internal calls between moved methods: syncConnectionIdentity etc. should use this. not p.
    for local in [
        "syncConnectionIdentity", "currentConnectionIdentity", "syncSearchIdentity",
        "currentSearchIdentity", "sanitizeEndpoint", "readSearchValidateOk", "readSearchValidateMessage",
        "getDetectModelButtonText", "getLlmProviderButtonText", "getSearchProviderButtonText",
        "getDebugWarningsButtonText", "getShowHideText", "getBaseUrlPresetButtonText",
        "getSelectedBaseUrlPreset", "applyBaseUrlPreset", "blurInputs", "openModels",
        "openProviders", "openBaseUrlPresets", "openSearchProviders", "openResetGroups",
        "toggleHideKey", "toggleDebugWarnings", "initWidgets", "syncWidgetStateFromDraft",
        "syncBaseUrlPresetFromValue", "buildPresetsFromCatalog", "startDetectModel",
        "startValidateKey", "startValidateSearchKey",
    ]:
        body = body.replace(f"p.{local}(", f"{local}(")
    return body


probe_bodies = []
for a, b, body in probe_ranges:
    probe_bodies.append(rewrite_instance_body(body))

widget_bodies = []
for a, b, body in widget_ranges:
    widget_bodies.append(rewrite_instance_body(body))

(SETTINGS / "SettingsProbeController.java").write_text(
    PROBE_HEADER + "\n".join(probe_bodies) + "}\n", encoding="utf-8"
)
(SETTINGS / "SettingsWidgetAssembler.java").write_text(
    WIDGET_HEADER + "\n".join(widget_bodies) + "}\n", encoding="utf-8"
)

# Remove extracted methods from panel; leave one-line delegates
remove_set = set()
for a, b, _ in probe_ranges + widget_ranges:
    for ln in range(a, b + 1):
        remove_set.add(ln)

delegates = '''
    public final SettingsProbeController probes = new SettingsProbeController(this);
    public final SettingsWidgetAssembler widgets = new SettingsWidgetAssembler(this);

    void startValidateSearchKey() { probes.startValidateSearchKey(); }
    void startDetectModel() { probes.startDetectModel(); }
    void startValidateKey() { probes.startValidateKey(); }
    void syncConnectionIdentity() { probes.syncConnectionIdentity(); }
    void syncSearchIdentity() { probes.syncSearchIdentity(); }
    static String sanitizeEndpoint(String endpoint) { return SettingsProbeController.sanitizeEndpointStatic(endpoint); }

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

    public void showToast(String msg, boolean isError) { this.toast = msg; this.isToastError = isError; }
    public void loadFromConfigPublic() { loadFromConfig(); }
    public boolean saveSettingsPublic() { return saveSettings(); }
    public void afterTextEditPublic(HudTextInput input, String before) { afterTextEdit(input, before); }
    public static float clamp01Public(float v) { return clamp01(v); }
    public static double reachToValuePublic(int reach) { return reachToValue(reach); }
    public static int valueToReachPublic(double value) { return valueToReach(value); }
'''

new_lines = []
for i, l in enumerate(lines, 1):
    if i in remove_set:
        continue
    new_lines.append(l)

new_text = "".join(new_lines)
# Insert delegates after class opening / fields — before constructor
new_text = new_text.replace(
    "    public SettingsPanel() {",
    delegates + "\n    public SettingsPanel() {",
)
# Remove duplicate showToast if we left the old one
PANEL.write_text(new_text, encoding="utf-8")
print("panel lines", new_text.count("\n") + 1)
print("probe lines", (SETTINGS / "SettingsProbeController.java").read_text(encoding="utf-8").count("\n") + 1)
print("widget lines", (SETTINGS / "SettingsWidgetAssembler.java").read_text(encoding="utf-8").count("\n") + 1)
