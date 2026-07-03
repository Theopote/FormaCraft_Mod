"""Extract PromptAssembler sections into separate files."""
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


spatial_ranges = [
    (find_line("private static String spatialConstraints"), find_line("private static String structuredJsonTemplate")),
    (find_line("private static String zoningBlock"), find_line("private static String userIntent")),
]
spatial_body: list[str] = []
for start, end in spatial_ranges:
    spatial_body.extend(lines[start:end])

template_start = find_line("private static String structuredJsonTemplate")
template_end = find_line("// ========== RAG")
template_body = lines[template_start:template_end]

spatial_header = """package com.formacraft.ai.prompt;

import com.formacraft.FormacraftMod;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.common.terrain.TerrainPolicy;

/**
 * Dynamic spatial constraint blocks assembled from tool state and PromptContext.
 */
final class PromptSpatialSections {
    private PromptSpatialSections() {}
"""

template_header = """package com.formacraft.ai.prompt;

/**
 * Structured JSON template and user-intent blocks for the final prompt.
 */
final class PromptTemplateSections {
    private PromptTemplateSections() {}
"""

out_dir = root.parent
(out_dir / "PromptSpatialSections.java").write_text(
    spatial_header + "\n" + "\n".join(dedent_methods(spatial_body)) + "\n}\n",
    encoding="utf-8",
)
(out_dir / "PromptTemplateSections.java").write_text(
    template_header + "\n" + "\n".join(dedent_methods(template_body)) + "\n}\n",
    encoding="utf-8",
)
print(f"spatial: {len(spatial_body)} lines, template: {len(template_body)} lines")
