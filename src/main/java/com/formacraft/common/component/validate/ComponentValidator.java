package com.formacraft.common.component.validate;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.socket.ComponentSocket;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.formacraft.common.component.validate.EnumUtil.isBlank;

/**
 * ComponentDefinition v1 验证器（核心入口）。
 * <p>
 * 按模块组织验证逻辑：
 * - Identity（id, name, category, tags）
 * - Geometry（size, anchor, blocks）
 * - Placement（placementSpec）
 * - Sockets
 * - Cross-field sanity checks
 */
public final class ComponentValidator {
    private ComponentValidator() {}

    // v1 支持的 schema 版本
    private static final String SUPPORTED_SCHEMA = "formacraft.component.v1";

    /**
     * 验证 ComponentDefinition
     */
    public static ValidationResult validate(ComponentDefinition def) {
        ValidationResult out = new ValidationResult();

        if (def == null) {
            out.error("$", "Component is null");
            return out;
        }

        // -------------------------
        // 1) Identity
        // -------------------------
        validateIdentity(def, out);

        // -------------------------
        // 2) Geometry
        // -------------------------
        GeometryValidator.validate(def, out);

        // -------------------------
        // 3) Placement
        // -------------------------
        PlacementValidator.validate(def.placementSpec, out, def.category);

        // -------------------------
        // 4) Sockets
        // -------------------------
        SocketValidator.validate(def.sockets, out);

        // -------------------------
        // 5) Cross-field sanity checks
        // -------------------------
        crossChecks(def, out);

        return out;
    }

    /**
     * 验证身份信息（id, name, category, tags, schema）
     */
    private static void validateIdentity(ComponentDefinition def, ValidationResult out) {
        // schema
        if (isBlank(def.schema)) {
            out.error("schema", "Missing schema");
        } else if (!SUPPORTED_SCHEMA.equals(def.schema.trim())) {
            out.warn("schema", "Unsupported schema: " + def.schema + " (expected: " + SUPPORTED_SCHEMA + ")");
        }

        // id
        if (isBlank(def.id)) {
            out.error("id", "Missing id");
        } else {
            String id = def.id.trim();
            if (!id.matches("^[a-z0-9_\\-\\.]+$")) {
                out.error("id", "Invalid id format. Use lowercase, digits, underscore, hyphen, or dot. Got: " + id);
            }
        }

        // name
        if (isBlank(def.name)) {
            out.warn("name", "Missing display name (name)");
        }

        // category
        if (def.category == null) {
            out.warn("category", "Missing category. Default may be GENERIC.");
        }

        // tags
        if (def.tags != null) {
            if (def.tags.size() > 64) {
                out.warn("tags", "Too many tags (>64). Consider trimming.");
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < def.tags.size(); i++) {
                String t = def.tags.get(i);
                if (isBlank(t)) {
                    out.warn("tags[" + i + "]", "Blank tag");
                } else {
                    String normalized = t.trim().toLowerCase();
                    if (!seen.add(normalized)) {
                        out.warn("tags[" + i + "]", "Duplicate tag: " + t);
                    }
                }
            }
        }

        // allowed_facing
        if (def.allowed_facing != null) {
            for (String facing : def.allowed_facing) {
                if (isBlank(facing)) {
                    out.warn("allowed_facing", "Blank facing value");
                } else {
                    String up = facing.trim().toUpperCase();
                    if (!up.matches("^(NORTH|SOUTH|EAST|WEST|UP|DOWN)$")) {
                        out.error("allowed_facing", "Invalid facing: " + facing + " (expected: NORTH/SOUTH/EAST/WEST/UP/DOWN)");
                    }
                }
            }
        }
    }

    /**
     * 跨字段合理性检查
     */
    private static void crossChecks(ComponentDefinition def, ValidationResult out) {
        // Socket ID 唯一性检查
        if (def.sockets != null) {
            Set<String> socketIds = new HashSet<>();
            for (int i = 0; i < def.sockets.size(); i++) {
                var socket = def.sockets.get(i);
                if (socket == null) continue;
                String id = socket.id;
                if (isBlank(id)) continue;
                String normalized = id.trim().toLowerCase();
                if (!socketIds.add(normalized)) {
                    out.error("sockets[" + i + "].id", "Duplicate socket id: " + id);
                }
            }
        }

        validateSocketPlacements(def, out);

        // Category 与 Placement 的一致性检查
        if (def.category != null && def.placementSpec != null) {
            switch (def.category) {
                case DOOR, WINDOW -> {
                    // 门/窗通常应该是 WALL_OPENING
                    if (def.placementSpec.attachment != null &&
                        def.placementSpec.attachment.name().equals("WALL_OPENING")) {
                        // OK
                    } else {
                        out.warn("placement.attachment", def.category.name() + " category usually uses WALL_OPENING attachment");
                    }
                }
                case COLUMN -> {
                    // 柱子通常是 NONE（自由放置）
                    if (def.placementSpec.attachment != null &&
                        def.placementSpec.attachment.name().equals("NONE")) {
                        // OK
                    } else {
                        out.warn("placement.attachment", "COLUMN category usually uses NONE attachment (free placement)");
                    }
                }
                case BRACKET, ORNAMENT -> {
                    // 装饰物通常附着在墙上或边缘
                    if (def.placementSpec.attachment != null) {
                        String att = def.placementSpec.attachment.name();
                        if (!att.equals("WALL_SURFACE") && !att.equals("EDGE") && !att.equals("CORNER")) {
                            out.warn("placement.attachment", def.category.name() + " category usually attaches to WALL_SURFACE, EDGE, or CORNER");
                        }
                    }
                }
                default -> {}
            }
        }

        // Blocks 与 Size 的一致性检查
        if (def.size != null && def.blocks != null) {
            int sizeW = def.size.w;
            int sizeH = def.size.h;
            int sizeD = def.size.d;

            // 检查是否有方块超出 size 范围
            for (int i = 0; i < def.blocks.size(); i++) {
                var block = def.blocks.get(i);
                if (block == null) continue;
                if (block.dx < 0 || block.dx >= sizeW ||
                    block.dy < 0 || block.dy >= sizeH ||
                    block.dz < 0 || block.dz >= sizeD) {
                    out.warn("blocks[" + i + "]", String.format(
                        "Block position (%d,%d,%d) is outside size bounds (%d,%d,%d)",
                        block.dx, block.dy, block.dz, sizeW, sizeH, sizeD));
                }
            }
        }
    }

    private static void validateSocketPlacements(ComponentDefinition def, ValidationResult out) {
        if (def.socketPlacements == null || def.socketPlacements.isEmpty()) {
            return;
        }

        Set<String> placementIds = new HashSet<>();
        Set<String> socketIds = new HashSet<>();
        if (def.sockets != null) {
            for (ComponentSocket socket : def.sockets) {
                if (socket == null || isBlank(socket.id)) continue;
                socketIds.add(socket.id.trim().toLowerCase());
            }
        }

        for (int i = 0; i < def.socketPlacements.size(); i++) {
            var sp = def.socketPlacements.get(i);
            String base = "socketPlacements[" + i + "]";
            if (sp == null) {
                out.warn(base, "Null socket placement");
                continue;
            }
            if (isBlank(sp.id)) {
                out.error(base + ".id", "Missing socket placement id");
                continue;
            }
            String id = sp.id.trim();
            if (!placementIds.add(id.toLowerCase())) {
                out.error(base + ".id", "Duplicate socket placement id: " + id);
            }
            if (!socketIds.isEmpty() && !socketIds.contains(id.toLowerCase())) {
                out.warn(base + ".id", "socketPlacements id '" + id + "' has no matching sockets[] entry");
            }
        }

        if (!socketIds.isEmpty()) {
            for (String socketId : socketIds) {
                if (!placementIds.contains(socketId)) {
                    out.warn("socketPlacements", "Socket '" + socketId + "' has no socketPlacements origin (mount may use geometry fallback)");
                }
            }
        }
    }
}
