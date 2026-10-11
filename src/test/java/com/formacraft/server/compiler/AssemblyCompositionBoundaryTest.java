package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 组合边界验收矩阵（审查报告 P1-06）：
 * 后序 MASS/ROOF/装饰不得覆盖已登记的门洞/通道表面所有权。
 * <p>
 * 本测试不代替游戏内几何验收；它锁定 ownership ledger 对组合覆盖的硬约束。
 */
class AssemblyCompositionBoundaryTest {

    private Component mass(String id) {
        return new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(12, 8, 20),
                List.of(), Map.of("component_id", id, "anchor_mode", "min_corner"));
    }

    private Component roof(String id, String host) {
        return new Component("ROOF", "main", new Vec3i(0, 8, 0), new Dimensions(12, 4, 20),
                List.of(), Map.of("component_id", id, "host_id", host, "roof_type", "gable"));
    }

    private Component entrance(String host) {
        return new Component("ENTRANCE", "main", new Vec3i(5, 0, 0), new Dimensions(2, 3, 1),
                List.of(), Map.of("component_id", "door", "host_id", host));
    }

    private BlockPatch place(int dx, int dy, int dz, String block) {
        return new BlockPatch(BlockPatch.PLACE, dx, dy, dz, block);
    }

    private BlockPatch remove(int dx, int dy, int dz) {
        return new BlockPatch(BlockPatch.REMOVE, dx, dy, dz, "minecraft:air");
    }

    @Test
    void apseAndTransept_doorwaySurvivesSubsequentRoof() {
        var ledger = new SurfaceOwnershipLedger();
        var nave = mass("nave");
        ledger.accept(nave, List.of(place(5, 1, 0, "minecraft:stone_bricks")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(5, 1, 0), GeneratedSurfaceCapture.Role.WALL)),
                BlockPos.ORIGIN);

        var door = entrance("nave");
        assertTrue(ledger.check(door, List.of(remove(5, 1, 0))).isEmpty(),
                "门洞应允许切除宿主墙面");
        ledger.accept(door, List.of(remove(5, 1, 0)), List.of(), BlockPos.ORIGIN);

        // 已接受门洞后，装饰性 remove 不应再报冲突（洞口已合法）
        assertTrue(ledger.check(componentDecor(), List.of(remove(5, 1, 0))).isEmpty(),
                "已接受门洞后，后续装饰不应把洞口报为非法破坏");
        assertTrue(ledger.check(roof("nave_roof", "nave"),
                List.of(place(5, 8, 0, "minecraft:oak_stairs"))).isEmpty());
    }

    @Test
    void ribVaultAndShell_decorationCannotDestroyWallSurface() {
        var ledger = new SurfaceOwnershipLedger();
        // surface 键与 patch 相对坐标对齐（见 SurfaceOwnershipLedger.index）
        ledger.accept(mass("choir"), List.of(place(3, 4, 5, "minecraft:stone_bricks")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(3, 4, 5), GeneratedSurfaceCapture.Role.WALL)),
                BlockPos.ORIGIN);

        var decor = componentDecor();
        assertTrue(ledger.check(decor, List.of(remove(3, 4, 5))).isPresent(),
                "装饰不得删除已登记墙面（肋拱/薄壳场景的覆盖防护）");
        assertTrue(ledger.check(decor, List.of(
                remove(3, 4, 5),
                place(3, 4, 5, "minecraft:chiseled_stone_bricks")
        )).isEmpty(), "允许同位置 remove+place 材质替换");
    }

    @Test
    void fourFacings_ledgerOwnershipIsHostScopedNotFacingDependent() {
        for (String facing : List.of("north", "south", "east", "west")) {
            var ledger = new SurfaceOwnershipLedger();
            var host = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(8, 6, 8),
                    List.of(), Map.of("component_id", "hall", "facing", facing));
            ledger.accept(host, List.of(place(4, 1, 0, "minecraft:stone_bricks")),
                    List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(4, 1, 0), GeneratedSurfaceCapture.Role.WALL)),
                    BlockPos.ORIGIN);

            var entry = new Component("ENTRANCE", "main", new Vec3i(4, 0, 0), new Dimensions(2, 3, 1),
                    List.of(), Map.of("host_id", "hall", "facing", facing));
            assertTrue(ledger.check(entry, List.of(remove(4, 1, 0))).isEmpty(),
                    "facing=" + facing + " 时应允许入口开洞");
        }
    }

    private Component componentDecor() {
        return new Component("DECOR_DETAIL", "main", new Vec3i(0, 0, 0), new Dimensions(1, 1, 1),
                List.of(), Map.of("component_id", "trim"));
    }
}
