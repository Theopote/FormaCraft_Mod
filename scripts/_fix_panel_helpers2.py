# -*- coding: utf-8 -*-
"""Surgical compile fixes after over-aggressive host qualification."""
from __future__ import annotations
import re
from pathlib import Path

# 1) BasePanel: keep drawCustomTooltip protected (subclasses override)
base_path = Path("src/main/java/com/formacraft/client/ui/panel/BasePanel.java")
base = base_path.read_text(encoding="utf-8")
base = base.replace(
    "public boolean drawCustomTooltip(",
    "protected boolean drawCustomTooltip(",
)
# keep other publicizations
base_path.write_text(base, encoding="utf-8")

# 2) Remove duplicate bridges from subclasses that conflict with BasePanel
BRIDGE_BLOCK = re.compile(
    r"[ \t]*public boolean superMouseClicked\(double x, double y, int button\) \{ return super\.mouseClicked\(x, y, button\); \}\r?\n"
    r"[ \t]*public boolean superMouseReleased\(double x, double y, int button\) \{ return super\.mouseReleased\(x, y, button\); \}\r?\n"
    r"[ \t]*public void drawContentBackgroundPublic\(net\.minecraft\.client\.gui\.DrawContext ctx\) \{ drawContentBackground\(ctx\); \}\r?\n"
    r"[ \t]*public void drawTooltipCompatPublic\(net\.minecraft\.client\.gui\.DrawContext ctx, java\.util\.List<net\.minecraft\.text\.Text> lines, int mx, int my\) \{ drawTooltipCompat\(ctx, lines, mx, my\); \}\r?\n"
    r"[ \t]*public net\.minecraft\.client\.MinecraftClient client\(\) \{ return client; \}\r?\n",
    re.M,
)
# lighter: remove only conflicting client()/drawTooltipCompatPublic/drawContentBackgroundPublic if BasePanel has them
for rel in [
    "src/main/java/com/formacraft/client/ui/panel/ToolPanel.java",
    "src/main/java/com/formacraft/client/ui/panel/ComponentLibraryPanel.java",
    "src/main/java/com/formacraft/client/ui/panel/ChatPanel.java",
    "src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java",
    "src/main/java/com/formacraft/client/ui/panel/ComponentCapturePanel.java",
]:
    p = Path(rel)
    if not p.exists():
        continue
    t = p.read_text(encoding="utf-8")
    # Keep superMouse* on panels that need them for helpers; remove only bridges that clash with BasePanel
    t2 = re.sub(
        r"[ \t]*public void drawContentBackgroundPublic\(net\.minecraft\.client\.gui\.DrawContext ctx\) \{ drawContentBackground\(ctx\); \}\r?\n",
        "",
        t,
    )
    t2 = re.sub(
        r"[ \t]*public void drawTooltipCompatPublic\(net\.minecraft\.client\.gui\.DrawContext ctx, java\.util\.List<net\.minecraft\.text\.Text> lines, int mx, int my\) \{ drawTooltipCompat\(ctx, lines, mx, my\); \}\r?\n",
        "",
        t2,
    )
    t2 = re.sub(
        r"[ \t]*public net\.minecraft\.client\.MinecraftClient client\(\) \{ return client; \}\r?\n",
        "",
        t2,
    )
    # ToolPanel shadows client field — BasePanel already has public client; remove subclass field if present and update refs... keep field for now but remove client() method only
    if t2 != t:
        p.write_text(t2, encoding="utf-8")
        print("stripped bridges", rel)

# 3) Fix host.meth(host, ...) and host.meth(host) in helpers
helper_dirs = [
    Path("src/main/java/com/formacraft/client/ui/panel"),
    Path("src/main/java/com/formacraft/client/ui/panel/tool"),
    Path("src/main/java/com/formacraft/client/ui/panel/library"),
]
for d in helper_dirs:
    for p in d.glob("*.java"):
        if p.name.endswith("Panel.java") and p.name != "BasePanel.java":
            # still fix hosts if needed
            pass
        t = p.read_text(encoding="utf-8")
        t2 = t
        # host.foo(host) -> host.foo()
        t2 = re.sub(r"host\.(\w+)\(host\)", r"host.\1()", t2)
        # host.foo(host, -> host.foo(
        t2 = re.sub(r"host\.(\w+)\(host,\s*", r"host.\1(", t2)
        # Class.foo(host, host, -> Class.foo(host,
        t2 = re.sub(r"(\w+)\(host,\s*host,\s*", r"\1(host, ", t2)
        # static ComponentLibraryPanel.typeSearchMatches(host, -> without host if static
        for static_m in (
            "typeSearchMatches",
            "matchScore",
            "displayName",
            "sortLabel",
            "getEntryComparator",
            "clickButton",
            "clamp01",
            "smoothstep",
        ):
            t2 = re.sub(
                rf"host\.{static_m}\(",
                f"FIXSTATIC_{static_m}(",
                t2,
            )
        # map FIXSTATIC back with correct owner
        for static_m in ("typeSearchMatches", "matchScore", "displayName", "sortLabel", "getEntryComparator", "clickButton"):
            t2 = t2.replace(f"FIXSTATIC_{static_m}(", f"ComponentLibraryPanel.{static_m}(")
        for static_m in ("clamp01", "smoothstep"):
            t2 = t2.replace(f"FIXSTATIC_{static_m}(", f"BasePanel.{static_m}(")
        # ToolPanel.clickButton is static too
        if "tool" in str(p).replace("\\", "/"):
            t2 = t2.replace("ComponentLibraryPanel.clickButton(", "ToolPanel.clickButton(")
            # but Library files should keep ComponentLibraryPanel
            if "library" in str(p).replace("\\", "/"):
                t2 = t2.replace("ToolPanel.clickButton(", "ComponentLibraryPanel.clickButton(")
        if "library" in str(p).replace("\\", "/"):
            t2 = t2.replace("ToolPanel.clickButton(", "ComponentLibraryPanel.clickButton(")
        # BasePanel same-package helpers: FormacraftUIState etc.
        if t2 != t:
            p.write_text(t2, encoding="utf-8")
            print("qualified", p)

# 4) Ensure BasePanel has working bridges (signature accepting List<?>)
base = base_path.read_text(encoding="utf-8")
if "drawTooltipCompatPublic" not in base:
    base = base.replace(
        "public abstract class BasePanel {",
        "public abstract class BasePanel {\n"
        "    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }\n"
        "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<? extends net.minecraft.text.Text> lines, int mx, int my) {\n"
        "        @SuppressWarnings(\"unchecked\") java.util.List<net.minecraft.text.Text> cast = (java.util.List<net.minecraft.text.Text>) (java.util.List<?>) lines;\n"
        "        drawTooltipCompat(ctx, cast, mx, my);\n"
        "    }\n"
        "    public net.minecraft.client.MinecraftClient client() { return client; }\n",
        1,
    )
    base_path.write_text(base, encoding="utf-8")

# 5) BasePanelChrome: sibling calls like host.drawCloseButton are fine; ensureToolbarWidgets(host) if bare
# Fix ensureToolbarWidgets(host) wrongly as host.ensureToolbarWidgets(host) already fixed by step 3

print("done")
