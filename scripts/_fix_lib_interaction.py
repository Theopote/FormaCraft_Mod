# -*- coding: utf-8 -*-
from pathlib import Path
import re

panel = Path("src/main/java/com/formacraft/client/ui/panel/ComponentLibraryPanel.java")
pt = panel.read_text(encoding="utf-8")

# Publicize methods helpers need
for old, new in [
    ("private void ensureWidgets()", "public void ensureWidgets()"),
    ("private static boolean clickButton(", "public static boolean clickButton("),
    ("private void cycleCategory()", "public void cycleCategory()"),
    ("private List<Text> buildComponentTooltip(", "public List<Text> buildComponentTooltip("),
    ("private String formatAge(", "public String formatAge("),
    ("private static @NotNull Comparator", "public static @NotNull Comparator"),
    ("private static boolean typeSearchMatches", "public static boolean typeSearchMatches"),
    ("private int drawWrappedText(", "public int drawWrappedText("),
]:
    pt = pt.replace(old, new)

# Ensure bridges
if "client() { return client; }" not in pt:
    pt = pt.replace(
        "public final class ComponentLibraryPanel extends BasePanel {",
        "public final class ComponentLibraryPanel extends BasePanel {\n"
        "    public net.minecraft.client.MinecraftClient client() { return client; }\n"
        "    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }\n"
        "    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }\n"
        "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }\n",
    )

# Publicize remaining private fields
for f in [
    "communityMode",
    "gridValid",
    "searchX",
    "searchY",
    "searchW",
    "searchH",
    "searchBoundsValid",
    "scrollY",
    "maxScrollY",
    "scrollAreaTop",
]:
    pt = re.sub(rf"(?m)^(\s*)private boolean {f}\b", rf"\1public boolean {f}", pt)
    pt = re.sub(rf"(?m)^(\s*)private int {f}\b", rf"\1public int {f}", pt)

# GridItem public
pt = pt.replace(
    """public static final class GridItem {
        final ComponentCatalog.Entry entry;
        final int x, y, w, h;
        GridItem(ComponentCatalog.Entry entry, int x, int y, int w, int h) {
            this.entry = entry; this.x = x; this.y = y; this.w = w; this.h = h;
        }
        boolean hit(double mx, double my) {
            return mx >= x && mx <= (x + w) && my >= y && my <= (y + h);
        }
    }""",
    """public static final class GridItem {
        public final ComponentCatalog.Entry entry;
        public final int x, y, w, h;
        public GridItem(ComponentCatalog.Entry entry, int x, int y, int w, int h) {
            this.entry = entry; this.x = x; this.y = y; this.w = w; this.h = h;
        }
        public boolean hit(double mx, double my) {
            return mx >= x && mx <= (x + w) && my >= y && my <= (y + h);
        }
    }""",
)
panel.write_text(pt, encoding="utf-8")

fields = [
    "confirmImportButton",
    "restoreRecipeButton",
    "cancelExportButton",
    "replayRecipeButton",
    "cancelImportButton",
    "searchBoundsValid",
    "recipeSizeButtons",
    "nextSourceButton",
    "saveRecipeButton",
    "recipeViewButton",
    "prevSourceButton",
    "communityToggle",
    "lastImportPhase",
    "nextPageButton",
    "categoryButton",
    "validateButton",
    "prevPageButton",
    "refreshButton",
    "communityMode",
    "communityPane",
    "scrollAreaTop",
    "autoFixButton",
    "exportButton",
    "deleteButton",
    "importButton",
    "sourceButton",
    "lastClickId",
    "searchInput",
    "lastClickMs",
    "recipeDraft",
    "sortButton",
    "maxScrollY",
    "gridValid",
    "searchW",
    "scrollY",
    "searchH",
    "searchX",
    "searchY",
    "grid",
    "DOUBLE_CLICK_MS",
    "CONTENT_PADDING",
    "BUTTON_HEIGHT",
    "LABEL_OFFSET",
]
methods = [
    "isMouseOver",
    "ensureWidgets",
    "clickButton",
    "blurKeyboardFocus",
    "cycleCategory",
    "buildComponentTooltip",
    "formatAge",
    "drawTooltipCompatPublic",
    "selectedEntry",
    "requestLoadSelectedComponent",
]

for helper_name in ["LibraryInteraction", "LibraryLayoutRenderer", "LibraryWidgetBuilder"]:
    hp = Path(f"src/main/java/com/formacraft/client/ui/panel/library/{helper_name}.java")
    if not hp.exists():
        continue
    t = hp.read_text(encoding="utf-8")
    # imports
    if "ClientComponentCatalogState" not in t and helper_name != "LibraryInteraction":
        t = t.replace(
            "import com.formacraft.client.ui.panel.ComponentLibraryPanel;",
            "import com.formacraft.client.component.ClientComponentCatalogState;\n"
            "import com.formacraft.client.component.ComponentThumbnailCache;\n"
            "import com.formacraft.client.component.ComponentLibraryUsage;\n"
            "import com.formacraft.client.ui.panel.ComponentLibraryPanel;\n"
            "import com.formacraft.common.component.ComponentCategory;\n"
            "import com.formacraft.client.network.FormaCraftClientNetworking;\n"
            "import java.util.Locale;\n"
            "import java.util.Comparator;\n"
            "import java.util.ArrayList;",
        )
    for f in sorted(fields, key=len, reverse=True):
        t = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", t)
    for m in sorted(methods, key=len, reverse=True):
        t = re.sub(rf"(?<![\w.]){m}\(", f"host.{m}(", t)
    t = t.replace("host.host.", "host.")
    t = re.sub(r"\(host,\s*\)", "(host)", t)
    t = t.replace("host.client.", "host.client().")
    t = t.replace("host.client().()", "host.client()")
    t = t.replace("var host.entry", "var entry")
    # static helpers on panel
    t = t.replace("host.getEntryComparator(", "ComponentLibraryPanel.getEntryComparator(")
    t = t.replace("host.typeSearchMatches(", "ComponentLibraryPanel.typeSearchMatches(")
    t = t.replace("host.clickButton(", "ComponentLibraryPanel.clickButton(")
    t = t.replace("host.DOUBLE_CLICK_MS", "ComponentLibraryPanel.DOUBLE_CLICK_MS")
    t = t.replace("host.CONTENT_PADDING", "ComponentLibraryPanel.CONTENT_PADDING")
    t = t.replace("host.BUTTON_HEIGHT", "ComponentLibraryPanel.BUTTON_HEIGHT")
    t = t.replace("host.LABEL_OFFSET", "ComponentLibraryPanel.LABEL_OFFSET")
    # GridItem
    t = re.sub(r"(?<![\w.])GridItem(?!\w)", "ComponentLibraryPanel.GridItem", t)
    t = t.replace("ComponentLibraryPanel.ComponentLibraryPanel.", "ComponentLibraryPanel.")
    hp.write_text(t, encoding="utf-8")
    print("fixed", helper_name)

print("panel", pt.count("\n") + 1)
