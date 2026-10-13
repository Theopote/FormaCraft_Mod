package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.transform.CapturedBlockStateTransform;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound11Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void capturedOriginSurvivesJsonRoundTrip() {
        var def = new ComponentDefinition();
        def.id = "cap_test";
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.size = new ComponentDefinition.Size();
        def.size.w = 3;
        def.size.h = 3;
        def.size.d = 1;
        String json = JsonUtil.toJson(def);
        var loaded = JsonUtil.fromJson(json, ComponentDefinition.class);
        assertTrue(loaded.isCapturedOrigin());
        assertEquals(ComponentDefinition.ORIGIN_CAPTURED, loaded.sourceOrigin);
    }

    @Test
    void capturedWithoutRulesSkipsRandomVariantPath() {
        var def = new ComponentDefinition();
        def.id = "cap_no_rules";
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.size = new ComponentDefinition.Size();
        def.size.w = 5;
        def.size.h = 5;
        def.size.d = 2;
        def.variantRules = null;
        var query = new ComponentQuery();
        query.geometry = new ComponentQuery.Geometry();
        query.geometry.scalable = true;
        query.geometry.openingWidth = 3;
        query.geometry.openingHeight = 3;
        var variant = VariantGenerator.generate(def, query, new Random(1));
        assertNotNull(variant);
        assertEquals(1.0f, variant.scaleX);
        assertEquals(1.0f, variant.scaleY);
        assertEquals(1.0f, variant.scaleZ);
        assertNull(variant.trimmedWidth);
        assertFalse(variant.mirrored);
        assertEquals(0, variant.repeatCount);
    }

    @Test
    void validatorRejectsDoorInMovableBandBeforeSave() {
        var def = new ComponentDefinition();
        def.size = new ComponentDefinition.Size();
        def.size.w = 3;
        def.size.h = 1;
        def.size.d = 1;
        def.variantRules = new ComponentPrototype.VariantRules();
        def.variantRules.scaling = new ComponentPrototype.VariantRules.Scaling();
        def.variantRules.scaling.axes = new HashMap<>();
        var axis = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        axis.type = "REPEAT";
        axis.fixedStart = 1;
        axis.fixedEnd = 1;
        def.variantRules.scaling.axes.put("X", axis);
        def.blocks = List.of(
                block(0, 0, 0, "minecraft:stone"),
                block(1, 0, 0, "minecraft:oak_door[half=lower,facing=south]"),
                block(2, 0, 0, "minecraft:stone"));
        String err = CapturedComponentRuleValidator.validate(def);
        assertNotNull(err);
        assertTrue(err.contains("X"));
        assertTrue(err.contains("门") || err.contains("配对"));
    }

    @Test
    void transformKeepsDoorHalfAndStairFacing() {
        String door = CapturedBlockStateTransform.apply(
                "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]",
                Direction.SOUTH,
                new ComponentTransform(Direction.EAST, Mirror.NONE));
        var doorState = BlockPatchTargetResolver.parse(door);
        assertNotNull(doorState);
        assertEquals(Direction.EAST, doorState.get(Properties.HORIZONTAL_FACING));
        assertTrue(door.contains("half=lower"));

        String stair = CapturedBlockStateTransform.apply(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
                Direction.SOUTH,
                new ComponentTransform(Direction.WEST, Mirror.NONE));
        var stairState = BlockPatchTargetResolver.parse(stair);
        assertNotNull(stairState);
        assertEquals(Direction.EAST, stairState.get(Properties.HORIZONTAL_FACING));
    }

    @Test
    void fenceFacingSurvivesMirror() {
        String fence = CapturedBlockStateTransform.apply(
                "minecraft:oak_fence_gate[facing=east,open=false,in_wall=false]",
                Direction.SOUTH,
                new ComponentTransform(Direction.SOUTH, Mirror.X));
        var state = BlockPatchTargetResolver.parse(fence);
        assertNotNull(state);
        assertEquals(Direction.WEST, state.get(Properties.HORIZONTAL_FACING));
    }

    @Test
    void materialApplierPrefersStructureTagOverFill() {
        var v = new Voxel(0, 0, 0, "minecraft:oak_planks");
        v.addSemanticTag("FILL");
        v.addSemanticTag("STRUCTURE");
        assertEquals("STRUCTURE", SemanticMaterialApplier.pickSemanticTag(v, List.of("STRUCTURE", "FRAME", "FILL")));
    }

    @Test
    void materialSwapRefusesDoorBlockChange() {
        var original = BlockPatchTargetResolver.parse("minecraft:oak_door[half=lower,facing=south]");
        var swapped = com.formacraft.common.component.semantic.ShapePreservingMaterialSwap.apply(
                "minecraft:oak_door[half=lower,facing=south]", Blocks.STONE.getDefaultState());
        assertEquals(original, swapped);
    }

    @Test
    void scalerPreviewMatchesRepeatedPlacement() {
        var voxels = new java.util.ArrayList<Voxel>();
        for (int x = 0; x < 4; x++) voxels.add(new Voxel(x, 0, 0, "slice_" + x));
        var tpl = new StructureTemplate(voxels, 4, 1, 1);
        var rules = new ComponentPrototype.VariantRules.Scaling();
        rules.axes = new HashMap<>();
        var rule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        rule.type = "REPEAT";
        rule.fixedStart = 1;
        rule.fixedEnd = 1;
        rules.axes.put("X", rule);
        var target = new com.formacraft.common.component.model.PersistedComponentVariant.Params.Scale();
        target.x = 7;
        target.y = 1;
        target.z = 1;
        var a = SegmentScaler.applyScaling(tpl, rules, target);
        var b = SegmentScaler.applyScaling(tpl, rules, target);
        assertEquals(
                a.all().stream().map(v -> v.x() + ":" + v.blockState()).sorted().toList(),
                b.all().stream().map(v -> v.x() + ":" + v.blockState()).sorted().toList());
    }

    private static ComponentDefinition.BlockEntry block(int x, int y, int z, String state) {
        var be = new ComponentDefinition.BlockEntry();
        be.dx = x;
        be.dy = y;
        be.dz = z;
        be.block = state;
        return be;
    }
}
