package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/** Final-patch diagnostics for recorded windows with explicit materials and door openings. */
final class OpeningMaterialAudit {
    record Expectation(String componentId, GeneratedSurfaceCapture.Role role, String blockId) {}
    record Issue(String componentId, String reason, BlockPos position, String expected, String actual) {}
    record Result(String status, int checkedWindows, int checkedDoorCells, int unresolvedCells,
                  int mismatchCells, Issue firstIssue) {
        boolean isMatched() {
            return "matched".equals(status);
        }
    }

    static void capture(Map<BlockPos, Expectation> expectations, Component c,
                        List<GeneratedSurfaceCapture.Cell> cells, BlockPos offset) {
        String id = c.params() == null ? c.componentType()
                : String.valueOf(c.params().getOrDefault("component_id", c.componentType()));
        String material = explicitWindowMaterial(c);
        for (var cell : cells) {
            var pos = cell.position().add(offset);
            if (cell.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING
                    || cell.role() == GeneratedSurfaceCapture.Role.WINDOW && material != null)
                expectations.put(pos, new Expectation(id, cell.role(), material));
            else expectations.remove(pos); // Later declared roles supersede earlier decisions.
        }
    }

    static Result inspect(Map<BlockPos, Expectation> expectations, List<BlockPatch> patches) {
        var finals = new HashMap<BlockPos, BlockPatch>();
        for (var p : patches) if (p != null) finals.put(new BlockPos(p.dx(), p.dy(), p.dz()), p);
        int windows = 0, doors = 0, unresolved = 0, mismatch = 0;
        Issue first = null;
        for (var entry : expectations.entrySet()) {
            var expected = entry.getValue();
            var patch = finals.get(entry.getKey());
            if (patch == null) {
                unresolved++;
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
                if (first == null) first = new Issue(expected.componentId(), reason, entry.getKey(),
                        expected.role() == GeneratedSurfaceCapture.Role.WINDOW ? expected.blockId() : "air_or_door", actual);
            }
        }
        return new Result(mismatch > 0 ? "mismatch" : unresolved > 0 || windows + doors == 0 ? "not_assessed" : "matched",
                windows, doors, unresolved, mismatch, first);
    }

    /**
     * Convert audit result to a capability gap when openings fail contract.
     * mismatch → E_OPENING_MATERIAL_MISMATCH; unresolved expectations → E_OPENING_UNRESOLVED.
     * Empty expectations / no windows+doors with zero unresolved is not a gap (nothing to gate).
     */
    static Optional<CapabilityGap> toCapabilityGap(Result result) {
        if (result == null || result.isMatched()) return Optional.empty();
        if (result.mismatchCells() > 0) {
            Issue issue = result.firstIssue();
            String detail = issue == null ? "n/a"
                    : issue.reason() + " at " + issue.position().toShortString()
                    + " expected=" + issue.expected() + " actual=" + issue.actual()
                    + " component=" + issue.componentId();
            return Optional.of(new CapabilityGap(
                    "E_OPENING_MATERIAL_MISMATCH",
                    "门窗契约失败：mismatchCells=" + result.mismatchCells() + "; " + detail,
                    "components[]",
                    List.of("保持显式玻璃/窗材不被后处理覆盖", "勿用实心方块填堵门洞")
            ));
        }
        if (result.unresolvedCells() > 0) {
            return Optional.of(new CapabilityGap(
                    "E_OPENING_UNRESOLVED",
                    "门窗契约未解析：unresolvedCells=" + result.unresolvedCells()
                            + " windows=" + result.checkedWindows()
                            + " doors=" + result.checkedDoorCells(),
                    "components[]",
                    List.of("确保开口单元在最终 Patch 中有对应坐标", "检查后处理是否删除了开口 Patch")
            ));
        }
        // status not_assessed with zero expectations checked — no hard fail
        return Optional.empty();
    }

    private static String explicitWindowMaterial(Component c) {
        if (c.params() == null) return null;
        for (String key : List.of("glass_block", "glazing_block", "glass_material", "window_block")) {
            Object value = c.params().get(key);
            if (value != null && !value.toString().isBlank()) return blockId(value.toString());
        }
        if ("FACADE_WINDOWS".equalsIgnoreCase(c.componentType())) for (String key : List.of("block", "material")) {
            Object value = c.params().get(key);
            if (value != null && (value.toString().contains("glass") || value.toString().contains("iron_bars")))
                return blockId(value.toString());
        }
        return null;
    }

    private static String blockId(String value) {
        int bracket = value.indexOf('[');
        String id = (bracket < 0 ? value : value.substring(0, bracket)).trim();
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
