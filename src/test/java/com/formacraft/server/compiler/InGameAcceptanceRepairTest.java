package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.impl.FacadeWindowsGenerator;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.FacadeScope;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions from Round-12 in-game acceptance (dense columns, missing 重檐, solid_sides ignored, EMPTY_OUTPUT).
 */
class InGameAcceptanceRepairTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void facadeScopeHonorsFacadeSideAndWindowSidesAliases() {
        assertEquals(Set.of("NORTH"),
                FacadeScope.parseStrict(Map.of("facade_side", "north"), 1).faceNames());
        assertEquals(Set.of("EAST", "WEST"),
                FacadeScope.parseStrict(Map.of("window_sides", List.of("east", "west")), 1).faceNames());
    }

    @Test
    void solidSidesSuppressEastWestWindowsOnMass() {
        Map<String, Object> params = new HashMap<>();
        params.put("wall_block", "minecraft:white_concrete");
        params.put("hollow", true);
        params.put("window_style", "square");
        params.put("window_ratio", 0.35);
        params.put("solid_sides", List.of("east", "west"));
        params.put("window_sides", List.of("north", "south"));
        params.put("roof_type", "none");
        params.put("entrance_type", "none");
        Component mass = new Component(
                "MASS_MAIN", "studio", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 6), List.of(), params);
        Slot slot = new Slot("studio", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", slot, mass));
        List<BlockPatch> glass = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .toList();
        assertTrue(glass.stream().noneMatch(p -> p.dx() == 0 || p.dx() == 11),
                "EAST/WEST solid_sides must suppress mass windows");
    }

    @Test
    void facadeSideNorthDoesNotWrapOntoEastWest() {
        Map<String, Object> params = new HashMap<>();
        params.put("facade_side", "north");
        params.put("window_width", 2);
        params.put("window_height", 2);
        params.put("windows_per_wall", 2);
        params.put("floor_count", 1);
        params.put("floor_height", 4);
        params.put("sill_offset", 1);
        params.put("reserve_entrance_bay", false);
        Component c = new Component(
                "FACADE_WINDOWS", "studio", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 4), List.of(), params);
        Slot slot = new Slot("studio", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> glass = new FacadeWindowsGenerator()
                .generate(new SemanticComponent("FACADE_WINDOWS", slot, c)).stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .toList();
        assertFalse(glass.isEmpty());
        assertTrue(glass.stream().allMatch(p -> p.dz() == 9), "NORTH-only facade_side must stay on maxZ");
        assertTrue(glass.stream().noneMatch(p -> p.dx() == 0 || p.dx() == 11));
    }

    @Test
    void tailiangCapsBayDensityAndDefaultsWithoutInteriorForest() {
        Map<String, Object> params = new HashMap<>();
        params.put("width", 15);
        params.put("depth", 15);
        params.put("baysX", 5);
        params.put("baysZ", 4);
        params.put("anchorMode", "corner");
        params.put("includeSubEaves", false);
        params.put("includeBrackets", false);
        params.put("roofType", "wu_dian");
        var states = new HashMap<BlockPos, net.minecraft.block.BlockState>();
        TailiangTimberHallBuilder.generate(params, new BlockPos(0, 64, 0), null, null)
                .getBlocks().forEach(b -> states.put(b.getPos(), b.getTargetState()));
        // Interior cell near center should stay walkable (no log forest).
        assertTrue(states.getOrDefault(new BlockPos(7, 67, 7), Blocks.AIR.getDefaultState()).isAir()
                        || !states.get(new BlockPos(7, 67, 7)).isOf(Blocks.DARK_OAK_LOG),
                "default hall must not fill interior with columns");
    }

    @Test
    void tailiangDoubleEaveEmitsTwoRoofHeightBands() {
        Map<String, Object> params = new HashMap<>();
        params.put("width", 17);
        params.put("depth", 15);
        params.put("baysX", 3);
        params.put("baysZ", 3);
        params.put("anchorMode", "corner");
        params.put("double_eaves", true);
        params.put("eave_tiers", 2);
        params.put("includeSubEaves", true); // ignored when double_eaves wins
        params.put("includeBrackets", false);
        params.put("includeInteriorColumns", false);
        params.put("roofType", "wu_dian");
        params.put("hallHeight", 8);
        assertTrue(TailiangTimberHallBuilder.wantsDoubleEave(
                new com.formacraft.common.typology.TypologyParams(params)));
        var ys = new HashSet<Integer>();
        TailiangTimberHallBuilder.generate(params, new BlockPos(0, 64, 0), null, null)
                .getBlocks().forEach(b -> {
                    var s = b.getTargetState();
                    if (s.isOf(Blocks.DEEPSLATE_TILES) || s.isOf(Blocks.DEEPSLATE_BRICK_STAIRS)
                            || s.isOf(Blocks.DEEPSLATE_TILE_SLAB) || s.isOf(Blocks.STONE_BRICK_STAIRS)) {
                        ys.add(b.getPos().getY());
                    }
                });
        assertTrue(ys.size() >= 4, "重檐 needs distinct lower+upper roof Y bands, ys=" + ys);
        int min = ys.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = ys.stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(max - min >= 3, "upper tier should sit above lower eave, span=" + (max - min));
    }

    @Test
    void emptyComponentsRecoverFromSlotSize() {
        Slot slot = new Slot(
                "hall_slot",
                new Vec3i(0, 0, 0),
                GlobalConstraints.Facing.SOUTH,
                null, null, null,
                new Vec3i(12, 8, 10));
        LlmPlan plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .layout(new Layout(Layout.SkeletonType.COMPOUND, false, List.of(slot)))
                .components(List.of())
                .build();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of("hall_slot", slot), false);
        assertFalse(prepared.components().isEmpty(), "slot.size must recover MASS_MAIN");
        assertTrue(prepared.components().stream()
                .anyMatch(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType())));
    }
}
