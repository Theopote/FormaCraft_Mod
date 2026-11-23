"""Wire restored SettingsPanel to existing SettingsProbeController + SettingsWidgetAssembler."""
from __future__ import annotations

import re
from pathlib import Path

PANEL = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
lines = PANEL.read_text(encoding="utf-8").splitlines(keepends=True)


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


REMOVE_SUBS = [
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

remove: set[int] = set()
for s in REMOVE_SUBS:
    a = find_line(s)
    b = method_end(a)
    # also drop a preceding lone @Override line if present
    if a > 1 and lines[a - 2].strip() == "@Override":
        remove.add(a - 1)
    remove.update(range(a, b + 1))
    print(f"drop {a}-{b} {s}")

kept = [l for i, l in enumerate(lines, 1) if i not in remove]
text = "".join(kept)

# Public nested records
text = text.replace("    private record ", "    public record ")

# Public fields: only exact field declaration patterns at class indent
field_patterns = [
    (r"(?m)^    private final MinecraftClient ", "    public final MinecraftClient "),
    (r"(?m)^    private final SettingsBackendSection ", "    public final SettingsBackendSection "),
    (r"(?m)^    private final SettingsChoicePanel ", "    public final SettingsChoicePanel "),
    (r"(?m)^    private final SettingsDetailsPanel ", "    public final SettingsDetailsPanel "),
    (r"(?m)^    private final SettingsUiBindings ", "    public final SettingsUiBindings "),
    (r"(?m)^    private final java\.util\.EnumMap", "    public final java.util.EnumMap"),
    (r"(?m)^    private final java\.util\.EnumSet", "    public final java.util.EnumSet"),
    (r"(?m)^    private final HudTextInput ", "    public final HudTextInput "),
    (r"(?m)^    private ButtonWidget ", "    public ButtonWidget "),
    (r"(?m)^    private long searchRevision", "    public long searchRevision"),
    (r"(?m)^    private SearchIdentity searchIdentity", "    public SearchIdentity searchIdentity"),
    (r"(?m)^    private long connectionRevision", "    public long connectionRevision"),
    (r"(?m)^    private ConnectionIdentity connectionIdentity", "    public ConnectionIdentity connectionIdentity"),
    (r"(?m)^    private boolean modelNeedsConfirmation", "    public boolean modelNeedsConfirmation"),
    (r"(?m)^    private boolean hideKey", "    public boolean hideKey"),
    (r"(?m)^    private String draftModel", "    public String draftModel"),
    (r"(?m)^    private String draftLlmProvider", "    public String draftLlmProvider"),
    (r"(?m)^    private String draftLlmBaseUrl", "    public String draftLlmBaseUrl"),
    (r"(?m)^    private boolean draftShowDebugWarnings", "    public boolean draftShowDebugWarnings"),
    (r"(?m)^    private String draftSearchProvider", "    public String draftSearchProvider"),
    (r"(?m)^    private float draftTemperature", "    public float draftTemperature"),
    (r"(?m)^    private int draftInteractionReach", "    public int draftInteractionReach"),
    (r"(?m)^    private String toast", "    public String toast"),
    (r"(?m)^    private boolean isToastError", "    public boolean isToastError"),
    (r"(?m)^    private SettingsSliders", "    public SettingsSliders"),
    (r"(?m)^    private int baseUrlPresetIndex", "    public int baseUrlPresetIndex"),
    (r"(?m)^    private final HttpClient ", "    public final HttpClient "),
    (r"(?m)^    private volatile boolean ", "    public volatile boolean "),
    (r"(?m)^    private int scrollY", "    public int scrollY"),
    (r"(?m)^    private int maxScrollY", "    public int maxScrollY"),
    (r"(?m)^    private List<String> availableModels", "    public List<String> availableModels"),
    (r"(?m)^    private long availableModelsUpdatedAtMs", "    public long availableModelsUpdatedAtMs"),
    (r"(?m)^    private static final FcaLog LOG", "    public static final FcaLog LOG"),
    (r"(?m)^    private static final List<BaseUrlPreset> BASE_URL_PRESETS = buildPresetsFromCatalog\(\);",
     "    public static final List<BaseUrlPreset> BASE_URL_PRESETS = SettingsWidgetAssembler.buildPresetsFromCatalog();"),
    (r"(?m)^    ButtonWidget searchProviderButton", "    public ButtonWidget searchProviderButton"),
    (r"(?m)^    ButtonWidget testSearchKeyButton", "    public ButtonWidget testSearchKeyButton"),
]
for a, b in field_patterns:
    text = re.sub(a, b, text)

# Helpers used by controllers
text = text.replace("    private static float clamp01", "    public static float clamp01")
text = text.replace("    private static int clampReach", "    public static int clampReach")
text = text.replace("    private static double reachToValue", "    public static double reachToValue")
text = text.replace("    private static int valueToReach", "    public static int valueToReach")
text = text.replace("    private void loadFromConfig()", "    public void loadFromConfig()")
text = text.replace("    private boolean saveSettings()", "    public boolean saveSettings()")
text = text.replace("    private void afterTextEdit(", "    public void afterTextEdit(")
# showToast(String) overload
text = text.replace("    private void showToast(String msg)", "    public void showToast(String msg)")

delegates = '''
    public final SettingsProbeController probes = new SettingsProbeController(this);
    public final SettingsWidgetAssembler widgets = new SettingsWidgetAssembler(this);

    void startValidateSearchKey() { probes.startValidateSearchKey(); }
    void startDetectModel() { probes.startDetectModel(); }
    void startValidateKey() { probes.startValidateKey(); }
    void syncConnectionIdentity() { probes.syncConnectionIdentity(); }
    void syncSearchIdentity() { probes.syncSearchIdentity(); }
    ConnectionIdentity currentConnectionIdentity() { return probes.currentConnectionIdentity(); }
    SearchIdentity currentSearchIdentity() { return probes.currentSearchIdentity(); }
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

if "SettingsProbeController probes" not in text:
    text = text.replace("    public SettingsPanel() {", delegates + "\n    public SettingsPanel() {")

PANEL.write_text(text, encoding="utf-8")
print("panel lines", text.count("\n") + 1)
