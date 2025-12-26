package com.formacraft.common.style.catalog;

import java.util.List;
import java.util.Map;

/**
 * StyleProfileCatalog (data-driven, v1):
 * A higher-level "style gene library" aligned with FormacraftGene v1.
 *
 * Notes:
 * - Keep JSON field names snake_case to match assets directly (Gson default).
 * - Defaults are grouped into geometry/materials/components/algorithm, intentionally flexible.
 */
public final class StyleProfileCatalog {
    public String version = "1.0";
    public String description = "";

    /** style_id -> profile def */
    public Map<String, StyleProfileDef> profiles = Map.of();

    public static final class StyleProfileDef {
        public Meta meta = new Meta();
        public Defaults defaults = new Defaults();
        public Constraints constraints = new Constraints();
    }

    public static final class Meta {
        public String display_name = "";
        public String family = "";
        public List<String> tags = List.of();
        public String description = "";
    }

    public static final class Defaults {
        public Map<String, Object> geometry = Map.of();
        public Map<String, Object> materials = Map.of();
        public Map<String, Object> components = Map.of();
        public Map<String, Object> algorithm = Map.of();
    }

    public static final class Constraints {
        public List<String> allowed_archetypes = List.of();
        /** follow | local_level | global_flatten */
        public String terrain_policy = "";
        /** none | mirror | radial */
        public String symmetry_preference = "";
        /** low | medium | high */
        public String silhouette_strength = "";
    }
}


