package com.formacraft.server.generation.structure.selector;

import java.util.List;
import java.util.Map;

/**
 * generator_selector_rules_v1.json root model.
 */
public final class GeneratorSelectorCatalog {
    public int version = 1;
    public List<Rule> rules = List.of();

    public static final class Rule {
        public String id;
        public When when;
        public Then then;
    }

    public static final class When {
        public String cityStyle;     // optional: ASIAN/MODERN/MEDIEVAL/...
        public String zoneType;      // CORE/PUBLIC/SEMI_PUBLIC/PRIVATE/SERVICE/TRANSITION/LANDSCAPE/CIRCULATION
        public String shape;         // CIRCLE/RECTANGLE/LINEAR/POINT/POLYGON
        public Integer minRadius;    // for CIRCLE
        public Integer maxRadius;

        // for RECTANGLE (uses effective footprint width/depth)
        public Integer minWidth;
        public Integer maxWidth;
        public Integer minDepth;
        public Integer maxDepth;
        public Integer minArea;      // width*depth
        public Integer maxArea;      // width*depth
    }

    public static final class Then {
        public String template;      // maps to GeneratorRouter.template routing
        public String landmark;      // maps to GeneratorRouter.legacy landmark routing
        public String buildingType;  // HOUSE/TOWER/...
        public Integer floors;
        public Integer height;
        public Integer radius;       // optional override for footprint radius (circle)
        public Integer width;        // optional override for footprint width (rectangle)
        public Integer depth;
        public Map<String, Object> extraDefaults; // merged into spec.extra if key missing
    }
}


