from pathlib import Path
import re

p = Path("src/main/java/com/formacraft/client/ui/panel/SettingsPanel.java")
t = p.read_text(encoding="utf-8")

pat = re.compile(
    r"    @Override\n"
    r"    public (?P<ret>[\w.<>,\s\[\]]+?) (?P<name>\w+)\((?P<params>[^)]*)\) \{\n"
    r"        (?P<body>[^\n]+)\n"
    r"    \}\n",
    re.M,
)


def repl(m: re.Match[str]) -> str:
    return (
        f"    @Override public {m.group('ret').strip()} {m.group('name')}({m.group('params')}) "
        f"{{ {m.group('body').strip()} }}\n"
    )


t2, n = pat.subn(repl, t)
print("compacted", n)
t2 = re.sub(r"\n{3,}", "\n\n", t2)

# Drop unused imports after extract
for unused in [
    "import com.formacraft.FormacraftMod;\n",
    "import java.net.URI;\n",
    "import java.net.URISyntaxException;\n",
    "import java.net.URLEncoder;\n",
    "import java.nio.charset.StandardCharsets;\n",
    "import java.util.concurrent.CompletableFuture;\n",
    "import net.minecraft.client.gui.tooltip.Tooltip;\n",
]:
    if unused in t2 and unused.split()[-1].rstrip(";\n") not in t2.replace(unused, ""):
        # crude: remove if symbol not used elsewhere
        sym = unused.strip().split(".")[-1].rstrip(";")
        body = t2.split("{", 1)[1] if "{" in t2 else t2
        if sym not in body:
            t2 = t2.replace(unused, "")

p.write_text(t2, encoding="utf-8")
print("lines", t2.count("\n") + 1)
