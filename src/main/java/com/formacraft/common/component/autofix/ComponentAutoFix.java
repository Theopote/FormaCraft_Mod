package com.formacraft.common.component.autofix;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.placement.SpatialContext;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SocketContext;
import com.formacraft.common.component.socket.SocketFacingPolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.formacraft.common.component.validate.EnumUtil.isBlank;

/**
 * Component AutoFix v1：自动修复构件定义中的明显错误。
 * <p>
 * 设计原则：
 * ✅ AutoFix 可以做的：
 * - 缺字段 → 补默认
 * - 明显非法值 → 纠正为最接近合法值
 * - 冗余/冲突字段 → 裁剪
 * - 根据 category / attachment / geometry 推导合理默认
 * <p>
 * ❌ AutoFix 不做的：
 * - 改 geometry.blocks 内容
 * - 改 socket 结构形态
 * - 改用户显式填写的数值（除非非法）
 * - 改 id / name / tags 语义
 */
public final class ComponentAutoFix {
    private ComponentAutoFix() {}

    /**
     * 应用自动修复到 ComponentDefinition
     * @param def 要修复的构件定义（会被修改）
     * @return 修复报告
     */
    public static AutoFixReport apply(ComponentDefinition def) {
        AutoFixReport report = new AutoFixReport();
        if (def == null) {
            report.add("$", "Component is null, cannot fix");
            return report;
        }

        fixIdentity(def, report);
        fixGeometry(def, report);
        fixPlacement(def, report);
        fixSockets(def, report);
        crossFix(def, report);

        return report;
    }

    // ----------------------------------------------------------------
    // Identity（身份信息）
    // ----------------------------------------------------------------
    private static void fixIdentity(ComponentDefinition def, AutoFixReport r) {
        // schema
        if (isBlank(def.schema)) {
            def.schema = "formacraft.component.v1";
            r.add("schema", "Defaulted to formacraft.component.v1");
        }

        // category
        if (def.category == null) {
            def.category = ComponentCategory.GENERIC;
            r.add("category", "Defaulted to GENERIC");
        }

        // tags
        if (def.tags == null) {
            def.tags = new ArrayList<>();
            r.add("tags", "Initialized empty tag list");
        } else {
            // 移除空白标签
            List<String> cleaned = new ArrayList<>();
            for (String tag : def.tags) {
                if (!isBlank(tag)) {
                    cleaned.add(tag.trim());
                }
            }
            if (cleaned.size() != def.tags.size()) {
                def.tags = cleaned;
                r.add("tags", "Removed blank tags");
            }
        }

        // name（如果 id 存在但 name 为空，可以用 id 作为 name）
        if (isBlank(def.name) && !isBlank(def.id)) {
            def.name = def.id;
            r.add("name", "Defaulted to id: " + def.id);
        }

        // allowed_facing（如果为空，设置默认值）
        if (def.allowed_facing == null || def.allowed_facing.isEmpty()) {
            def.allowed_facing = Set.of("NORTH", "SOUTH", "EAST", "WEST");
            r.add("allowed_facing", "Defaulted to [NORTH, SOUTH, EAST, WEST]");
        }
    }

    // ----------------------------------------------------------------
    // Geometry（几何信息）
    // ----------------------------------------------------------------
    private static void fixGeometry(ComponentDefinition def, AutoFixReport r) {
        // size
        if (def.size == null) {
            // 尝试从 blocks 推导 size
            if (def.blocks != null && !def.blocks.isEmpty()) {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

                for (var block : def.blocks) {
                    if (block == null) continue;
                    minX = Math.min(minX, block.dx);
                    minY = Math.min(minY, block.dy);
                    minZ = Math.min(minZ, block.dz);
                    maxX = Math.max(maxX, block.dx);
                    maxY = Math.max(maxY, block.dy);
                    maxZ = Math.max(maxZ, block.dz);
                }

                if (minX != Integer.MAX_VALUE) {
                    def.size = new ComponentDefinition.Size();
                    def.size.w = maxX - minX + 1;
                    def.size.h = maxY - minY + 1;
                    def.size.d = maxZ - minZ + 1;
                    r.add("size", String.format("Derived from blocks: %dx%dx%d", def.size.w, def.size.h, def.size.d));
                } else {
                    def.size = new ComponentDefinition.Size();
                    def.size.w = 1;
                    def.size.h = 1;
                    def.size.d = 1;
                    r.add("size", "Defaulted to 1x1x1");
                }
            } else {
                def.size = new ComponentDefinition.Size();
                def.size.w = 1;
                def.size.h = 1;
                def.size.d = 1;
                r.add("size", "Defaulted to 1x1x1 (no blocks to derive from)");
            }
        } else {
            // 修正非法值
            boolean fixed = false;
            if (def.size.w <= 0) {
                def.size.w = 1;
                fixed = true;
            }
            if (def.size.h <= 0) {
                def.size.h = 1;
                fixed = true;
            }
            if (def.size.d <= 0) {
                def.size.d = 1;
                fixed = true;
            }
            if (fixed) {
                r.add("size", String.format("Fixed invalid size to %dx%dx%d", def.size.w, def.size.h, def.size.d));
            }
        }

        // anchor
        if (def.anchor == null) {
            def.anchor = new ComponentDefinition.Anchor();
            def.anchor.dx = 0;
            def.anchor.dy = 0;
            def.anchor.dz = 0;
            def.anchor.facing = "SOUTH";
            r.add("anchor", "Created default anchor at (0,0,0) facing SOUTH");
        } else {
            // 修正 anchor.facing
            if (isBlank(def.anchor.facing)) {
                def.anchor.facing = "SOUTH";
                r.add("anchor.facing", "Defaulted to SOUTH");
            } else {
                String facing = def.anchor.facing.trim().toUpperCase();
                if (!facing.matches("^(NORTH|SOUTH|EAST|WEST|UP|DOWN)$")) {
                    def.anchor.facing = "SOUTH";
                    r.add("anchor.facing", "Invalid facing '" + def.anchor.facing + "' → corrected to SOUTH");
                }
            }
        }

        // blocks（移除 null 条目 + 归一化到非负局部坐标系）
        if (def.blocks != null) {
            List<ComponentDefinition.BlockEntry> cleaned = new ArrayList<>();
            for (var block : def.blocks) {
                if (block != null) {
                    cleaned.add(block);
                }
            }
            if (cleaned.size() != def.blocks.size()) {
                def.blocks = cleaned;
                r.add("blocks", "Removed null block entries");
            }
            normalizeBlocksToNonNegativeOrigin(def, r);
        }
    }

    /**
     * 将 blocks 平移到非负坐标，并同步 size / directionHints。
     * 捕获导出时若局部原点与 size 不一致（出现负 dx/dz），加载后会产生大量越界警告。
     */
    private static void normalizeBlocksToNonNegativeOrigin(ComponentDefinition def, AutoFixReport r) {
        if (def.blocks == null || def.blocks.isEmpty()) return;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (var block : def.blocks) {
            if (block == null) continue;
            minX = Math.min(minX, block.dx);
            minY = Math.min(minY, block.dy);
            minZ = Math.min(minZ, block.dz);
            maxX = Math.max(maxX, block.dx);
            maxY = Math.max(maxY, block.dy);
            maxZ = Math.max(maxZ, block.dz);
        }
        if (minX == Integer.MAX_VALUE) return;

        int needW = maxX - minX + 1;
        int needH = maxY - minY + 1;
        int needD = maxZ - minZ + 1;

        boolean hasNegative = minX < 0 || minY < 0 || minZ < 0;
        boolean sizeMismatch = def.size == null
                || def.size.w < needW || def.size.h < needH || def.size.d < needD;
        if (!hasNegative && !sizeMismatch) return;

        int shiftX = minX;
        int shiftY = minY;
        int shiftZ = minZ;
        for (var block : def.blocks) {
            if (block == null) continue;
            block.dx -= shiftX;
            block.dy -= shiftY;
            block.dz -= shiftZ;
        }
        shiftDirectionHints(def, shiftX, shiftY, shiftZ);

        if (def.size == null) {
            def.size = new ComponentDefinition.Size();
        }
        def.size.w = needW;
        def.size.h = needH;
        def.size.d = needD;

        r.add("blocks", String.format(
                "Normalized block bounds to origin: shifted (%d,%d,%d), size=%dx%dx%d",
                shiftX, shiftY, shiftZ, needW, needH, needD));
    }

    private static void shiftDirectionHints(ComponentDefinition def, int shiftX, int shiftY, int shiftZ) {
        if (def.directionHints == null) return;
        shiftMark(def.directionHints.inside, shiftX, shiftY, shiftZ);
        shiftMark(def.directionHints.outside, shiftX, shiftY, shiftZ);
        shiftMark(def.directionHints.bottom, shiftX, shiftY, shiftZ);
        shiftMark(def.directionHints.top, shiftX, shiftY, shiftZ);
        if (def.directionHints.hostFace != null) {
            def.directionHints.hostFace.dx -= shiftX;
            def.directionHints.hostFace.dy -= shiftY;
            def.directionHints.hostFace.dz -= shiftZ;
        }
    }

    private static void shiftMark(ComponentDefinition.DirectionHints.Mark mark, int shiftX, int shiftY, int shiftZ) {
        if (mark == null) return;
        mark.dx -= shiftX;
        mark.dy -= shiftY;
        mark.dz -= shiftZ;
    }

    // ----------------------------------------------------------------
    // Placement（放置规格）
    // ----------------------------------------------------------------
    private static void fixPlacement(ComponentDefinition def, AutoFixReport r) {
        ComponentPlacementSpec spec = def.placementSpec;
        if (spec == null) {
            spec = new ComponentPlacementSpec();
            def.placementSpec = spec;
            r.add("placementSpec", "Created default placementSpec");
        }

        // attachment（根据 category 推导默认值）
        if (spec.attachment == null) {
            spec.attachment = defaultAttachmentFor(def.category);
            r.add("placementSpec.attachment", "Defaulted to " + spec.attachment.name());
        }

        // facingPolicy（根据 category 推导默认值）
        if (spec.facingPolicy == null) {
            spec.facingPolicy = defaultFacingPolicyFor(def.category);
            r.add("placementSpec.facingPolicy", "Defaulted to " + spec.facingPolicy.name());
        }

        // spatialContext
        if (spec.spatialContext == null) {
            spec.spatialContext = SpatialContext.ANY;
            r.add("placementSpec.spatialContext", "Defaulted to ANY");
        }

        // constraints（如果为 null，创建默认）
        if (spec.constraints == null) {
            spec.constraints = new com.formacraft.common.component.placement.PlacementConstraints();
            r.add("placementSpec.constraints", "Created default constraints");
        }

        // semanticTags（移除空白）
        if (spec.semanticTags != null) {
            Set<String> cleaned = new HashSet<>();
            for (String tag : spec.semanticTags) {
                if (!isBlank(tag)) {
                    cleaned.add(tag.trim());
                }
            }
            if (cleaned.size() != spec.semanticTags.size()) {
                spec.semanticTags = cleaned;
                r.add("placementSpec.semanticTags", "Removed blank semantic tags");
            }
        }

        // aiHint（移除空白）
        if (spec.aiHint != null && spec.aiHint.trim().isEmpty()) {
            spec.aiHint = null;
            r.add("placementSpec.aiHint", "Removed blank aiHint");
        }

        // 根据 category 修正不一致
        if (def.category != null) {
            switch (def.category) {
                case DOOR, WINDOW -> {
                    if (spec.attachment != AttachmentType.WALL_OPENING) {
                        spec.attachment = AttachmentType.WALL_OPENING;
                        r.add("placementSpec.attachment", def.category.name() + " → corrected to WALL_OPENING");
                    }
                    if (spec.hasInteriorExterior && spec.facingPolicy == FacingPolicy.NONE) {
                        spec.facingPolicy = FacingPolicy.DERIVED_FROM_HOST;
                        r.add("placementSpec.facingPolicy", "hasInteriorExterior=true → corrected to DERIVED_FROM_HOST");
                    }
                }
                case COLUMN -> {
                    if (spec.attachment != AttachmentType.NONE && spec.attachment != AttachmentType.FLOOR) {
                        spec.attachment = AttachmentType.NONE;
                        r.add("placementSpec.attachment", "COLUMN → corrected to NONE");
                    }
                }
                case BRACKET, ORNAMENT -> {
                    if (spec.attachment != AttachmentType.WALL_SURFACE &&
                        spec.attachment != AttachmentType.EDGE &&
                        spec.attachment != AttachmentType.CORNER) {
                        spec.attachment = AttachmentType.WALL_SURFACE;
                        r.add("placementSpec.attachment", def.category.name() + " → corrected to WALL_SURFACE");
                    }
                }
                case ROOF_DETAIL -> {
                    if (spec.attachment != AttachmentType.ROOF_EDGE &&
                        spec.attachment != AttachmentType.ROOF_RIDGE &&
                        spec.attachment != AttachmentType.ROOF_SURFACE) {
                        spec.attachment = AttachmentType.ROOF_EDGE;
                        r.add("placementSpec.attachment", "ROOF_DETAIL → corrected to ROOF_EDGE");
                    }
                }
                default -> {}
            }
        }
    }

    /**
     * 根据 category 推导默认 attachment
     */
    private static AttachmentType defaultAttachmentFor(ComponentCategory cat) {
        if (cat == null) return AttachmentType.NONE;
        return switch (cat) {
            case DOOR, WINDOW -> AttachmentType.WALL_OPENING;
            case BRACKET, ORNAMENT -> AttachmentType.WALL_SURFACE;
            case ROOF_DETAIL -> AttachmentType.ROOF_EDGE;
            case ARCH -> AttachmentType.EDGE;
            case STAIRS -> AttachmentType.FLOOR;
            default -> AttachmentType.NONE;
        };
    }

    /**
     * 根据 category 推导默认 facingPolicy
     */
    private static FacingPolicy defaultFacingPolicyFor(ComponentCategory cat) {
        if (cat == null) return FacingPolicy.NONE;
        return switch (cat) {
            case DOOR, WINDOW -> FacingPolicy.DERIVED_FROM_HOST;
            case BRACKET, ORNAMENT -> FacingPolicy.OUTWARD_NORMAL;
            case ARCH, STAIRS -> FacingPolicy.ALONG_EDGE;
            default -> FacingPolicy.NONE;
        };
    }

    // ----------------------------------------------------------------
    // Sockets（插槽）
    // ----------------------------------------------------------------
    private static void fixSockets(ComponentDefinition def, AutoFixReport r) {
        if (def.sockets == null) {
            // sockets 是可选的，不需要创建
            return;
        }

        // 移除 null socket
        List<ComponentSocket> cleaned = new ArrayList<>();
        for (var socket : def.sockets) {
            if (socket != null) {
                cleaned.add(socket);
            }
        }
        if (cleaned.size() != def.sockets.size()) {
            def.sockets = cleaned;
            r.add("sockets", "Removed " + (0) + " null socket entries");
        }

        // Socket 本身是 final 类，无法修改字段
        // 但可以检查并记录问题（由 Validator 处理）
        // 这里只做清理工作
    }

    // ----------------------------------------------------------------
    // Cross-field 修复（跨字段一致性）
    // ----------------------------------------------------------------
    private static void crossFix(ComponentDefinition def, AutoFixReport r) {
        // 1. OPENING 类别不应该有 require_edge（如果 placement_rules 存在）
        if (def.category == ComponentCategory.DOOR || def.category == ComponentCategory.WINDOW) {
            if (def.placement_rules != null && def.placement_rules.requires_wall) {
                // DOOR/WINDOW 应该 requires_wall=true，这是合理的
            }
        }

        // 2. 如果 anchor 坐标超出 size 范围，调整 anchor 到边界内
        if (def.size != null && def.anchor != null) {
            boolean fixed = false;
            if (def.anchor.dx < 0) {
                def.anchor.dx = 0;
                fixed = true;
            } else if (def.anchor.dx >= def.size.w) {
                def.anchor.dx = def.size.w - 1;
                fixed = true;
            }
            if (def.anchor.dy < 0) {
                def.anchor.dy = 0;
                fixed = true;
            } else if (def.anchor.dy >= def.size.h) {
                def.anchor.dy = def.size.h - 1;
                fixed = true;
            }
            if (def.anchor.dz < 0) {
                def.anchor.dz = 0;
                fixed = true;
            } else if (def.anchor.dz >= def.size.d) {
                def.anchor.dz = def.size.d - 1;
                fixed = true;
            }
            if (fixed) {
                r.add("anchor", String.format("Adjusted anchor to (%d,%d,%d) to fit within size", 
                    def.anchor.dx, def.anchor.dy, def.anchor.dz));
            }
        }

        // 3. 如果 blocks 为空但 size 存在，至少需要警告（但不自动修复，因为可能是有意的）
        // 这里不做自动修复，由 Validator 报告

        // 4. placement_rules 默认值
        if (def.placement_rules == null) {
            def.placement_rules = new ComponentDefinition.PlacementRules();
            r.add("placement_rules", "Created default placement_rules");
        }
    }
}
