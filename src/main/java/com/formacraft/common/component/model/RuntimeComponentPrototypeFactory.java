package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.archetype.ComponentArchetype;
import com.formacraft.common.component.archetype.ComponentArchetypeStorage;

/**
 * 从运行时 {@link ComponentDefinition} 构建内存 Prototype（无需磁盘 prototype 目录）。
 */
public final class RuntimeComponentPrototypeFactory {
    private RuntimeComponentPrototypeFactory() {}

    public static ComponentPrototype fromDefinition(ComponentDefinition def) {
        if (def == null || def.id == null || def.id.isBlank()) {
            return null;
        }

        ComponentPrototype proto = new ComponentPrototype();
        proto.id = def.id;
        proto.name = def.name;
        proto.category = def.category;
        proto.tags = def.tags;

        ComponentPrototype.StructureRef sr = new ComponentPrototype.StructureRef();
        sr.format = "component_v1_json";
        sr.file = "component.json";
        if (def.size != null) {
            sr.bounds = new ComponentPrototype.StructureRef.Bounds();
            sr.bounds.w = def.size.w;
            sr.bounds.h = def.size.h;
            sr.bounds.d = def.size.d;
        }
        if (def.anchor != null) {
            sr.anchor = new ComponentPrototype.StructureRef.Anchor();
            sr.anchor.x = def.anchor.dx;
            sr.anchor.y = def.anchor.dy;
            sr.anchor.z = def.anchor.dz;
            sr.default_facing = def.anchor.facing;
        }
        proto.structure = sr;

        ComponentArchetype archetype = ComponentArchetypeStorage.resolve(def);
        if (archetype != null && archetype.variation != null) {
            proto.variant_rules = ComponentPrototypeRulesBridge.fromVariationSpec(archetype.variation, def);
        }
        return proto;
    }
}
