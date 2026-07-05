package com.formacraft.common.archetype;

import java.util.List;
import java.util.Map;

/**
 * archetypes_v1.json root model.
 */
public final class ArchetypeCatalog {
    public int version = 1;
    public List<ArchetypeDef> archetypes = List.of();

    public static final class ArchetypeDef {
        public String id;
        public List<String> aliases = List.of();
        public String category;      // LANDMARK / INFRASTRUCTURE / FORTIFICATION
        public String generatorId;   // maps to generator factory; blank when researchOnly
        /** When true, aliases identify a real landmark but no preset MODULE generator exists. */
        public Boolean researchOnly;
        public Map<String, Object> defaults;
        public Map<String, Object> constraints;
        public Map<String, Object> scoringWeights;

        public boolean hasModuleGenerator() {
            return generatorId != null && !generatorId.isBlank();
        }
    }
}


