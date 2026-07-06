import re
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "src" / "test"
fixed = 0
for path in sorted(root.rglob("*.java")):
    if path.name == "NonClassicalEnrichmentGuardTest.java":
        continue
    text = path.read_text(encoding="utf-8")
    if "new LlmPlan(" not in text:
        continue

    def repl(match: re.Match[str]) -> str:
        return match.group(1).rstrip() + ",\n                null" + match.group(2)

    new_text, n = re.subn(r"(new LlmPlan\([\s\S]*?)(\n\s*\);)", repl, text)
    if n:
        path.write_text(new_text, encoding="utf-8")
        fixed += 1
        print(f"{path.relative_to(root.parents[1])}: {n}")

print(f"fixed_files={fixed}")
