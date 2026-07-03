#!/usr/bin/env python3
"""Extract Phase 5e splits from ComponentTool and AssemblyMacroApplier."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)

def slice_lines(lines: list[str], start: int, end: int) -> str:
    return "".join(lines[start - 1 : end])

def write_file(path: Path, header: str, body: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(header + body, encoding="utf-8")
    print(f"wrote {path} ({len((header + body).splitlines())} lines)")

def extract_macro():
    src = ROOT / "src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroApplier.java"
    lines = read_lines(src)
    pkg = "package com.formacraft.server.assembly.macro;\n\n"
    imports = (
        "import com.formacraft.common.logging.FcaLog;\n"
        "import com.formacraft.server.assembly.validation.AssemblyValidationIssue;\n\n"
        "import java.util.ArrayList;\n"
        "import java.util.List;\n"
        "import java.util.Locale;\n"
        "import java.util.Map;\n\n"
    )

    support_header = pkg + imports + "/** Shared helpers for macro appliers. */\nfinal class AssemblyMacroSupport {\n    private static final FcaLog LOG = FcaLog.of(\"AssemblyMacroSupport\");\n\n    private AssemblyMacroSupport() {}\n\n"
    support_body = slice_lines(lines, 407, 421)  # clamp01, d
    support_body += slice_lines(lines, 696, 708)  # putPortIfAbsent, bool
    support_body += slice_lines(lines, 674, 695)  # makeRoller
    support_body += slice_lines(lines, 957, 965)  # uniqueId
    support_body += slice_lines(lines, 1312, 1357)  # helpers tail
    support_body = support_body.replace("private static", "static")
    support_body += "}\n"
    write_file(ROOT / "src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroSupport.java", support_header, support_body)

    style_header = pkg + imports + "/** Style/culture macro mapping. */\nfinal class AssemblyMacroStyleApplier {\n    private AssemblyMacroStyleApplier() {}\n\n"
    style_body = slice_lines(lines, 98, 405)
    style_body = style_body.replace("private static void applyStyleMacro", "static void apply")
    style_body = style_body.replace("clamp01(", "AssemblyMacroSupport.clamp01(")
    style_body = style_body.replace("d(", "AssemblyMacroSupport.d(")
    style_body = style_body.replace("safeMap(", "AssemblyMacroSupport.safeMap(")
    style_body = style_body.replace("str(", "AssemblyMacroSupport.str(")
    style_body = style_body.replace("warn(", "AssemblyMacroSupport.warn(")
    style_body = style_body.replace("i(", "AssemblyMacroSupport.i(")
    style_body = style_body.replace("bool(", "AssemblyMacroSupport.bool(")
    style_body = style_body.replace("uniqueId(", "AssemblyMacroSupport.uniqueId(")
    style_body += "}\n"
    write_file(ROOT / "src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroStyleApplier.java", style_header, style_body)

    inject_header = pkg + imports + "/** Injects bridge tower and subtract-hole components. */\nfinal class AssemblyMacroInjectApplier {\n    private AssemblyMacroInjectApplier() {}\n\n"
    inject_body = slice_lines(lines, 423, 672)  # bridge tower
    inject_body += slice_lines(lines, 710, 790)  # subtract holes
    inject_body = inject_body.replace("private static", "static")
    for fn in ["clamp01", "d", "safeMap", "str", "warn", "i", "bool", "uniqueId", "putPortIfAbsent", "makeRoller", "clamp", "intOrNull"]:
        inject_body = inject_body.replace(f"{fn}(", f"AssemblyMacroSupport.{fn}(")
    inject_body = inject_body.replace("applyBridgeTower", "static void applyBridgeTower")
    inject_body = inject_body.replace("static void static void", "static void")
    inject_body = inject_body.replace("static void applySubtractHoles", "static void applySubtractHoles")
    inject_body += "}\n"
    write_file(ROOT / "src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroInjectApplier.java", inject_header, inject_body)

    primary_header = pkg + imports + "/** Primary component macro transforms (shape, roof, vertical profile). */\nfinal class AssemblyMacroPrimaryApplier {\n    private AssemblyMacroPrimaryApplier() {}\n\n"
    primary_body = slice_lines(lines, 792, 955)  # vertical profile + Segment
    primary_body += slice_lines(lines, 967, 1310)  # shape through symmetry
    primary_body = primary_body.replace("private static", "static")
    primary_body = primary_body.replace("static class Segment", "private static class Segment")
    for fn in ["clamp01", "d", "safeMap", "str", "warn", "i", "bool", "uniqueId", "intOrNull", "clamp"]:
        primary_body = primary_body.replace(f"{fn}(", f"AssemblyMacroSupport.{fn}(")
    primary_body = primary_body.replace("LOG.", "AssemblyMacroSupport.LOG.")
    # rename entry points
    primary_body = primary_body.replace("static Map<String, Object> applyVerticalProfile", "static Map<String, Object> applyVerticalProfile")
    primary_body += "}\n"
    write_file(ROOT / "src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroPrimaryApplier.java", primary_header, primary_body)

    orch = '''package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P0 Macro parameter table applier.
 * <p>
 * Reads extra.assembly.macro and maps high-level "gene-like" sliders onto existing assembly knobs.
 */
public final class AssemblyMacroApplier {
    private AssemblyMacroApplier() {}

    public static AssemblyMacroApplyResult apply(Object assemblyObj) {
        List<AssemblyValidationIssue> issues = new ArrayList<>();
        if (!(assemblyObj instanceof Map<?, ?> root)) {
            return new AssemblyMacroApplyResult(assemblyObj, issues);
        }
        Map<String, Object> m = AssemblyMacroSupport.safeMap(root);
        if (m == null) return new AssemblyMacroApplyResult(assemblyObj, issues);

        Object macroObj = m.get("macro");
        if (!(macroObj instanceof Map<?, ?> mm)) {
            return new AssemblyMacroApplyResult(assemblyObj, issues);
        }
        Map<String, Object> macro = AssemblyMacroSupport.safeMap(mm);
        if (macro == null) return new AssemblyMacroApplyResult(m, issues);

        Map<String, Object> graph = null;
        Object graphObj = m.get("graph");
        if (graphObj instanceof Map<?, ?> gm) {
            graph = AssemblyMacroSupport.safeMap(gm);
        }
        Object compsObj = (graph != null) ? graph.get("components") : m.get("components");
        Object connsObj = (graph != null) ? graph.get("connections") : m.get("connections");

        String targetId = AssemblyMacroSupport.str(macro.get("primaryComponent"), AssemblyMacroSupport.str(macro.get("primaryComponentId"), null));
        if (targetId != null) targetId = targetId.trim();

        Map<String, Object> primary = null;
        if (compsObj instanceof List<?> comps) {
            for (Object it : comps) {
                if (!(it instanceof Map<?, ?> cm)) continue;
                Map<String, Object> c = AssemblyMacroSupport.safeMap(cm);
                if (c == null) continue;
                if (primary == null) primary = c;
                if (targetId != null && targetId.equals(AssemblyMacroSupport.str(c.get("id"), "").trim())) {
                    primary = c;
                    break;
                }
            }
        }

        if (primary != null) {
            primary = AssemblyMacroPrimaryApplier.applyVerticalProfile(m, graph, compsObj, primary, macro, issues);
        }

        if (primary != null) {
            AssemblyMacroPrimaryApplier.applyShapeType(primary, macro, issues);
            AssemblyMacroPrimaryApplier.applyHeightScale(primary, macro, issues);
            AssemblyMacroPrimaryApplier.applyRoofMacro(m, graph, compsObj, primary, macro, issues);
            AssemblyMacroPrimaryApplier.applyOpenness(primary, macro, issues);
        }

        if (connsObj instanceof List<?> conns) {
            AssemblyMacroPrimaryApplier.applySymmetryToConnections(conns, macro, issues);
        }

        AssemblyMacroStyleApplier.apply(m, graph, compsObj, connsObj, primary, macro, issues);
        AssemblyMacroInjectApplier.applyBridgeTower(m, graph, compsObj, macro, issues);
        AssemblyMacroInjectApplier.applySubtractHoles(m, graph, compsObj, primary, macro, issues);

        return new AssemblyMacroApplyResult(m, issues);
    }
}
'''
    (ROOT / "src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroApplier.java").write_text(orch, encoding="utf-8")
    print("wrote orchestrator AssemblyMacroApplier.java")

def extract_component_tool_json():
    src = ROOT / "src/main/java/com/formacraft/client/tool/ComponentTool.java"
    lines = read_lines(src)
    header = '''package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.ComponentPlacementAnalyzer;
import com.formacraft.common.component.placement.PlacementCaptureContext;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.FormacraftMod;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.block.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds component JSON from capture draft + world selection. */
final class ComponentToolJsonBuilder {
    private ComponentToolJsonBuilder() {}

'''
    body = slice_lines(lines, 1151, 1298)  # buildCurrentComponentJson body only
    body = body.replace("public String buildCurrentComponentJson(net.minecraft.client.MinecraftClient client, ComponentCaptureDraft draft)", "static String build(MinecraftClient client, ComponentToolState state, List<ComponentSocket> sockets, ComponentCaptureDraft draft)")
    body = body.replace("isAnchorAllowed(anchor, draft)", "ComponentToolCaptureSupport.isAnchorAllowed(state, anchor, draft)")
    body = body.replace("hasValidSelection(draft)", "ComponentToolCaptureSupport.hasValidSelection(draft)")
    body += "\n"
    body += slice_lines(lines, 1300, 1537)
    body = body.replace("private static", "static")
    body = body.replace("private ComponentDefinition.DirectionHints buildDirectionHints", "static ComponentDefinition.DirectionHints buildDirectionHints")
    body += "}\n"
    write_file(ROOT / "src/main/java/com/formacraft/client/tool/ComponentToolJsonBuilder.java", header, body)

def extract_component_tool_capture_support():
    src = ROOT / "src/main/java/com/formacraft/client/tool/ComponentTool.java"
    lines = read_lines(src)
    header = '''package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Selection and anchor validation for {@link ComponentTool}. */
final class ComponentToolCaptureSupport {
    private ComponentToolCaptureSupport() {}

'''
    body = slice_lines(lines, 803, 864)  # isAnchorValid through expanded selection - partial
    # We'll build manually from anchor methods
    body = ""
    methods = [
        (816, 831, "static boolean isAnchorAllowed(ComponentToolState state, BlockPos pos, ComponentCaptureDraft draft)"),
        (833, 840, "static boolean isInSelectionBlocks(ComponentToolState state, ComponentCaptureDraft draft, BlockPos pos)"),
        (842, 850, "static boolean isAnchorAdjacentToExplicitSelection(BlockPos pos, ComponentCaptureDraft draft)"),
        (852, 864, "static boolean isInsideExpandedSelection(BlockPos pos, int pad, ComponentCaptureDraft draft)"),
        (1098, 1111, "static boolean hasValidSelection(ComponentCaptureDraft draft)"),
        (1484, 1496, "static boolean isInsideSelection(BlockPos pos, ComponentCaptureDraft draft)"),
    ]
    for start, end, sig in methods:
        chunk = slice_lines(lines, start, end)
        chunk = chunk.split("{", 1)
        if len(chunk) == 2:
            body += sig + " {\n" + chunk[1]
        else:
            body += chunk
        if not body.endswith("\n"):
            body += "\n"
    # fix isInSelectionBlocks - was using effectiveDraft
    body = body.replace("ComponentCaptureDraft draft = effectiveDraft();", "ComponentCaptureDraft draft = draft;")
    body = body.replace("if (state.useLibrary) return true;", "if (state.useLibrary) return true;")
    body += "}\n"
    write_file(ROOT / "src/main/java/com/formacraft/client/tool/ComponentToolCaptureSupport.java", header, body)

if __name__ == "__main__":
    extract_macro()
    extract_component_tool_json()
    extract_component_tool_capture_support()
    print("done")
