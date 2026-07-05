package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.generation.component.impl.AssemblyPatchGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将 LLM 误嵌在 {@code MASS_*.params.assembly} 中的 MetaAssembly 载荷提升为独立 {@code ASSEMBLY} 构件，
 * 并剥离同 slot 上与之冲突的 MASS / ROOF / FACADE / ENTRANCE 叠加。
 */
public final class AssemblyPlanPromoter {

    private static final Set<String> STRIP_ON_ASSEMBLY_SLOT = Set.of(
            "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS",
            "ROOF", "ROOF_STRUCTURE", "FACADE_WINDOWS", "ENTRANCE"
    );

    private AssemblyPlanPromoter() {}

    public record PromotionResult(List<Component> components, Set<String> assemblyPrimarySlots) {}

    public static PromotionResult promoteNestedAssembly(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return new PromotionResult(List.of(), Set.of());
        }

        Set<String> assemblyPrimarySlots = new HashSet<>();
        for (Component c : components) {
            if (c != null && "ASSEMBLY".equals(normalizeType(c.componentType()))) {
                assemblyPrimarySlots.add(slotKey(c));
            }
        }

        List<Component> promoted = new ArrayList<>(components.size() + 2);

        for (Component c : components) {
            if (c == null) {
                continue;
            }
            String type = normalizeType(c.componentType());
            if (!isMassType(type)) {
                promoted.add(c);
                continue;
            }

            String slotKey = slotKey(c);
            if (assemblyPrimarySlots.contains(slotKey)) {
                Object payload = AssemblyPatchGenerator.resolveAssemblyPayload(c.params());
                if (payload != null) {
                    FormacraftMod.LOGGER.warn(
                            "AssemblyPlanPromoter: dropping {} with nested params.assembly on slot {} (ASSEMBLY already present)",
                            type, slotKey
                    );
                } else {
                    FormacraftMod.LOGGER.info(
                            "AssemblyPlanPromoter: dropping conflicting {} on assembly-primary slot {}",
                            type, slotKey
                    );
                }
                continue;
            }

            Object payload = AssemblyPatchGenerator.resolveAssemblyPayload(c.params());
            if (payload == null) {
                promoted.add(c);
                continue;
            }

            Component assembly = toAssemblyComponent(c, payload);
            promoted.add(assembly);
            assemblyPrimarySlots.add(slotKey);
            FormacraftMod.LOGGER.info(
                    "AssemblyPlanPromoter: promoted nested params.assembly on {} (slot {}) -> ASSEMBLY",
                    type, slotKey
            );
        }

        if (assemblyPrimarySlots.isEmpty()) {
            return new PromotionResult(promoted, Set.of());
        }

        return new PromotionResult(stripConflictingOnAssemblySlots(promoted, assemblyPrimarySlots), assemblyPrimarySlots);
    }

    private static List<Component> stripConflictingOnAssemblySlots(
            List<Component> components,
            Set<String> assemblyPrimarySlots
    ) {
        List<Component> filtered = new ArrayList<>(components.size());
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            String slotKey = slotKey(c);
            if (!assemblyPrimarySlots.contains(slotKey)) {
                filtered.add(c);
                continue;
            }
            String type = normalizeType(c.componentType());
            if ("ASSEMBLY".equals(type)) {
                filtered.add(c);
                continue;
            }
            if (isMassType(type) || STRIP_ON_ASSEMBLY_SLOT.contains(type)) {
                FormacraftMod.LOGGER.info(
                        "AssemblyPlanPromoter: stripping conflicting {} on assembly-primary slot {}",
                        type, slotKey
                );
                continue;
            }
            filtered.add(c);
        }
        return filtered;
    }

    private static Component toAssemblyComponent(Component mass, Object payload) {
        Map<String, Object> assemblyRoot = payload instanceof Map<?, ?> map
                ? copyMap(map)
                : Map.of("payload", payload);

        Map<String, Object> params = new HashMap<>();
        params.put("assembly", assemblyRoot);

        Vec3i pos = mass.relativePosition();
        Dimensions dims = mass.dimensions();
        return new Component(
                "ASSEMBLY",
                mass.slotId(),
                pos != null ? pos : new Vec3i(0, 0, 0),
                dims,
                mass.features(),
                params
        );
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : source.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String slotKey(Component c) {
        return c.slotId() != null ? c.slotId() : "__global__";
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isMassType(String type) {
        return "MASS_MAIN".equals(type)
                || "MASS_SECONDARY".equals(type)
                || "MASS_WING".equals(type)
                || "SIDE_WING".equals(type)
                || "MAIN_MASS".equals(type);
    }
}
