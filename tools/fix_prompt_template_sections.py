"""Fix PromptTemplateSections - keep only template methods."""
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "src/main/java/com/formacraft/ai/prompt/PromptAssembler.java"
text = root.read_text(encoding="utf-8")
lines = text.splitlines()


def find_line(substr: str, start: int = 0) -> int:
    for i in range(start, len(lines)):
        if substr in lines[i]:
            return i
    raise ValueError(f"not found: {substr!r}")


def dedent_methods(body: list[str]) -> list[str]:
    out = []
    for line in body:
        if line.startswith("    private static"):
            out.append("    static" + line[len("    private static") :])
        else:
            out.append(line)
    return out


template_parts = [
    lines[find_line("private static String structuredJsonTemplate") : find_line("private static String zoningBlock")],
    lines[find_line("private static String escapeJsonString") : find_line("private static String zoningBlock")],
    lines[find_line("private static String userIntent") : find_line("// ========== RAG")],
]
template_body: list[str] = []
for part in template_parts:
    template_body.extend(part)

header = """package com.formacraft.ai.prompt;

/**
 * Structured JSON template and user-intent blocks for the final prompt.
 */
final class PromptTemplateSections {
    private PromptTemplateSections() {}
"""

out = root.parent / "PromptTemplateSections.java"
out.write_text(header + "\n" + "\n".join(dedent_methods(template_body)) + "\n}\n", encoding="utf-8")
print(f"template: {len(template_body)} lines")
