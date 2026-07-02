package com.formacraft.common.component.archetype;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.semantic.ComponentSemanticInference;
import com.formacraft.common.logging.FcaLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@link ComponentDefinition} 生成或同步 {@link ComponentArchetype} 侧车数据。
 */
public final class ComponentArchetypeBridge {
    private static final FcaLog LOG = FcaLog.of("ComponentArchetypeBridge");

    private ComponentArchetypeBridge() {}

    public static void sync(ComponentDefinition def) {
        if (def == null) {
            return;
        }
        ComponentSemanticInference.ensureSemanticFields(def);
        ComponentArchetype archetype = fromDefinition(def);
        if (archetype != null) {
            ComponentArchetypeStorage.saveArchetype(archetype);
        }
    }

    public static ComponentArchetype fromDefinition(ComponentDefinition def) {
        if (def == null || def.id == null || def.id.isBlank()) {
            return null;
        }

        String archetypeId = !isBlank(def.archetypeRef) ? def.archetypeRef : def.id;
        ComponentArchetype archetype = new ComponentArchetype();
        archetype.id = archetypeId;
        archetype.displayName = def.name != null ? def.name : archetypeId;
        archetype.category = mapArchetypeCategory(def.category);
        archetype.semanticTags = def.tags != null ? new ArrayList<>(def.tags) : List.of();
        archetype.attachment = mapAttachment(def);
        archetype.variation = mapVariation(def.category);
        archetype.geometryHint = mapGeometryHint(def);
        archetype.validation = ValidationSpec.createDefault();
        return archetype;
    }

    private static String mapArchetypeCategory(ComponentCategory category) {
        if (category == null) {
            return "DECORATION";
        }
        return switch (category) {
            case DOOR, WINDOW -> "OPENING";
            case COLUMN -> "SUPPORT";
            case STAIRS -> "CIRCULATION";
            default -> "DECORATION";
        };
    }

    private static AttachmentSpec mapAttachment(ComponentDefinition def) {
        ComponentCategory category = def.category != null ? def.category : ComponentCategory.GENERIC;
        if (def.placementSpec != null && def.placementSpec.attachment != null) {
            return switch (def.placementSpec.attachment) {
                case WALL_OPENING -> category == ComponentCategory.WINDOW
                        ? AttachmentSpec.forWindow()
                        : AttachmentSpec.forDoor();
                case WALL_SURFACE, ROOF_SURFACE, ROOF_RIDGE -> AttachmentSpec.createDefault();
                case ROOF_EDGE, EDGE -> AttachmentSpec.forRailing();
                case FLOOR -> AttachmentSpec.forColumn();
                case CORNER -> AttachmentSpec.createDefault();
                case NONE -> category == ComponentCategory.COLUMN || category == ComponentCategory.BRACKET
                        ? AttachmentSpec.forColumn()
                        : AttachmentSpec.createDefault();
            };
        }

        return switch (category) {
            case DOOR -> AttachmentSpec.forDoor();
            case WINDOW -> AttachmentSpec.forWindow();
            case COLUMN, BRACKET -> AttachmentSpec.forColumn();
            case ORNAMENT, ROOF_DETAIL, ARCH -> AttachmentSpec.createDefault();
            default -> AttachmentSpec.createDefault();
        };
    }

    private static VariationSpec mapVariation(ComponentCategory category) {
        if (category == null) {
            return new VariationSpec();
        }
        return switch (category) {
            case DOOR -> VariationSpec.forDoor();
            case WINDOW -> VariationSpec.forWindow();
            case COLUMN -> VariationSpec.forColumn();
            case ORNAMENT, ROOF_DETAIL, ARCH, BRACKET -> VariationSpec.forRailing();
            default -> new VariationSpec();
        };
    }

    private static GeometryHint mapGeometryHint(ComponentDefinition def) {
        ComponentCategory category = def.category != null ? def.category : ComponentCategory.GENERIC;
        GeometryHint hint = switch (category) {
            case DOOR -> GeometryHint.forDoor();
            case WINDOW -> GeometryHint.forWindow();
            case COLUMN -> GeometryHint.forColumn();
            case ORNAMENT, ROOF_DETAIL, ARCH, BRACKET -> {
                GeometryHint ornament = new GeometryHint();
                ornament.archetype = GeometryArchetype.ORNAMENT;
                ornament.visualIdentity = "decorative architectural element";
                ornament.symmetryPreferred = true;
                yield ornament;
            }
            default -> {
                GeometryHint generic = new GeometryHint();
                generic.archetype = GeometryArchetype.VOLUME;
                yield generic;
            }
        };

        if (!isBlank(def.culturalStyle)) {
            String identity = hint.visualIdentity != null ? hint.visualIdentity : "architectural component";
            hint.visualIdentity = def.culturalStyle.toLowerCase() + " " + identity;
        }
        if (!isBlank(def.geometryArchetype)) {
            try {
                hint.archetype = GeometryArchetype.valueOf(def.geometryArchetype.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                LOG.debug("parse geometry archetype failed value={}", def.geometryArchetype, ex);
            }
        }
        return hint;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
