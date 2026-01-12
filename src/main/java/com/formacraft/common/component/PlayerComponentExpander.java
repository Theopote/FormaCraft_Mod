package com.formacraft.common.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.component.semantic.BlockStatePropertyUtil;
import com.formacraft.common.component.semantic.SemanticBlockStatePicker;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Player Component Library（构件库）展开器：
 * - 从 LLM component.features 中读取 component_request:{...}
 * - 在服务端 world save 中匹配/加载 ComponentDefinition
 * - 展开为 BlockPatch（支持旋转/镜像 + 可选语义换皮）
 * <p>
 * 约定：
 * - 返回 null 表示“该组件不是构件库请求”，上层应走正常生成器
 * - 返回 empty list 表示“是构件库请求，但未匹配到/无法生成”
 */
public final class PlayerComponentExpander {
    private PlayerComponentExpander() {}

    private static final String PREFIX = "component_request:";

    public static List<BlockPatch> tryExpand(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null || world == null) return null;
        String featureJson = extractFeatureJson(semantic.source().features());
        if (featureJson == null) return null;

        Map<String, Object> reqMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) JsonUtil.get().fromJson(featureJson, Map.class);
            reqMap = m;
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("PlayerComponentExpander: invalid component_request json: {}", featureJson);
            return List.of();
        }
        if (reqMap == null) return List.of();

        Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);

        // mount 模式：一次性生成 host + carve + mount
        if (isMountRequest(reqMap)) {
            return expandMount(semantic, world, worldDir, reqMap);
        }

        // 1) 确定要加载的构件（单构件）
        ComponentDefinition def = resolveComponent(worldDir, reqMap, null);
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return List.of();

        // 2) 解析变换参数（facing/mirror）
        Direction fromFacing = parseDir(def.anchor != null ? def.anchor.facing : null);
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) fromFacing = Direction.SOUTH;

        Direction targetFacing = parseDir(getString(reqMap, "facing", "target_facing"));
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) {
            targetFacing = facingFromSlot(semantic);
        }
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) targetFacing = Direction.SOUTH;

        Mirror mirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode"));
        ComponentTransform transform = new ComponentTransform(targetFacing, mirror);

        // 3) 放置偏移（相对于 slot anchor）
        int baseX = 0, baseY = 0, baseZ = 0;
        if (semantic.source().relativePosition() != null) {
            baseX = semantic.source().relativePosition().x();
            baseY = semantic.source().relativePosition().y();
            baseZ = semantic.source().relativePosition().z();
        }

        // 4) 是否启用语义换皮
        boolean semanticSkin = getBool(reqMap, "semantic_skin", "semanticSkin");
        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) {
            semanticStyleId = resolveSemanticStyleId(semantic.styleProfile());
        }

        // 5) 展开为 patches（相对 slot anchor）
        List<BlockPatch> out = new ArrayList<>(def.blocks.size());
        long worldSeed = world.getSeed();

        // 5.1) 可选：先 carve（开洞/清洞）
        // - 默认：DOOR/WINDOW 会 carve，其他不 carve（可用 carve=false 关闭）
        boolean carve = shouldCarve(reqMap, def);
        if (carve) {
            SocketMask mask = resolveMask(reqMap, def);
            if (mask != null && mask.w > 0 && mask.h > 0 && mask.d > 0) {
                BlockPos maskOrigin = resolveMaskOrigin(reqMap, def);
                for (int x = 0; x < mask.w; x++) {
                    for (int y = 0; y < mask.h; y++) {
                        for (int z = 0; z < mask.d; z++) {
                            BlockPos local = new BlockPos(maskOrigin.getX() + x, maskOrigin.getY() + y, maskOrigin.getZ() + z);
                            BlockPos off = ComponentTransformUtil.transformOffset(local, fromFacing, transform);
                            out.add(new BlockPatch(BlockPatch.REMOVE,
                                    baseX + off.getX(),
                                    baseY + off.getY(),
                                    baseZ + off.getZ(),
                                    "minecraft:air"));
                        }
                    }
                }
            }
        }

        int minDy = Integer.MAX_VALUE;
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            minDy = Math.min(minDy, be.dy);
        }
        if (minDy == Integer.MAX_VALUE) minDy = 0;

        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            BlockPos local = new BlockPos(be.dx, be.dy, be.dz);
            BlockPos off = ComponentTransformUtil.transformOffset(local, fromFacing, transform);

            int dx = baseX + off.getX();
            int dy = baseY + off.getY();
            int dz = baseZ + off.getZ();

            String block;
            if (semanticSkin) {
                // B：兼容旧构件（没有 semantic 字段）——服务端兜底 AUTO 推断
                com.formacraft.common.semantic.SemanticPart part = be.semantic != null
                        ? be.semantic
                        : guessSemanticPartFromString(be.block, be.dy, minDy);

                long seed = mixSeed(worldSeed, dx, dy, dz, part.ordinal());
                BlockState picked = SemanticBlockStatePicker.pick(semanticStyleId, part, seed);

                // 若原始 blockstate 带有 facing，则把 facing 迁移到“换皮后”的方块上
                Direction capturedFacing = BlockStateStringUtil.extractFacing(be.block);
                if (capturedFacing != null) {
                    Direction tf = FacingTransformUtil.transformFacing(capturedFacing, fromFacing, transform);
                    picked = BlockStatePropertyUtil.applyFacing(picked, tf);
                }
                block = BlockStateStringUtil.fromState(picked);
            } else {
                if (be.block == null || be.block.isBlank()) continue;
                block = BlockStateStringUtil.withTransformedFacing(be.block, fromFacing, transform);
            }

            out.add(new BlockPatch(BlockPatch.PLACE, dx, dy, dz, block));
        }
        return out;
    }

    private static List<BlockPatch> expandMount(SemanticComponent semantic, ServerWorld world, Path worldDir, Map<String, Object> reqMap) {
        // host / mount request maps
        Object host0 = reqMap.get("host");
        Object mount0 = reqMap.get("mount");
        if (!(host0 instanceof Map<?, ?>) || !(mount0 instanceof Map<?, ?>)) {
            // 兼容扁平字段：host_xxx / mount_xxx
            host0 = reqMap;
            mount0 = reqMap;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> hostMap = (Map<String, Object>) host0;
        @SuppressWarnings("unchecked")
        Map<String, Object> mountMap = (Map<String, Object>) mount0;

        ComponentDefinition host = resolveComponent(worldDir, hostMap, "host_");
        ComponentDefinition mount = resolveComponent(worldDir, mountMap, "mount_");
        if (host == null || host.blocks == null || host.blocks.isEmpty()) return List.of();
        if (mount == null || mount.blocks == null || mount.blocks.isEmpty()) return List.of();

        // host transform（facing/mirror）
        Direction hostFromFacing = parseDir(host.anchor != null ? host.anchor.facing : null);
        if (hostFromFacing == null || !hostFromFacing.getAxis().isHorizontal()) hostFromFacing = Direction.SOUTH;

        Direction hostTargetFacing = parseDir(getString(reqMap, "facing", "target_facing", "host_facing", "host_target_facing"));
        if (hostTargetFacing == null || !hostTargetFacing.getAxis().isHorizontal()) {
            hostTargetFacing = facingFromSlot(semantic);
        }
        if (hostTargetFacing == null || !hostTargetFacing.getAxis().isHorizontal()) hostTargetFacing = Direction.SOUTH;

        Mirror hostMirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode", "host_mirror", "hostMirror"));
        ComponentTransform hostTransform = new ComponentTransform(hostTargetFacing, hostMirror);

        // base offset（相对 slot anchor）
        int baseX = 0, baseY = 0, baseZ = 0;
        if (semantic != null && semantic.source() != null && semantic.source().relativePosition() != null) {
            baseX = semantic.source().relativePosition().x();
            baseY = semantic.source().relativePosition().y();
            baseZ = semantic.source().relativePosition().z();
        }

        // style / semantic skin（可分别覆盖 host/mount）
        boolean semanticSkin = getBool(reqMap, "semantic_skin", "semanticSkin");
        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) semanticStyleId = resolveSemanticStyleId(semantic != null ? semantic.styleProfile() : null);

        Boolean hostSkin0 = getBoolNullable(reqMap, "host_semantic_skin", "hostSemanticSkin");
        Boolean mountSkin0 = getBoolNullable(reqMap, "mount_semantic_skin", "mountSemanticSkin");
        boolean hostSkin = hostSkin0 != null ? hostSkin0 : semanticSkin;
        boolean mountSkin = mountSkin0 != null ? mountSkin0 : semanticSkin;

        String hostStyleId = getString(reqMap, "host_semantic_style_id", "hostSemanticStyleId");
        String mountStyleId = getString(reqMap, "mount_semantic_style_id", "mountSemanticStyleId");
        if (hostStyleId == null) hostStyleId = semanticStyleId;
        if (mountStyleId == null) mountStyleId = semanticStyleId;

        // socket
        String socketId = getString(reqMap, "socket_id", "socketId");
        ComponentSocket socket = findSocket(host, socketId);
        if (socket == null) {
            FormacraftMod.LOGGER.warn("PlayerComponentExpander: mount requested but socket_id not found: {}", socketId);
            return List.of();
        }

        // socket world offset and facing (in host placement space)
        BlockPos socketLocal = new BlockPos(socket.x(), socket.y(), socket.z());
        BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, hostFromFacing, hostTransform);

        Direction socketLocalFacing = parseDir(socket.facing());
        if (socketLocalFacing == null || !socketLocalFacing.getAxis().isHorizontal()) socketLocalFacing = Direction.SOUTH;
        Direction socketWorldFacing = FacingTransformUtil.transformFacing(socketLocalFacing, hostFromFacing, hostTransform);
        if (socketWorldFacing == null || !socketWorldFacing.getAxis().isHorizontal()) socketWorldFacing = hostTargetFacing;

        // mount transform：对齐到 socketWorldFacing
        Direction mountFromFacing = parseDir(mount.anchor != null ? mount.anchor.facing : null);
        if (mountFromFacing == null || !mountFromFacing.getAxis().isHorizontal()) mountFromFacing = Direction.SOUTH;
        Direction mountTargetFacing = parseDir(getString(reqMap, "mount_facing", "mountFacing"));
        if (mountTargetFacing == null || !mountTargetFacing.getAxis().isHorizontal()) mountTargetFacing = socketWorldFacing;
        Mirror mountMirror = parseMirror(getString(reqMap, "mount_mirror", "mountMirror"));
        ComponentTransform mountTransform = new ComponentTransform(mountTargetFacing, mountMirror);

        // carve mask defaults from socket
        boolean carve = shouldCarve(reqMap, host);
        SocketMask mask = carve ? new SocketMask(socket.width(), socket.height(), socket.depth()) : null;
        BlockPos maskOrigin = socketLocal;

        List<BlockPatch> out = new ArrayList<>(host.blocks.size() + mount.blocks.size() + 64);

        // 1) place host
        addComponentPatches(out, host, hostFromFacing, hostTransform, baseX, baseY, baseZ, hostSkin, hostStyleId, world.getSeed());

        // 2) carve
        if (carve && mask != null && mask.w > 0 && mask.h > 0 && mask.d > 0) {
            for (int x = 0; x < mask.w; x++) {
                for (int y = 0; y < mask.h; y++) {
                    for (int z = 0; z < mask.d; z++) {
                        BlockPos local = new BlockPos(maskOrigin.getX() + x, maskOrigin.getY() + y, maskOrigin.getZ() + z);
                        BlockPos off = ComponentTransformUtil.transformOffset(local, hostFromFacing, hostTransform);
                        out.add(new BlockPatch(BlockPatch.REMOVE,
                                baseX + off.getX(),
                                baseY + off.getY(),
                                baseZ + off.getZ(),
                                "minecraft:air"));
                    }
                }
            }
        }

        // 3) place mount at socket anchor
        int mountBaseX = baseX + socketOff.getX();
        int mountBaseY = baseY + socketOff.getY();
        int mountBaseZ = baseZ + socketOff.getZ();
        addComponentPatches(out, mount, mountFromFacing, mountTransform, mountBaseX, mountBaseY, mountBaseZ, mountSkin, mountStyleId, world.getSeed());

        return out;
    }

    private static void addComponentPatches(List<BlockPatch> out,
                                           ComponentDefinition def,
                                           Direction fromFacing,
                                           ComponentTransform transform,
                                           int baseX, int baseY, int baseZ,
                                           boolean semanticSkin,
                                           String semanticStyleId,
                                           long seedBase) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return;
        int minDy = Integer.MAX_VALUE;
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            minDy = Math.min(minDy, be.dy);
        }
        if (minDy == Integer.MAX_VALUE) minDy = 0;

        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            BlockPos local = new BlockPos(be.dx, be.dy, be.dz);
            BlockPos off = ComponentTransformUtil.transformOffset(local, fromFacing, transform);

            int dx = baseX + off.getX();
            int dy = baseY + off.getY();
            int dz = baseZ + off.getZ();

            String block;
            if (semanticSkin) {
                com.formacraft.common.semantic.SemanticPart part = be.semantic != null
                        ? be.semantic
                        : guessSemanticPartFromString(be.block, be.dy, minDy);
                long seed = mixSeed(seedBase, dx, dy, dz, part.ordinal());
                BlockState picked = SemanticBlockStatePicker.pick(semanticStyleId, part, seed);

                Direction capturedFacing = BlockStateStringUtil.extractFacing(be.block);
                if (capturedFacing != null) {
                    Direction tf = FacingTransformUtil.transformFacing(capturedFacing, fromFacing, transform);
                    picked = BlockStatePropertyUtil.applyFacing(picked, tf);
                }
                block = BlockStateStringUtil.fromState(picked);
            } else {
                if (be.block == null || be.block.isBlank()) continue;
                block = BlockStateStringUtil.withTransformedFacing(be.block, fromFacing, transform);
            }
            out.add(new BlockPatch(BlockPatch.PLACE, dx, dy, dz, block));
        }
    }

    private static ComponentSocket findSocket(ComponentDefinition def, String socketId) {
        if (def == null || def.sockets == null || def.sockets.isEmpty()) return null;
        if (socketId == null || socketId.isBlank()) return null;
        for (ComponentSocket s : def.sockets) {
            if (s == null || s.id() == null) continue;
            if (socketId.equals(s.id())) return s;
        }
        return null;
    }

    private static boolean isMountRequest(Map<String, Object> reqMap) {
        if (reqMap == null) return false;
        if (reqMap.get("mount") != null || reqMap.get("host") != null) return true;
        // 扁平字段
        return reqMap.get("host_id") != null
                || reqMap.get("mount_id") != null
                || reqMap.get("socket_id") != null;
    }

    private static ComponentDefinition resolveComponent(Path worldDir, Map<String, Object> reqMap, String prefix) {
        if (worldDir == null || reqMap == null) return null;
        String px = prefix == null ? "" : prefix;
        String explicitId = getString(reqMap, px + "id", px + "component_id", px + "componentId");
        if (explicitId != null) {
            return ComponentStorage.loadComponent(worldDir, explicitId);
        }
        ComponentRequest req = parseRequestWithPrefix(reqMap, px);
        return ComponentLibrary.findBest(worldDir, req);
    }

    private static ComponentRequest parseRequestWithPrefix(Map<String, Object> reqMap, String prefix) {
        if (prefix == null) prefix = "";
        ComponentRequest req = new ComponentRequest();
        String cat = getString(reqMap, prefix + "category", prefix + "type");
        if (cat != null) {
            try {
                req.category = ComponentCategory.valueOf(cat.trim().toUpperCase(Locale.ROOT));
            } catch (Throwable ignored) {
                req.category = null;
            }
        }
        Set<String> tags = parseTags(reqMap.get(prefix + "tags"));
        req.tags = tags.isEmpty() ? null : tags;
        Object approx = reqMap.get(prefix + "approx_size");
        if (approx instanceof Map<?, ?> am) {
            req.approxW = getInt(am, -1, "w", "width");
            req.approxH = getInt(am, -1, "h", "height");
            req.approxD = getInt(am, -1, "d", "depth");
        } else {
            req.approxW = getInt(reqMap, -1, prefix + "approxW", prefix + "approx_w");
            req.approxH = getInt(reqMap, -1, prefix + "approxH", prefix + "approx_h");
            req.approxD = getInt(reqMap, -1, prefix + "approxD", prefix + "approx_d");
        }
        return req;
    }

    private static boolean shouldCarve(Map<String, Object> reqMap, ComponentDefinition def) {
        Boolean explicit = getBoolNullable(reqMap, "carve", "carve_mask", "carveMask", "carve_socket", "carveSocket");
        if (explicit != null) return explicit;
        // 如果显式给了 socket_id，默认 carve=true
        String socketId = getString(reqMap, "socket_id", "socketId");
        if (socketId != null && def != null && def.sockets != null) {
            for (ComponentSocket s : def.sockets) {
                if (s != null && socketId.equals(s.id())) {
                    return true;
                }
            }
        }
        // 默认只对 DOOR/WINDOW 进行开洞
        String cat = getString(reqMap, "category", "type", "socket_type", "socketType");
        if (cat == null) return false;
        String u = cat.trim().toUpperCase(Locale.ROOT);
        return u.contains("DOOR") || u.contains("WINDOW");
    }

    private static BlockPos resolveMaskOrigin(Map<String, Object> reqMap, ComponentDefinition def) {
        Object o = reqMap.get("mask_origin");
        if (!(o instanceof Map<?, ?>)) o = reqMap.get("socket_origin");
        if (!(o instanceof Map<?, ?> mm)) {
            // 若提供 socket_id，则优先使用 def.sockets 中该 socket 的 origin
            String socketId = getString(reqMap, "socket_id", "socketId");
            if (socketId != null && def != null && def.sockets != null) {
                for (ComponentSocket s : def.sockets) {
                    if (s == null || s.id() == null) continue;
                    if (!socketId.equals(s.id())) continue;
                    return new BlockPos(s.x(), s.y(), s.z());
                }
            }
            return BlockPos.ORIGIN;
        }
        int x = getInt(mm, 0, "x");
        int y = getInt(mm, 0, "y");
        int z = getInt(mm, 0, "z");
        return new BlockPos(x, y, z);
    }

    private static SocketMask resolveMask(Map<String, Object> reqMap, ComponentDefinition def) {
        // 1) 显式 mask
        Object m0 = reqMap.get("mask");
        if (!(m0 instanceof Map<?, ?>)) m0 = reqMap.get("socket_mask");
        if (m0 instanceof Map<?, ?> m) {
            int w = getInt(m, -1, "w", "width");
            int h = getInt(m, -1, "h", "height");
            int d = getInt(m, -1, "d", "depth");
            if (w > 0 && h > 0 && d > 0) return new SocketMask(w, h, d);
        }

        // 2) 如果给了 socket_id，并且 def.sockets 中存在，则用 socket 自带尺寸
        String socketId = getString(reqMap, "socket_id", "socketId");
        if (socketId != null && def != null && def.sockets != null) {
            for (ComponentSocket s : def.sockets) {
                if (s == null || s.id() == null) continue;
                if (!socketId.equals(s.id())) continue;
                if (s.width() > 0 && s.height() > 0 && s.depth() > 0) {
                    return new SocketMask(s.width(), s.height(), s.depth());
                }
            }
        }

        // 3) 默认（按类型）
        String type = getString(reqMap, "socket_type", "socketType", "category", "type");
        if (type == null) return null;
        SocketType t = parseSocketType(type);
        if (t == SocketType.DOOR) return new SocketMask(2, 3, 1);
        if (t == SocketType.WINDOW) return new SocketMask(2, 2, 1);
        if (t == SocketType.BALCONY) return new SocketMask(3, 2, 1);
        return null;
    }

    private static SocketType parseSocketType(String s) {
        if (s == null || s.isBlank()) return SocketType.DECORATION;
        try {
            return SocketType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            String u = s.trim().toUpperCase(Locale.ROOT);
            if (u.contains("DOOR")) return SocketType.DOOR;
            if (u.contains("WINDOW")) return SocketType.WINDOW;
            if (u.contains("BALCONY")) return SocketType.BALCONY;
            if (u.contains("ROOF")) return SocketType.ROOF_ATTACHMENT;
            return SocketType.DECORATION;
        }
    }

    private record SocketMask(int w, int h, int d) {}

    private static com.formacraft.common.semantic.SemanticPart guessSemanticPartFromString(String blockStateString, int dy, int minDy) {
        if (dy == minDy) return com.formacraft.common.semantic.SemanticPart.FOUNDATION;
        if (blockStateString == null || blockStateString.isBlank()) return com.formacraft.common.semantic.SemanticPart.WALL;
        String s = blockStateString.toLowerCase(Locale.ROOT);

        if (s.contains("door") || s.contains("trapdoor")) return com.formacraft.common.semantic.SemanticPart.DOORWAY;
        if (s.contains("glass_pane") || s.contains("stained_glass_pane") || s.contains(":glass")) return com.formacraft.common.semantic.SemanticPart.WINDOW;
        if (s.contains("fence") || s.contains("iron_bars") || s.contains("bars")) return com.formacraft.common.semantic.SemanticPart.RAILING;
        if (s.contains("lantern") || s.contains("torch")) return com.formacraft.common.semantic.SemanticPart.LIGHT;
        if (s.contains("stairs")) return com.formacraft.common.semantic.SemanticPart.STAIR_STEP;
        if (s.contains("slab")) return com.formacraft.common.semantic.SemanticPart.FLOOR;
        if (s.contains("log") || s.contains("stem")) return com.formacraft.common.semantic.SemanticPart.PILLAR;

        return com.formacraft.common.semantic.SemanticPart.WALL;
    }

    private static String extractFeatureJson(List<String> features) {
        if (features == null) return null;
        for (String f : features) {
            if (f == null) continue;
            String s = f.trim();
            if (s.isEmpty()) continue;
            if (s.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                String json = s.substring(PREFIX.length()).trim();
                return json.isEmpty() ? null : json;
            }
        }
        return null;
    }

    // reserved: keep parseRequestWithPrefix as the only entry

    private static Set<String> parseTags(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v == null) return out;
        if (v instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        String s = String.valueOf(v);
        if (s == null) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String resolveSemanticStyleId(String styleProfile) {
        if (styleProfile != null) {
            String s = styleProfile.trim();
            if (!s.isEmpty() && SemanticStyleProfileRegistry.get(s) != null) {
                return s;
            }
        }
        return "DEFAULT";
    }

    private static Direction facingFromSlot(SemanticComponent semantic) {
        if (semantic == null || semantic.slot() == null || semantic.slot().facing() == null) return null;
        return switch (semantic.slot().facing()) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    private static Direction parseDir(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Direction.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Mirror parseMirror(String s) {
        if (s == null || s.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return Mirror.NONE;
        }
    }

    private static String getString(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static boolean getBool(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return true;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        }
        return true;
    }

    private static Boolean getBoolNullable(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        }
        return null;
    }

    private static int getInt(Map<?, ?> m, int def, String... keys) {
        if (m == null || keys == null) return def;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private static long mixSeed(long base, int x, int y, int z, int t) {
        long h = 1469598103934665603L ^ base;
        h ^= x; h *= 1099511628211L;
        h ^= y; h *= 1099511628211L;
        h ^= z; h *= 1099511628211L;
        h ^= t; h *= 1099511628211L;
        return h;
    }
}

