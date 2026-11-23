from pathlib import Path
import re

p = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
t = p.read_text(encoding="utf-8")

# Keep probes/widgets fields; remove one-line wrappers and rewrite call sites.
wrappers = {
    "startValidateSearchKey": "probes.startValidateSearchKey",
    "startDetectModel": "probes.startDetectModel",
    "startValidateKey": "probes.startValidateKey",
    "syncConnectionIdentity": "probes.syncConnectionIdentity",
    "syncSearchIdentity": "probes.syncSearchIdentity",
    "currentConnectionIdentity": "probes.currentConnectionIdentity",
    "currentSearchIdentity": "probes.currentSearchIdentity",
    "sanitizeEndpoint": "SettingsProbeController.sanitizeEndpoint",
    "initWidgets": "widgets.initWidgets",
    "openResetGroups": "widgets.openResetGroups",
    "openModels": "widgets.openModels",
    "openProviders": "widgets.openProviders",
    "openBaseUrlPresets": "widgets.openBaseUrlPresets",
    "openSearchProviders": "widgets.openSearchProviders",
    "toggleHideKey": "widgets.toggleHideKey",
    "getShowHideText": "widgets.getShowHideText",
    "getDebugWarningsButtonText": "widgets.getDebugWarningsButtonText",
    "toggleDebugWarnings": "widgets.toggleDebugWarnings",
    "getDetectModelButtonText": "widgets.getDetectModelButtonText",
    "getLlmProviderButtonText": "widgets.getLlmProviderButtonText",
    "getSearchProviderButtonText": "widgets.getSearchProviderButtonText",
    "getBaseUrlPresetButtonText": "widgets.getBaseUrlPresetButtonText",
    "blurInputs": "widgets.blurInputs",
}

# Remove wrapper method lines (one-liners and @Override sync* that only delegate)
t = re.sub(
    r"\n    void startValidateSearchKey\(\) \{ probes\.startValidateSearchKey\(\); \}"
    r"\n    void startDetectModel\(\) \{ probes\.startDetectModel\(\); \}"
    r"\n    void startValidateKey\(\) \{ probes\.startValidateKey\(\); \}"
    r"\n    void syncConnectionIdentity\(\) \{ probes\.syncConnectionIdentity\(\); \}"
    r"\n    void syncSearchIdentity\(\) \{ probes\.syncSearchIdentity\(\); \}"
    r"\n    ConnectionIdentity currentConnectionIdentity\(\) \{ return probes\.currentConnectionIdentity\(\); \}"
    r"\n    SearchIdentity currentSearchIdentity\(\) \{ return probes\.currentSearchIdentity\(\); \}"
    r"\n    static String sanitizeEndpoint\(String endpoint\) \{ return SettingsProbeController\.sanitizeEndpoint\(endpoint\); \}"
    r"\n\n    void initWidgets\(\) \{ widgets\.initWidgets\(\); \}"
    r"\n    @Override public void syncWidgetStateFromDraft\(\) \{ widgets\.syncWidgetStateFromDraft\(\); \}"
    r"\n    void openResetGroups\(\) \{ widgets\.openResetGroups\(\); \}"
    r"\n    void openModels\(\) \{ widgets\.openModels\(\); \}"
    r"\n    void openProviders\(\) \{ widgets\.openProviders\(\); \}"
    r"\n    void openBaseUrlPresets\(\) \{ widgets\.openBaseUrlPresets\(\); \}"
    r"\n    void openSearchProviders\(\) \{ widgets\.openSearchProviders\(\); \}"
    r"\n    void toggleHideKey\(\) \{ widgets\.toggleHideKey\(\); \}"
    r"\n    Text getShowHideText\(\) \{ return widgets\.getShowHideText\(\); \}"
    r"\n    Text getDebugWarningsButtonText\(\) \{ return widgets\.getDebugWarningsButtonText\(\); \}"
    r"\n    void toggleDebugWarnings\(\) \{ widgets\.toggleDebugWarnings\(\); \}"
    r"\n    Text getDetectModelButtonText\(\) \{ return widgets\.getDetectModelButtonText\(\); \}"
    r"\n    Text getLlmProviderButtonText\(\) \{ return widgets\.getLlmProviderButtonText\(\); \}"
    r"\n    Text getSearchProviderButtonText\(\) \{ return widgets\.getSearchProviderButtonText\(\); \}"
    r"\n    Text getBaseUrlPresetButtonText\(\) \{ return widgets\.getBaseUrlPresetButtonText\(\); \}"
    r"\n    @Override public void syncBaseUrlPresetFromValue\(String baseUrl\) \{ widgets\.syncBaseUrlPresetFromValue\(baseUrl\); \}"
    r"\n    void blurInputs\(\) \{ widgets\.blurInputs\(\); \}\n",
    "\n    @Override public void syncWidgetStateFromDraft() { widgets.syncWidgetStateFromDraft(); }\n"
    "    @Override public void syncBaseUrlPresetFromValue(String baseUrl) { widgets.syncBaseUrlPresetFromValue(baseUrl); }\n",
    t,
    count=1,
)

for name, target in wrappers.items():
    # Avoid double-prefixing already qualified calls
    t = re.sub(rf"(?<![\w.]){name}\(", f"{target}(", t)

# Restore field decls that may have been mangled: probes.initWidgets etc on field init? unlikely
# Fix ctor: widgets.initWidgets
# Fix syncWidgetStateFromDraft double: widgets.widgets.
t = t.replace("widgets.widgets.", "widgets.")
t = t.replace("probes.probes.", "probes.")
t = t.replace("SettingsProbeController.SettingsProbeController.", "SettingsProbeController.")

# Move nested records to settings as top-level to shrink panel? skip for now — trim blanks
t = re.sub(r"\n{3,}", "\n\n", t)

# Drop unused imports
for imp in [
    "import com.formacraft.FormacraftMod;\n",
    "import java.net.URI;\n",
    "import java.net.URISyntaxException;\n",
    "import java.net.URLEncoder;\n",
    "import java.nio.charset.StandardCharsets;\n",
    "import java.util.concurrent.CompletableFuture;\n",
    "import net.minecraft.client.gui.tooltip.Tooltip;\n",
    "import java.time.Duration;\n",
]:
    sym = imp.strip().rsplit(".", 1)[-1].rstrip(";")
    # check usage outside import section
    body = t.split("public class SettingsPanel", 1)[-1]
    if sym not in body and imp in t:
        t = t.replace(imp, "")

p.write_text(t, encoding="utf-8")
print("lines", t.count("\n") + 1)
