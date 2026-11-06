package com.formacraft.server.compiler;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.server.generation.typology.builder.DenseEavesPagodaBuilder;
import com.formacraft.server.generation.typology.builder.PagodaMorphologyAudit;
import com.formacraft.server.generation.typology.builder.PagodaVerticalSemantics;
import com.formacraft.server.generation.typology.builder.TibetanStupaBuilder;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch C helpers: request → typology → final blocks (not merely non-empty structures).
 */
final class AcceptanceReplayMorphology {
    private AcceptanceReplayMorphology() {}

    static void assertDenseEavesPagoda(Component structure, BlockPos origin) {
        assertNotNull(structure);
        Map<String, Object> merged = TypologyParamResolver.merge(
                DenseEavesPagodaBuilder.TYPOLOGY_ID, structure);
        PagodaVerticalSemantics vertical = PagodaVerticalSemantics.resolve(
                new TypologyParams(merged), 13, 7, 15);

        Object floorParam = first(structure.params(), "floor_count", "levels");
        if (floorParam != null) {
            assertEquals(asInt(floorParam), vertical.floorCount(), "floor_count semantics");
        }
        Object epl = first(structure.params(), "eaves_per_level", "eavesPerLevel");
        if (epl != null) {
            assertEquals(asInt(epl), vertical.eavesPerLevel(), "eaves_per_level semantics");
        }

        GeneratedStructure built = DenseEavesPagodaBuilder.generate(merged, origin, null, null);
        assertNotNull(built);
        assertFalse(built.getBlocks().isEmpty(), "pagoda must emit blocks");

        PagodaMorphologyAudit.GeometryObservation geo =
                PagodaMorphologyAudit.observe(built.getBlocks(), origin);
        assertTrue(geo.rolesPresent(), "pagoda blocks must carry morphology roles");
        assertEquals(vertical.floorCount(), geo.measuredFloors(), "shaft storeys");
        assertEquals(vertical.eaveTiers(), geo.measuredEaveTiers(), "eave continuity / count");
        assertTrue(geo.finialCentered(), "finial must sit on tower axis");
        assertTrue(geo.span() >= vertical.floorCount() * 3, "span too short: " + geo.span());

        PagodaMorphologyAudit.Result audit = PagodaMorphologyAudit.checkFromBlocks(
                DenseEavesPagodaBuilder.TYPOLOGY_ID, vertical, built.getBlocks(), origin, true);
        assertTrue(audit.passed(), () -> String.valueOf(audit.diagnostics()));

        String wantBody = blockId(first(structure.params(), "bodyBlock", "body_block", "wall_block", "wallBlock"));
        if (wantBody != null) {
            assertTrue(containsBlock(built.getBlocks(), wantBody),
                    "expected body material " + wantBody + " in final blocks");
        }
    }

    static void assertTibetanStupa(Component structure, BlockPos origin) {
        Map<String, Object> merged = TypologyParamResolver.merge(
                TibetanStupaBuilder.TYPOLOGY_ID, structure);
        GeneratedStructure built = TibetanStupaBuilder.generate(merged, origin, null, null);
        assertNotNull(built);
        assertFalse(built.getBlocks().isEmpty());
        assertTrue(built.getDescription().toLowerCase(Locale.ROOT).contains("tibetan")
                        || built.getDescription().toLowerCase(Locale.ROOT).contains("stupa"),
                built.getDescription());
    }

    static void assertPavilionGardenHosts(List<Component> components) {
        boolean hasHall = components.stream().anyMatch(c -> {
            if (c == null || c.params() == null) return false;
            String id = String.valueOf(c.params().getOrDefault("component_id", ""));
            String role = String.valueOf(c.params().getOrDefault("building_role", ""));
            return id.contains("hall") || role.contains("hall") || "MASS_MAIN".equals(c.componentType());
        });
        boolean hasPavilion = components.stream().anyMatch(c -> {
            if (c == null) return false;
            String slot = c.slotId() == null ? "" : c.slotId().toLowerCase(Locale.ROOT);
            String id = c.params() == null ? "" : String.valueOf(c.params().getOrDefault("component_id", ""));
            String role = c.params() == null ? "" : String.valueOf(c.params().getOrDefault("building_role", ""));
            return slot.contains("pavilion") || id.contains("pavilion") || role.contains("pavilion")
                    || slot.contains("亭");
        });
        assertTrue(hasHall, "pavilion garden must keep a primary hall mass");
        assertTrue(hasPavilion, "pavilion garden must include a pavilion host");

        boolean pavilionRoof = components.stream().anyMatch(c -> {
            if (c == null || !"ROOF".equalsIgnoreCase(c.componentType()) || c.params() == null) return false;
            String host = String.valueOf(c.params().getOrDefault("host_id", ""));
            String rt = String.valueOf(c.params().getOrDefault("roof_type", "")).toLowerCase(Locale.ROOT);
            return host.contains("pavilion") && (rt.contains("pyramid") || rt.contains("攒尖"));
        });
        assertTrue(pavilionRoof, "pavilion must keep a host-bound pyramid/攒尖 roof");
    }

    private static boolean containsBlock(List<PlannedBlock> blocks, String want) {
        String normalized = blockId(want);
        if (normalized == null) return false;
        for (PlannedBlock b : blocks) {
            if (b == null || b.getTargetState() == null || b.getTargetState().isAir()) continue;
            String id = net.minecraft.registry.Registries.BLOCK.getId(b.getTargetState().getBlock()).toString();
            if (normalized.equals(id)) return true;
        }
        return false;
    }

    private static Object first(Map<String, Object> params, String... keys) {
        if (params == null) return null;
        for (String key : keys) {
            Object v = params.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return v;
        }
        return null;
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }

    private static String blockId(Object value) {
        if (value == null) return null;
        String id = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return null;
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
