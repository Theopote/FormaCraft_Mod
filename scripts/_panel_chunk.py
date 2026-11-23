# -*- coding: utf-8 -*-
"""Extract methods from a HUD panel into a helper class.

Usage:
  python scripts/_panel_chunk.py HostClass FQCN HelperClass method [method...]

Example:
  python scripts/_panel_chunk.py ComponentLibraryPanel com.formacraft.client.ui.panel.ComponentLibraryPanel \\
      LibraryLayoutRenderer drawLibraryScrollable drawLibraryFixedHeader
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path("src/main/java")


def find_java(simple: str) -> Path:
    hits = list(ROOT.rglob(f"{simple}.java"))
    if not hits:
        raise SystemExit(f"not found: {simple}.java")
    return hits[0]


def find_method(text: str, name: str) -> tuple[int, int, str]:
    special = {
        "mouseClicked": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?public boolean mouseClicked\([^)]*\)[ \t]*\{",
        "mouseDragged": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?public boolean mouseDragged\([^)]*\)[ \t]*\{",
        "mouseReleased": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?public boolean mouseReleased\([^)]*\)[ \t]*\{",
        "mouseScrolled": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?public void mouseScrolled\([^)]*\)[ \t]*\{",
        "keyPressed": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?public void keyPressed\([^)]*\)[ \t]*\{",
        "charTyped": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?public void charTyped\([^)]*\)[ \t]*\{",
        "drawCustomTooltip": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?protected boolean drawCustomTooltip\([^)]*\)[ \t]*\{",
        "drawContents": r"^[ \t]*(?:@Override[ \t]*\n[ \t]*)?protected void drawContents\([^)]*\)[ \t]*\{",
        "render": r"^[ \t]*public void render\(DrawContext ctx\)[ \t]*\{",
    }
    if name in special:
        pat = special[name]
    else:
        pat = (
            rf"^[ \t]*(?:public|private|protected)[ \t]+(?:static[ \t]+)?"
            rf"(?:@\w+(?:\([^)]*\))?[ \t]+)*"
            rf"[A-Za-z0-9_.<>,?\[\]@ \t]+[ \t]+{re.escape(name)}[ \t]*\([^;\n]*\)[ \t]*\{{"
        )
    m = re.search(pat, text, re.M)
    if not m:
        raise SystemExit(f"not found: {name}")
    start = m.start()
    # include @Override on previous line
    line_start = text.rfind("\n", 0, start) + 1
    before = text[:line_start]
    prev_nl = before.rfind("\n")
    if prev_nl >= 0 and before[prev_nl + 1 :].strip() == "@Override":
        start = prev_nl + 1
    brace = text.find("{", m.end() - 1)
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1, text[start : i + 1]
        i += 1
    raise SystemExit("unbalanced " + name)


def collect_fields_consts(text: str) -> tuple[list[str], list[str]]:
    """Only scan the class header field region (before the first real method body)."""
    # Find class opening brace
    cm = re.search(r"\bclass\s+\w+[^{]*\{", text)
    if not cm:
        return [], []
    start = cm.end()
    # Stop at first method-like signature at class indent that opens a body and isn't a field
    stop = len(text)
    for m in re.finditer(
        r"(?m)^[ \t]{4}(?:public|private|protected).+\([^;]*\)[ \t]*\{",
        text[start:],
    ):
        stop = start + m.start()
        break
    region = text[start:stop]
    fields, consts = [], []
    for m in re.finditer(
        r"(?m)^[ \t]*(?:public|private|protected)[ \t]+(?:static[ \t]+)?(?:final[ \t]+)?"
        r"[\w.<>,?\[\]]+[ \t]+([A-Za-z_][A-Za-z0-9_]*)\s*[=;]",
        region,
    ):
        name = m.group(1)
        line = m.group(0)
        if "static" in line and "final" in line:
            consts.append(name)
        else:
            fields.append(name)
    # nested static class names etc. — also pick multi-decl: ButtonWidget a, b, c
    for m in re.finditer(
        r"(?m)^[ \t]*(?:public|private|protected)[ \t]+(?:static[ \t]+)?(?:final[ \t]+)?"
        r"[\w.<>,?\[\]]+[ \t]+(.+?);",
        region,
    ):
        decl = m.group(1)
        if "(" in decl:
            continue
        for part in decl.split(","):
            tok = part.strip().split("=")[0].strip().split()[-1]
            if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", tok):
                fields.append(tok)
    # BasePanel-ish geometry used as fields in subclasses
    for extra in ("client", "panelX", "panelY", "panelWidth", "panelHeight", "scrollY", "maxScrollY"):
        fields.append(extra)
    fields = sorted(set(fields), key=len, reverse=True)
    consts = sorted(set(consts), key=len, reverse=True)
    return fields, consts


def qualify(inner: str, host_simple: str, fields: list[str], consts: list[str], sibling: set[str], host_methods: set[str]) -> str:
    out = inner
    out = re.sub(r"(?<![\w.])this\.", "host.", out)
    for c in consts:
        out = re.sub(rf"(?<![\w.]){c}(?!\w)", f"{host_simple}.{c}", out)
    for f in fields:
        if f in ("host",):
            continue
        out = re.sub(rf"(?<![\w.]){f}(?!\w)", f"host.{f}", out)
    for meth in sorted(host_methods | sibling, key=len, reverse=True):
        if meth in sibling:
            out = re.sub(rf"(?<![\w.]){meth}\(", f"{meth}(host, ", out)
        else:
            out = re.sub(rf"(?<![\w.]){meth}\(", f"host.{meth}(", out)
    out = out.replace("host.host.", "host.")
    out = re.sub(r"(\w+)\(host, host,", r"\1(host,", out)
    out = re.sub(r"\(host,\s*\)", "(host)", out)
    out = out.replace("super.mouseClicked(", "host.superMouseClicked(")
    out = out.replace("super.mouseReleased(", "host.superMouseReleased(")
    out = out.replace("host.drawContentBackground(", "host.drawContentBackgroundPublic(")
    out = out.replace("host.drawTooltipCompat(", "host.drawTooltipCompatPublic(")
    # Prefer getters for protected geometry — skip for BasePanel (fields publicized; render assigns).
    if host_simple != "BasePanel":
        for g, getter in [
            ("panelX", "getPanelX()"),
            ("panelY", "getPanelY()"),
            ("panelWidth", "getPanelWidth()"),
            ("panelHeight", "getPanelHeight()"),
        ]:
            out = out.replace(f"host.{g}", f"host.{getter}")
    out = out.replace(f"{host_simple}.{host_simple}.", f"{host_simple}.")
    return out


def to_helper_method(body: str, name: str, host_simple: str, fields, consts, sibling, host_methods) -> str:
    brace = body.find("{")
    header = body[:brace].rstrip()
    # drop @Override from helper
    header = re.sub(r"(?m)^[ \t]*@Override[ \t]*\n", "", header)
    pm = re.search(r"\(([^)]*)\)\s*$", header)
    params = pm.group(1).strip() if pm else ""
    is_static = re.search(r"\bstatic\b", header) is not None
    ret_m = re.search(
        r"(?:public|private|protected)\s+(?:static\s+)?(?:@\w+(?:\([^)]*\))?\s+)*(.+?)\s+"
        + re.escape(name)
        + r"\s*\(",
        header,
    )
    ret = ret_m.group(1).strip() if ret_m else "void"
    inner = body[brace + 1 : -1]
    inner = qualify(inner, host_simple, fields, consts, sibling, host_methods)
    if is_static and not any(re.search(rf"(?<![\w.]){f}(?!\w)", body) for f in fields):
        # keep pure static without host if no field refs — still inject host if sibling calls need it
        if name in sibling and any(s != name for s in sibling):
            sig = f"public static {ret} {name}({host_simple} host, {params})" if params else f"public static {ret} {name}({host_simple} host)"
        else:
            sig = f"public static {ret} {name}({params})"
            # undo host injection for pure static body if we wrongly added
            inner = re.sub(rf"{re.escape(name)}\(host,\s*", f"{name}(", inner)
    else:
        sig = f"public static {ret} {name}({host_simple} host, {params})" if params else f"public static {ret} {name}({host_simple} host)"
    # clean empty host param artifacts
    inner = re.sub(r"\(host,\s*\)", "(host)", inner)
    return f"    {sig} {{\n{inner}\n    }}\n"


def delegate_stub(name: str, body: str, helper_fq: str, helper_simple: str) -> str:
    brace = body.find("{")
    header = body[:brace].rstrip()
    pm = re.search(r"\(([^)]*)\)\s*$", header)
    params = pm.group(1).strip() if pm else ""
    args = [p.split()[-1] for p in params.split(",") if p.strip()] if params else []
    is_static = re.search(r"\bstatic\b", header) is not None
    ret_m = re.search(
        r"(?:public|private|protected)\s+(?:static\s+)?(?:@\w+(?:\([^)]*\))?\s+)*(.+?)\s+"
        + re.escape(name)
        + r"\s*\(",
        header,
    )
    ret = ret_m.group(1).strip() if ret_m else "void"
    call_args = ", ".join(args)
    fq = f"{helper_fq}.{name}"
    # Heuristic: static helpers that don't need host keep no-this form only if original static and no this
    needs_host = not is_static or "this." in body or re.search(r"\b(client|panelX|messages)\b", body)
    if needs_host:
        call = f"{fq}(this, {call_args})" if call_args else f"{fq}(this)"
    else:
        call = f"{fq}({call_args})"
    if ret == "void":
        return f"{header} {{ {call}; }}"
    return f"{header} {{ return {call}; }}"


def publicize_common(text: str, host_simple: str) -> str:
    if "superMouseClicked" not in text:
        text = re.sub(
            rf"(public class {re.escape(host_simple)}\b[^{{]*\{{)",
            r"\1\n"
            "    public boolean superMouseClicked(double x, double y, int button) { return super.mouseClicked(x, y, button); }\n"
            "    public boolean superMouseReleased(double x, double y, int button) { return super.mouseReleased(x, y, button); }\n"
            "    public void drawContentBackgroundPublic(net.minecraft.client.gui.DrawContext ctx) { drawContentBackground(ctx); }\n"
            "    public void drawTooltipCompatPublic(net.minecraft.client.gui.DrawContext ctx, java.util.List<net.minecraft.text.Text> lines, int mx, int my) { drawTooltipCompat(ctx, lines, mx, my); }\n"
            "    public net.minecraft.client.MinecraftClient client() { return client; }\n",
            text,
            count=1,
        )
    # publicize private instance fields and static final ints commonly needed
    text = re.sub(r"(?m)^([ \t]*)private (static final )", r"\1public \2", text)
    text = re.sub(r"(?m)^([ \t]*)private final ", r"\1public final ", text)
    text = re.sub(r"(?m)^([ \t]*)private (?!class |interface |enum |record |static void|void |boolean |int |[^;=\(]*\()", r"\1public ", text)
    return text


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(2)
    host_simple = sys.argv[1]
    host_fq = sys.argv[2]
    helper_simple = sys.argv[3]
    methods = sys.argv[4:]
    host_path = find_java(host_simple)
    text = host_path.read_text(encoding="utf-8")
    text = publicize_common(text, host_simple)
    fields, consts = collect_fields_consts(text)

    # host methods = all method names in file (approx)
    host_methods = set(re.findall(r"(?:public|private|protected)\s+(?:static\s+)?(?:[\w.<>,?\[\]]+\s+)+(\w+)\s*\(", text))
    host_methods -= {"if", "for", "while", "switch", host_simple}
    sibling = set(methods)

    parts = []
    spans = []
    for name in methods:
        s, e, body = find_method(text, name)
        parts.append(to_helper_method(body, name, host_simple, fields, consts, sibling - {name}, host_methods))
        spans.append((s, e, name, body))

    pkg = ".".join(host_fq.split(".")[:-1])
    # put helpers in subpackage if name suggests, else same package
    if helper_simple.startswith("Library"):
        pkg = pkg + ".library"
        out_dir = host_path.parent / "library"
    elif helper_simple.startswith("Tool") and host_simple == "ToolPanel":
        pkg = pkg + ".tool"
        out_dir = host_path.parent / "tool"
    elif helper_simple.startswith("Base") and host_simple == "BasePanel":
        pkg = pkg  # same package package-private OK — still public for simplicity
        out_dir = host_path.parent
    else:
        out_dir = host_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    helper_path = out_dir / f"{helper_simple}.java"
    helper_fq = f"{pkg}.{helper_simple}"

    imports = [
        f"import {host_fq};",
        "import com.formacraft.client.ui.UiTheme;",
        "import net.minecraft.client.gui.DrawContext;",
        "import net.minecraft.client.gui.Click;",
        "import net.minecraft.client.gui.tooltip.Tooltip;",
        "import net.minecraft.client.gui.widget.ButtonWidget;",
        "import net.minecraft.client.input.MouseInput;",
        "import net.minecraft.text.Text;",
        "import com.formacraft.client.ui.widget.HudClickSupport;",
        "import com.formacraft.client.ui.widget.HudTextInput;",
        "import com.formacraft.client.tool.ComponentTool;",
        "import com.formacraft.client.tool.ComponentToolState;",
        "import com.formacraft.common.component.ComponentCatalog;",
        "import com.formacraft.client.ui.input.InputRouter;",
        "import org.jetbrains.annotations.NotNull;",
        "import java.util.*;",
    ]
    helper = (
        f"package {pkg};\n\n"
        + "\n".join(imports)
        + f"\n\npublic final class {helper_simple} {{\n"
        f"    private {helper_simple}() {{}}\n"
        + "\n".join(parts)
        + "}\n"
    )
    helper = re.sub(r"\(host,\s*\)", "(host)", helper)
    helper_path.write_text(helper, encoding="utf-8")

    for s, e, name, body in sorted(spans, key=lambda x: -x[0]):
        text = text[:s] + delegate_stub(name, body, helper_fq, helper_simple) + text[e:]
    host_path.write_text(text, encoding="utf-8")
    print("ok", helper_simple, methods, "host", text.count("\n") + 1, "helper", helper.count("\n") + 1)


if __name__ == "__main__":
    main()
