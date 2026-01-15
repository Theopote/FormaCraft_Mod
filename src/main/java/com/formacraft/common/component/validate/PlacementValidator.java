package com.formacraft.common.component.validate;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.placement.SpatialContext;

/**
 * 放置规格验证器（ComponentPlacementSpec）。
 */
public final class PlacementValidator {
    private PlacementValidator() {}

    public static void validate(ComponentPlacementSpec spec, ValidationResult out, ComponentCategory category) {
        if (spec == null) {
            // placementSpec 是可选的（有默认值）
            return;
        }

        // AttachmentType（枚举，不需要字符串解析）
        if (spec.attachment == null) {
            out.warn("placement.attachment", "Missing attachment (default may be NONE)");
        }
        // 枚举值本身是类型安全的，但如果从 JSON 反序列化失败，可能是 null

        // FacingPolicy
        if (spec.facingPolicy == null) {
            out.warn("placement.facingPolicy", "Missing facingPolicy (default may be NONE)");
        }

        // SpatialContext
        if (spec.spatialContext == null) {
            out.warn("placement.spatialContext", "Missing spatialContext (default may be ANY)");
        }

        // Constraints
        if (spec.constraints != null) {
            // PlacementConstraints 的字段验证（如果有需要）
            // 目前 PlacementConstraints 可能没有需要验证的字段
        }

        // semanticTags
        if (spec.semanticTags != null) {
            if (spec.semanticTags.size() > 32) {
                out.warn("placement.semanticTags", "Too many semantic tags (>32)");
            }
            for (String tag : spec.semanticTags) {
                if (tag == null || tag.trim().isEmpty()) {
                    out.warn("placement.semanticTags", "Blank semantic tag");
                }
            }
        }

        // aiHint
        if (spec.aiHint != null && spec.aiHint.length() > 200) {
            out.warn("placement.aiHint", "AI hint too long (>200 characters)");
        }

        // Category 特定的合理性检查
        if (category != null) {
            switch (category) {
                case DOOR, WINDOW -> {
                    if (spec.attachment != AttachmentType.WALL_OPENING) {
                        out.warn("placement.attachment", category.name() + " usually uses WALL_OPENING attachment");
                    }
                    if (spec.hasInteriorExterior && spec.facingPolicy == FacingPolicy.NONE) {
                        out.warn("placement.facingPolicy", category.name() + " with hasInteriorExterior=true usually needs a facing policy");
                    }
                }
                case COLUMN -> {
                    if (spec.attachment != AttachmentType.NONE && spec.attachment != AttachmentType.FLOOR) {
                        out.warn("placement.attachment", "COLUMN usually uses NONE or FLOOR attachment");
                    }
                }
                case BRACKET, ORNAMENT -> {
                    if (spec.attachment != AttachmentType.WALL_SURFACE &&
                        spec.attachment != AttachmentType.EDGE &&
                        spec.attachment != AttachmentType.CORNER) {
                        out.warn("placement.attachment", category.name() + " usually attaches to WALL_SURFACE, EDGE, or CORNER");
                    }
                }
                case ROOF_DETAIL -> {
                    if (spec.attachment != AttachmentType.ROOF_EDGE &&
                        spec.attachment != AttachmentType.ROOF_RIDGE &&
                        spec.attachment != AttachmentType.ROOF_SURFACE) {
                        out.warn("placement.attachment", "ROOF_DETAIL usually attaches to ROOF_EDGE, ROOF_RIDGE, or ROOF_SURFACE");
                    }
                }
                default -> {}
            }
        }
    }
}
