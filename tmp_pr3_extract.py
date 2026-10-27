#!/usr/bin/env python3
"""PR3: extract AssemblyFacadeCompiler from ComponentPlanCompiler."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("src/main/java/com/formacraft/server/compiler")
CPC = ROOT / "ComponentPlanCompiler.java"
OUT = ROOT / "AssemblyFacadeCompiler.java"

src = CPC.read_text(encoding="utf-8")
assert "平屋顶" in src
lines = src.splitlines()

# Ranges relative to current CPC (1-based inclusive). Leave resolveMassOrigin (1528-1531).
RANGES = [
    (1345, 1527),  # generate + buildAssemblyMacroStyle
    (1532, 1726),  # style/palette/facing/filter/door/clamp01
]

chunks: list[str] = []
for start, end in RANGES:
    chunks.append("\n".join(lines[start - 1 : end]))
    chunks.append("")

body = "\n".join(chunks) + "\n"

# Visibility for entry point
body = body.replace(
    "private static List<BlockPatch> generateAssemblyFacadePatches",
    "static List<BlockPatch> generateAssemblyFacadePatches",
)

# resolveMassOrigin stays on CPC
body = re.sub(
    r"(?<!ComponentPlanCompiler\.)\bresolveMassOrigin\(",
    "ComponentPlanCompiler.resolveMassOrigin(",
    body,
)

# Logger message can stay mentioning ComponentPlanCompiler for continuity, or update — leave as-is.

header = '''package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.assembly.macro.AssemblyMacroApplier;
import com.formacraft.server.assembly.macro.AssemblyMacroApplyResult;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MetaAssembly-backed facade emission for components that opted into assembly facade.
 * <p>
 * Extracted from {@link ComponentPlanCompiler} (PR3). Marking/eligibility
 * ({@code markAssemblyFacade} / {@code shouldUseAssemblyFacade}) remains in
 * {@link ComponentSatelliteInference}.
 */
final class AssemblyFacadeCompiler {
    private AssemblyFacadeCompiler() {}

'''

OUT.write_text(header + body + "}\n", encoding="utf-8", newline="\n")
out_text = OUT.read_text(encoding="utf-8")
assert "generateAssemblyFacadePatches" in out_text
assert "屋檐" not in out_text  # shouldn't have wrongly pulled roof trim
assert "resolveAssemblyStyleId" in out_text
print("AssemblyFacadeCompiler lines", len(out_text.splitlines()))

# Patch CPC
new_lines = lines[:]
for start, end in sorted(RANGES, reverse=True):
    del new_lines[start - 1 : end]
    print(f"removed {start}-{end}, now {len(new_lines)}")

text = "\n".join(new_lines) + "\n"
text = text.replace(
    "generateAssemblyFacadePatches(",
    "AssemblyFacadeCompiler.generateAssemblyFacadePatches(",
)
# Avoid double-prefix if any
text = text.replace(
    "AssemblyFacadeCompiler.AssemblyFacadeCompiler.",
    "AssemblyFacadeCompiler.",
)

assert "平屋顶" in text
assert "static Vec3i resolveMassOrigin" in text
assert "private static List<BlockPatch> generateAssemblyFacadePatches" not in text
assert "AssemblyFacadeCompiler.generateAssemblyFacadePatches" in text

tmp = CPC.with_suffix(".java.tmp")
tmp.write_text(text, encoding="utf-8", newline="\n")
tmp.replace(CPC)
print("CPC lines", len(text.splitlines()))
print("done")
