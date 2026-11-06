package com.formacraft.common.generation.capability;

import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.typology.StructuralTypologyRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Machine-readable generation capability snapshot derived from live registries.
 * Python stage-B precheck and LLM planning must consume this contract — do not
 * hand-maintain a divergent prompt-only table.
 */
public final class GenerationCapabilityCatalog {

    public static final String SCHEMA = "formacraft.generation_capability.v1";
    public static final int VERSION = 1;

    /** Bodies that may own hosted satellites (mirrors building_contract owners). */
    public static final List<String> HOSTABLE_COMPONENT_TYPES = List.of(
            "MASS_MAIN", "TOWER", "MASS_WING", "SIDE_WING", "MASS_ANCILLARY", "MASS_SECONDARY",
            "TERRACE", "COURTYARD", "COURTYARD_SPACE", "GATE", "GATE_STRUCTURE",
            "STRUCTURE", "MODULE", "FOUNDATION", "PLAZA", "PAVING", "PATH", "ROAD", "KEEP",
            "WALL"
    );

    /** Satellites that require a resolvable host_id. */
    public static final List<String> HOSTED_COMPONENT_TYPES = List.of(
            "ROOF", "ROOF_STRUCTURE", "FOUNDATION", "FACADE_WINDOWS", "FACADE", "ENTRANCE",
            "ENTRANCE_CANOPY", "CROWN", "CUPOLA", "DOME", "MASS_SECONDARY", "DECOR_DETAIL",
            "BALCONY", "TERRACE", "STRUCTURE", "ASSEMBLY"
    );

    /**
     * Planner tokens → executable roof_type. Keep in sync with {@link RoofTypeCatalog}
     * aliases; unknown tokens are rejected unless mapped here.
     */
    public static final Map<String, String> ROOF_ALIASES = Map.ofEntries(
            Map.entry("irimoya", "xieshan"),
            Map.entry("iri_moya", "xieshan"),
            Map.entry("iri-moya", "xieshan"),
            Map.entry("入母屋", "xieshan"),
            Map.entry("入母屋造", "xieshan"),
            Map.entry("hip_and_gable", "xieshan"),
            Map.entry("hip-gable", "xieshan"),
            Map.entry("hip_gable", "xieshan"),
            Map.entry("歇山", "xieshan"),
            Map.entry("庑殿", "hip"),
            Map.entry("wu_dian", "hip"),
            Map.entry("wudian", "hip"),
            Map.entry("四坡", "hip"),
            Map.entry("悬山", "xuanshan"),
            Map.entry("硬山", "yingshan"),
            Map.entry("双坡", "double_gable"),
            Map.entry("gabled", "gable"),
            Map.entry("hipped", "hip"),
            Map.entry("pyramidal", "pyramid"),
            Map.entry("conical", "cone"),
            Map.entry("domed", "dome")
    );

    private GenerationCapabilityCatalog() {}

    /** Build a JSON-serializable catalog from live Java registries. */
    public static Map<String, Object> build() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("version", VERSION);
        root.put("component_types", componentTypes());
        root.put("hostable_component_types", new ArrayList<>(HOSTABLE_COMPONENT_TYPES));
        root.put("hosted_component_types", new ArrayList<>(HOSTED_COMPONENT_TYPES));
        root.put("roof", roofSection());
        root.put("typologies", typologyIds());
        root.put("building_classes", buildingClasses());
        return root;
    }

    public static String normalizeRoofType(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        String aliased = ROOF_ALIASES.getOrDefault(token, ROOF_ALIASES.getOrDefault(raw.trim(), token));
        if (RoofTypeCatalog.isExecutableExplicit(aliased)) {
            return aliased;
        }
        // Preserve original when neither alias nor executable — caller decides reject/repair.
        return token;
    }

    public static boolean isExecutableRoofType(String raw) {
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        String normalized = normalizeRoofType(raw);
        return RoofTypeCatalog.isExecutableExplicit(normalized);
    }

    public static boolean isHostable(String componentType) {
        if (componentType == null || componentType.isBlank()) {
            return false;
        }
        return HOSTABLE_COMPONENT_TYPES.contains(componentType.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isHosted(String componentType) {
        if (componentType == null || componentType.isBlank()) {
            return false;
        }
        return HOSTED_COMPONENT_TYPES.contains(componentType.trim().toUpperCase(Locale.ROOT));
    }

    private static Map<String, Object> componentTypes() {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> registered = new TreeSet<>(ComponentGeneratorRegistry.registeredTypes());
        // STRUCTURE is typology-routed rather than a ComponentGenerator.
        registered.add("STRUCTURE");
        registered.add("MODULE");
        for (String type : registered) {
            Map<String, Object> entry = new LinkedHashMap<>();
            boolean hasGenerator = ComponentGeneratorRegistry.hasGenerator(type);
            if ("STRUCTURE".equals(type) || "MODULE".equals(type)) {
                entry.put("implementation_status", "typology_routed");
            } else if ("ASSEMBLY".equals(type)) {
                entry.put("implementation_status", "fbd_or_empty");
            } else if (hasGenerator) {
                entry.put("implementation_status", "supported");
            } else {
                entry.put("implementation_status", "unsupported");
            }
            entry.put("can_host", isHostable(type));
            entry.put("hosted", isHosted(type));
            if ("ROOF".equals(type) || "ROOF_STRUCTURE".equals(type)) {
                entry.put("supported_types_ref", "roof.supported_tokens");
            }
            if ("GATE".equals(type) || "GATE_STRUCTURE".equals(type)) {
                entry.put("output_contract", List.of("through_passage", "wide_shallow_preferred"));
                entry.put("fallback_policy", "accept_mass_with_passage_axis");
            }
            out.put(type, entry);
        }
        return out;
    }

    private static Map<String, Object> roofSection() {
        Map<String, Object> roof = new LinkedHashMap<>();
        List<String> supported = new ArrayList<>(new TreeSet<>(RoofTypeCatalog.SUPPORTED_TOKENS));
        roof.put("supported_tokens", supported);
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.putAll(ROOF_ALIASES);
        roof.put("aliases", aliases);
        roof.put("fallback_policy", "map_alias_then_reject_unknown");
        return roof;
    }

    private static List<String> typologyIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (StructuralTypologyRegistry.TypologyDef def : StructuralTypologyRegistry.listTypologies()) {
            if (def != null && def.id() != null && !def.id().isBlank()) {
                ids.add(def.id());
            }
        }
        return new ArrayList<>(ids);
    }

    private static Map<String, Object> buildingClasses() {
        Map<String, Object> classes = new LinkedHashMap<>();
        classes.put("tower_like", Map.of(
                "preferred_components", List.of("STRUCTURE"),
                "preferred_typologies", List.of("dense_eaves_pagoda", "tibetan_stupa"),
                "composition", "single"));
        classes.put("gate", Map.of(
                "preferred_components", List.of("GATE", "GATE_STRUCTURE", "STRUCTURE"),
                "accept_mass_proportions", true,
                "composition", "single"));
        classes.put("temple", Map.of(
                "preferred_components", List.of("MASS_MAIN", "ROOF"),
                "composition_default", "single",
                "composition_compound_triggers", List.of("建筑群", "院落", "厢房", "配殿", "三进", "两进")));
        classes.put("garden", Map.of(
                "preferred_components", List.of("COURTYARD", "PATH", "MASS_MAIN"),
                "composition", "compound"));
        classes.put("generic", Map.of(
                "preferred_components", List.of("MASS_MAIN", "ROOF", "ENTRANCE", "FACADE_WINDOWS"),
                "composition", "single"));
        return classes;
    }
}
