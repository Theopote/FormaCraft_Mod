# -*- coding: utf-8 -*-
"""Rebuild LibraryInteraction from HEAD method bodies (stub-extraction bug)."""
from __future__ import annotations
import re
import subprocess
from pathlib import Path

ns: dict = {}
exec(Path("scripts/_panel_chunk.py").read_text(encoding="utf-8"), ns)
find_method = ns["find_method"]
to_helper_method = ns["to_helper_method"]
collect_fields_consts = ns["collect_fields_consts"]

host_path = Path("src/main/java/com/formacraft/client/ui/panel/ComponentLibraryPanel.java")
head = subprocess.check_output(
    ["git", "show", "HEAD:src/main/java/com/formacraft/client/ui/panel/ComponentLibraryPanel.java"],
    text=True,
    encoding="utf-8",
)
host_text = host_path.read_text(encoding="utf-8")
fields, consts = collect_fields_consts(host_text)
hf, hc = collect_fields_consts(head)
fields = sorted(set(fields) | set(hf), key=len, reverse=True)
consts = sorted(set(consts) | set(hc), key=len, reverse=True)

host_methods = set(
    re.findall(r"(?:public|private|protected)\s+(?:static\s+)?(?:[\w.<>,?\[\]]+\s+)+(\w+)\s*\(", host_text)
)
host_methods |= set(
    re.findall(r"(?:public|private|protected)\s+(?:static\s+)?(?:[\w.<>,?\[\]]+\s+)+(\w+)\s*\(", head)
)
host_methods -= {"if", "for", "while", "switch", "ComponentLibraryPanel"}

methods = ["mouseClicked", "drawCustomTooltip", "buildComponentTooltip"]
sibling = set(methods)
parts = []
for name in methods:
    _s, _e, body = find_method(head, name)
    parts.append(
        to_helper_method(
            body, name, "ComponentLibraryPanel", fields, consts, sibling - {name}, host_methods
        )
    )

helper = (
    "package com.formacraft.client.ui.panel.library;\n\n"
    "import com.formacraft.client.ui.panel.ComponentLibraryPanel;\n"
    "import com.formacraft.client.ui.UiTheme;\n"
    "import net.minecraft.client.gui.DrawContext;\n"
    "import net.minecraft.client.gui.Click;\n"
    "import net.minecraft.client.gui.tooltip.Tooltip;\n"
    "import net.minecraft.client.gui.widget.ButtonWidget;\n"
    "import net.minecraft.client.input.MouseInput;\n"
    "import net.minecraft.text.Text;\n"
    "import com.formacraft.client.ui.widget.HudClickSupport;\n"
    "import com.formacraft.client.ui.widget.HudTextInput;\n"
    "import com.formacraft.client.tool.ComponentTool;\n"
    "import com.formacraft.client.tool.ComponentToolState;\n"
    "import com.formacraft.common.component.ComponentCatalog;\n"
    "import com.formacraft.client.component.ClientComponentCatalogState;\n"
    "import com.formacraft.client.component.ComponentLibraryUsage;\n"
    "import com.formacraft.client.ui.input.InputRouter;\n"
    "import com.formacraft.client.network.FormaCraftClientNetworking;\n"
    "import org.jetbrains.annotations.NotNull;\n"
    "import java.util.*;\n\n"
    "public final class LibraryInteraction {\n"
    "    private LibraryInteraction() {}\n"
    + "\n".join(parts)
    + "}\n"
)
helper = re.sub(r"(?<![\w.])GridItem(?!\w)", "ComponentLibraryPanel.GridItem", helper)
helper = helper.replace("ComponentLibraryPanel.ComponentLibraryPanel.", "ComponentLibraryPanel.")
helper = re.sub(r"host\.clickButton\(", "ComponentLibraryPanel.clickButton(", helper)
helper = re.sub(r"(?<![\w.])clickButton\(", "ComponentLibraryPanel.clickButton(", helper)
for z in ("matchScore", "typeSearchMatches", "displayName", "sortLabel", "getEntryComparator"):
    helper = re.sub(rf"host\.{z}\(", f"ComponentLibraryPanel.{z}(", helper)
# publicize needed fields on host if still private
for fld in (
    "communityMode",
    "gridValid",
    "searchBoundsValid",
    "scrollY",
    "maxScrollY",
    "scrollAreaTop",
    "searchX",
    "searchY",
    "searchW",
    "searchH",
):
    host_text = re.sub(rf"(?m)^([ \t]*)private (boolean|int) {fld}", rf"\1public \2 {fld}", host_text)

out = Path("src/main/java/com/formacraft/client/ui/panel/library/LibraryInteraction.java")
out.write_text(helper, encoding="utf-8")
host_path.write_text(host_text, encoding="utf-8")
print("ok LibraryInteraction", helper.count("\n") + 1, "this.", helper.count("this."), "host", host_text.count("\n") + 1)
