package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound12Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void capturedWithoutRulesIgnoresRuntimeScaleAndTrim() {
        ComponentDefinition base = capturedSlab(4, 2, 2);
        int blockCount = base.blocks.size();
        ComponentVariant variant = new ComponentVariant(base);
        variant.scaleX = 2.0f;
        variant.trimmedWidth = 2;
        variant.repeatCount = 3;

        ComponentDefinition out = ComponentModelApi.applyRuntimeVariant(base, null, variant);
        assertSame(base, out);
        assertEquals(4, out.size.w);
        assertEquals(2, out.size.h);
        assertEquals(blockCount, out.blocks.size());

        ComponentQuery mismatch = new ComponentQuery();
        mismatch.geometry = new ComponentQuery.Geometry();
        mismatch.geometry.scalable = true;
        mismatch.geometry.openingWidth = 8;
        mismatch.geometry.openingHeight = 2;
        mismatch.geometry.targetDepth = 2;
        assertNull(ComponentModelApi.fitToQuery(base, mismatch, variant));
    }

    @Test
    void compileResultStatuses() {
        assertEquals(VariantCompileResult.Status.INVALID_INPUT,
                ComponentVariantCompiler.compileResult(null, null, Direction.SOUTH, null).status());

        ComponentPrototype proto = new ComponentPrototype();
        proto.id = "missing_tpl";
        proto.structure = new ComponentPrototype.StructureRef();
        proto.structure.format = "component_v1_json";
        proto.structure.file = "component.json";
        assertEquals(VariantCompileResult.Status.INVALID_INPUT,
                ComponentVariantCompiler.compileResult(proto, null, Direction.SOUTH, null).status());

        PersistedComponentVariant variant = new PersistedComponentVariant();
        variant.params = new PersistedComponentVariant.Params();
        variant.params.scale = new PersistedComponentVariant.Params.Scale();
        variant.params.scale.x = 1;
        variant.params.scale.y = 1;
        variant.params.scale.z = 1;
        VariantCompileResult missing = ComponentVariantCompiler.compileResult(proto, variant, Direction.SOUTH, null);
        assertEquals(VariantCompileResult.Status.TEMPLATE_MISSING, missing.status());
        assertTrue(ComponentVariantCompiler.compileToPatches(missing).isEmpty());
    }

    @Test
    void socketRepeatExpandsAlongRepeatAxis() {
        ComponentDefinition base = railingWithThreeMounts();
        ComponentQuery q = new ComponentQuery();
        q.geometry = new ComponentQuery.Geometry();
        q.geometry.scalable = true;
        q.geometry.openingWidth = 11; // middle 9 = 3 periods of 3
        q.geometry.openingHeight = 1;
        q.geometry.targetDepth = 1;

        ComponentDefinition out = ComponentModelApi.fitToQuery(base, q, null);
        assertNotNull(out);
        assertEquals(11, out.size.w);
        assertNotNull(out.socketPlacements);
        assertEquals(9, out.socketPlacements.size());
        Set<Integer> xs = out.socketPlacements.stream().map(p -> p.dx).collect(Collectors.toSet());
        assertTrue(xs.size() >= 3, "repeat mounts should land on distinct X slices: " + xs);
    }

    @Test
    void forbiddenSocketInMiddleFailsAdapt() {
        ComponentDefinition base = railingWithThreeMounts();
        base.socketPlacements.get(1).adaptMode = "FORBIDDEN";
        ComponentQuery q = new ComponentQuery();
        q.geometry = new ComponentQuery.Geometry();
        q.geometry.scalable = true;
        q.geometry.openingWidth = 11;
        q.geometry.openingHeight = 1;
        q.geometry.targetDepth = 1;
        assertNull(ComponentModelApi.fitToQuery(base, q, null));
    }

    @Test
    void materialTagFiltersToSemanticMapThenBuiltinPriority() {
        Voxel v = new Voxel(0, 0, 0, "minecraft:stone");
        v.addSemanticTag("ORNAMENT");
        v.addSemanticTag("SURFACE");
        v.addSemanticTag("FILL");
        Map<String, String> map = new HashMap<>();
        map.put("SURFACE", "wall");
        map.put("FILL", "floor");
        // ORNAMENT not in map → ignored; SURFACE beats FILL
        assertEquals("SURFACE", SemanticMaterialApplier.pickSemanticTag(v, map, null));

        Voxel structural = new Voxel(0, 0, 0, "minecraft:stone");
        structural.addSemanticTag("STRUCTURAL");
        structural.addSemanticTag("FRAME");
        map.put("STRUCTURAL", "wall");
        map.put("FRAME", "trim");
        assertEquals("STRUCTURAL", SemanticMaterialApplier.pickSemanticTag(structural, map, null));
    }

    @Test
    void previewPatchesMatchSegmentScalerEmitter() {
        ComponentDefinition base = railingWithThreeMounts();
        ComponentQuery q = new ComponentQuery();
        q.geometry = new ComponentQuery.Geometry();
        q.geometry.scalable = true;
        q.geometry.openingWidth = 8;
        q.geometry.openingHeight = 1;
        q.geometry.targetDepth = 1;

        ComponentDefinition placed = ComponentModelApi.fitToQuery(base, q, null);
        assertNotNull(placed);

        StructureTemplate tpl = StructureLoader.fromComponentDefinition(base);
        PersistedComponentVariant.Params.Scale scale = new PersistedComponentVariant.Params.Scale();
        scale.x = 8;
        scale.y = 1;
        scale.z = 1;
        VoxelGrid grid = SegmentScaler.applyScaling(tpl, base.variantRules.scaling, scale);
        List<BlockPatch> scalerPatches = PatchEmitter.emit(grid);

        Set<String> fromPlace = placed.blocks.stream()
                .map(b -> b.dx + "," + b.dy + "," + b.dz + "|" + b.block)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> fromPreview = scalerPatches.stream()
                .map(p -> p.dx() + "," + p.dy() + "," + p.dz() + "|" + p.targetBlock())
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(fromPlace, fromPreview);
        assertEquals(fromPlace.size(), placed.blocks.size());
        assertEquals(fromPreview.size(), scalerPatches.size());
    }

    private static ComponentDefinition capturedSlab(int w, int h, int d) {
        var def = new ComponentDefinition();
        def.id = "cap_gate";
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.variantRules = null;
        def.size = new ComponentDefinition.Size();
        def.size.w = w;
        def.size.h = h;
        def.size.d = d;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    var b = new ComponentDefinition.BlockEntry();
                    b.dx = x;
                    b.dy = y;
                    b.dz = z;
                    b.block = "minecraft:stone";
                    def.blocks.add(b);
                }
            }
        }
        return def;
    }

    /** 5×1×1 railing: X REPEAT with fixed ends; three middle mounts with adaptMode=REPEAT. */
    private static ComponentDefinition railingWithThreeMounts() {
        var def = new ComponentDefinition();
        def.id = "rail_repeat";
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.size = new ComponentDefinition.Size();
        def.size.w = 5;
        def.size.h = 1;
        def.size.d = 1;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            var b = new ComponentDefinition.BlockEntry();
            b.dx = x;
            b.block = "minecraft:oak_fence";
            def.blocks.add(b);
        }
        def.variantRules = new ComponentPrototype.VariantRules();
        def.variantRules.scaling = new ComponentPrototype.VariantRules.Scaling();
        def.variantRules.scaling.axes = new HashMap<>();
        var xRule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        xRule.type = "REPEAT";
        xRule.fixedStart = 1;
        xRule.fixedEnd = 1;
        def.variantRules.scaling.axes.put("X", xRule);
        var yRule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        yRule.type = "FIXED";
        def.variantRules.scaling.axes.put("Y", yRule);
        var zRule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        zRule.type = "FIXED";
        def.variantRules.scaling.axes.put("Z", zRule);

        def.socketPlacements = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var p = new ComponentDefinition.SocketPlacement();
            p.id = "mount_" + i;
            p.dx = 1 + i;
            p.dy = 0;
            p.dz = 0;
            p.facing = "SOUTH";
            p.adaptMode = "REPEAT";
            def.socketPlacements.add(p);
        }
        return def;
    }
}
