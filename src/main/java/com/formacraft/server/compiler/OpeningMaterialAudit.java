package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Final-patch diagnostics for recorded windows with explicit materials and door openings.
 * <p>
 * Hard vs soft:
 * <ul>
 *   <li>Hard — user/plan authored window material or explicit door constraint; mismatch/unresolved → capability gap</li>
 *   <li>Soft — autofill door openings without explicit materials; advisory only</li>
 * </ul>
 */
final class OpeningMaterialAudit {
    record Expectation(
            String componentId,
            GeneratedSurfaceCapture.Role role,
            String blockId,
            boolean hardConstraint
    ) {
        /** Soft by default — use 4-arg ctor when constraint_source is USER_EXPLICIT. */
        Expectation(String componentId, GeneratedSurfaceCapture.Role role, String blockId) {
            this(componentId, role, blockId, false);
        }
    }

    record Issue(String componentId, String reason, BlockPos position, String expected, String actual) {}

    record Result(
            String status,
            int checkedWindows,
            int checkedDoorCells,
            int unresolvedCells,
            int mismatchCells,
            int hardFailCells,
            Issue firstIssue,
            Issue firstHardIssue
    ) {
        boolean isMatched() {
            return "matched".equals(status);
        }
    }

    static void capture(Map<BlockPos, Expectation> expectations, Component c,
                        List<GeneratedSurfaceCapture.Cell> cells, BlockPos offset) {
        String id = c.params() == null ? c.componentType()
                : String.valueOf(c.params().getOrDefault("component_id", c.componentType()));
        String material = explicitWindowMaterial(c);
        boolean userExplicitOpening = isUserExplicitOpening(c);
        boolean userExplicitGlass = isUserExplicitWindowMaterial(c);
        for (var cell : cells) {
            var pos = cell.position().add(offset);
            if (cell.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING) {
                // Soft by default — LLM style frame_block / inferred door_width must not hard-fail.
                expectations.put(pos, new Expectation(id, cell.role(), material, userExplicitOpening));
            } else if (cell.role() == GeneratedSurfaceCapture.Role.WINDOW && material != null) {
                expectations.put(pos, new Expectation(id, cell.role(), material, userExplicitGlass));
            } else {
                expectations.remove(pos); // Later declared roles supersede earlier decisions.
            }
        }
    }

    static Result inspect(Map<BlockPos, Expectation> expectations, List<BlockPatch> patches) {
        var finals = new HashMap<BlockPos, BlockPatch>();
        for (var p : patches) if (p != null) finals.put(new BlockPos(p.dx(), p.dy(), p.dz()), p);
        int windows = 0, doors = 0, unresolved = 0, mismatch = 0, hardFail = 0;
        Issue first = null;
        Issue firstHard = null;
        for (var entry : expectations.entrySet()) {
            var expected = entry.getValue();
            var patch = finals.get(entry.getKey());
            if (patch == null) {
                unresolved++;
                if (expected.hardConstraint()) {
                    hardFail++;
                    if (firstHard == null) {
                        firstHard = new Issue(expected.componentId(), "opening_unresolved",
                                entry.getKey(),
                                expected.role() == GeneratedSurfaceCapture.Role.WINDOW
                                        ? expected.blockId() : "air_or_door",
                                "missing");
                    }
                }
                continue;
            } // No world read: unknown is not a verified opening.
            String actual = GeneratedSurfaceCapture.occupied(patch) ? blockId(patch.targetBlock()) : "minecraft:air";
            String reason = null;
            if (expected.role() == GeneratedSurfaceCapture.Role.WINDOW) {
                windows++;
                if (!Objects.equals(expected.blockId(), actual)) reason = "window_material_changed";
            } else {
                doors++;
                if (!"minecraft:air".equals(actual) && !actual.endsWith("_door")) reason = "door_opening_blocked";
            }
            if (reason != null) {
                mismatch++;
                Issue issue = new Issue(expected.componentId(), reason, entry.getKey(),
                        expected.role() == GeneratedSurfaceCapture.Role.WINDOW ? expected.blockId() : "air_or_door",
                        actual);
                if (first == null) first = issue;
                if (expected.hardConstraint()) {
                    hardFail++;
                    if (firstHard == null) firstHard = issue;
                }
            }
        }
        String status = mismatch > 0 ? "mismatch"
                : unresolved > 0 || windows + doors == 0 ? "not_assessed" : "matched";
        return new Result(status, windows, doors, unresolved, mismatch, hardFail, first, firstHard);
    }

    /**
     * Hard capability gap only for authored window/door constraints.
     * Soft autofill door failures stay warnings via {@link #emitSoftWarnings}.
     */
    static Optional<CapabilityGap> toCapabilityGap(Result result) {
        if (result == null || result.hardFailCells() <= 0) return Optional.empty();
        Issue issue = result.firstHardIssue() != null ? result.firstHardIssue() : result.firstIssue();
        boolean unresolved = issue != null && "opening_unresolved".equals(issue.reason());
        String code = unresolved ? "E_OPENING_UNRESOLVED" : "E_OPENING_MATERIAL_MISMATCH";
        String detail = issue == null ? "n/a"
                : issue.reason() + " at " + issue.position().toShortString()
                + " expected=" + issue.expected() + " actual=" + issue.actual()
                + " component=" + issue.componentId();
        return Optional.of(new CapabilityGap(
                code,
                unresolved
                        ? "用户明确的门窗约束未落地：" + detail
                        : "用户明确的门窗材质/开口被覆盖：" + detail,
                "FACADE_WINDOWS",
                List.of("Preserve authored door/window materials; do not overwrite with residential autofill.")));
    }

    static void emitSoftWarnings(Result result) {
        if (result == null || result.isMatched()) return;
        // Soft path: failures that are not hard constraints, or residual soft noise alongside hard gaps.
        int softMismatch = Math.max(0, result.mismatchCells() - result.hardFailCells());
        // hardFail counts unresolved hard cells too; soft unresolved ≈ total unresolved when no hardFail on those.
        if (result.hardFailCells() > 0 && softMismatch <= 0 && result.unresolvedCells() == 0) {
            return; // fully covered by capability gap
        }
        if (result.mismatchCells() > 0 && result.hardFailCells() == 0) {
            Issue issue = result.firstIssue();
            String detail = issue == null ? "n/a"
                    : issue.reason() + " at " + issue.position().toShortString()
                    + " expected=" + issue.expected() + " actual=" + issue.actual()
                    + " component=" + issue.componentId();
            AssemblyCompileDiagnostics.addSoftWarning(
                    "E_OPENING_MATERIAL_MISMATCH",
                    "门窗材质偏差（不阻断生成）：mismatchCells=" + result.mismatchCells() + "; " + detail,
                    "FACADE_WINDOWS",
                    issue == null ? "" : issue.componentId());
        } else if (result.unresolvedCells() > 0 && result.hardFailCells() == 0) {
            AssemblyCompileDiagnostics.addSoftWarning(
                    "E_OPENING_UNRESOLVED",
                    "门窗契约未完全解析（不阻断生成）：unresolved=" + result.unresolvedCells()
                            + " windows=" + result.checkedWindows()
                            + " doors=" + result.checkedDoorCells(),
                    "FACADE_WINDOWS",
                    "");
        }
    }

    /**
     * Hard only when constraint provenance is user-authored — not LLM style defaults.
     * Bare {@code door_width} / {@code glass_block} presence is insufficient; prefer
     * {@code constraint_source} stamped from request_intent by {@link OpeningConstraintProvenance}.
     */
    static boolean isUserExplicitOpening(Component c) {
        if (c == null || c.params() == null) return false;
        Object src = c.params().get("constraint_source");
        if (src == null) src = c.params().get("constraintSource");
        if (src != null) {
            String s = String.valueOf(src).trim().toUpperCase(Locale.ROOT);
            if ("USER_EXPLICIT".equals(s) || "USER".equals(s) || "PLAYER".equals(s)) return true;
            if ("LLM_INFERRED".equals(s) || "STYLE_DEFAULT".equals(s) || "AUTO_REPAIR".equals(s)) {
                return false;
            }
        }
        return Boolean.TRUE.equals(c.params().get("door_explicit"))
                || Boolean.TRUE.equals(c.params().get("window_explicit"))
                || Boolean.TRUE.equals(c.params().get("opening_explicit"))
                || Boolean.TRUE.equals(c.params().get("material_explicit"))
                || Boolean.TRUE.equals(c.params().get("glass_explicit"));
    }

    /**
     * Window glazing is hard when field-level material provenance says so — not merely because
     * {@code glass_block} is filled, and not blocked by a leftover component-level
     * {@code LLM_INFERRED} stamp (material must not require whole-component USER_EXPLICIT).
     */
    static boolean isUserExplicitWindowMaterial(Component c) {
        if (c == null || c.params() == null) return false;
        if (explicitWindowMaterial(c) == null) return false;
        Object sources = c.params().get("constraint_sources");
        if (sources instanceof Map<?, ?> map) {
            Object glass = map.get("glass_block");
            if (glass != null) {
                String s = String.valueOf(glass).trim();
                if ("USER_EXPLICIT".equalsIgnoreCase(s) || "USER".equalsIgnoreCase(s)
                        || "PLAYER".equalsIgnoreCase(s)) {
                    return true;
                }
            }
        }
        if (Boolean.TRUE.equals(c.params().get("window_material_explicit"))) {
            return true;
        }
        return isUserExplicitOpening(c);
    }

    private static String explicitWindowMaterial(Component c) {
        if (c.params() == null) return null;
        if ("FACADE_WINDOWS".equalsIgnoreCase(c.componentType())) {
            String lattice = com.formacraft.common.generation.component.util.WoodLatticeMaterial.resolve(c.params());
            if (lattice != null) return lattice;
        }
        for (String key : List.of("glass_block", "glazing_block", "glass_material", "window_block")) {
            Object value = c.params().get(key);
            if (value != null && !value.toString().isBlank()) return blockId(value.toString());
        }
        if ("FACADE_WINDOWS".equalsIgnoreCase(c.componentType())) {
            for (String key : List.of("block", "material")) {
                Object value = c.params().get(key);
                if (value != null && (value.toString().contains("glass") || value.toString().contains("iron_bars")))
                    return blockId(value.toString());
            }
        }
        return null;
    }

    private static String blockId(String value) {
        int bracket = value.indexOf('[');
        String id = (bracket < 0 ? value : value.substring(0, bracket)).trim();
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
