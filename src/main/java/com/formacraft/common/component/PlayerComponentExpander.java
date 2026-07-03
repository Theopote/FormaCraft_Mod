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
import com.formacraft.common.component.fallback.FallbackMaterialConfig;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.FacingUtil;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.socket.*;
import com.formacraft.common.component.placement.AttachmentRecognizer;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.FacingDeriver;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
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
        if (def == null || def.blocks == null || def.blocks.isEmpty()) {
            ComponentQuery query = parseComponentQuery(reqMap, null);
            if (query != null) {
                List<BlockPatch> fallback = generateFallbackPatches(semantic, query);
                if (!fallback.isEmpty()) {
                    FormacraftMod.LOGGER.debug("PlayerComponentExpander: fallback generated for component_request");
                    return fallback;
                }
            }
            return List.of();
        }

        // 2) 解析变换参数（facing/mirror）
        Direction fromFacing = parseDir(def.anchor != null ? def.anchor.facing : null);
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) fromFacing = Direction.SOUTH;

        String explicitFacingStr = getString(reqMap, "facing", "target_facing");
        Direction targetFacing = parseDir(explicitFacingStr);
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) {
            targetFacing = facingFromSlot(semantic);
        }
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) targetFacing = Direction.SOUTH;

        // PlacementSpec v1：根据 FacingPolicy 推导（仅当未显式指定 facing）
        try {
            if (explicitFacingStr == null || explicitFacingStr.isBlank()) {
                targetFacing = FacingDeriver.derive(
                        def.placementSpec,
                        targetFacing, // host-like hint：slot facing（若有）
                        targetFacing,
                        reqMap        // 支持 edge hint（ALONG_EDGE）
                );
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] FacingDeriver failed componentId={}", def.id, t);
        }

        // PlacementSpec v1：当构件不需要方向（FacingPolicy.NONE）且用户未显式指定 facing 时，保持原朝向（不做旋转）
        try {
            if ((explicitFacingStr == null || explicitFacingStr.isBlank())
                    && def.placementSpec != null
                    && def.placementSpec.facingPolicy == FacingPolicy.NONE) {
                targetFacing = fromFacing;
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] FacingPolicy.NONE handling failed componentId={}", def.id, t);
        }

        Mirror mirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode"));
        ComponentTransform transform = new ComponentTransform(targetFacing, mirror);

        // 3) 放置偏移（相对于 slot anchor）
        BlockPos baseOffset = resolveBaseOffset(semantic);
        int baseX = baseOffset.getX();
        int baseY = baseOffset.getY();
        int baseZ = baseOffset.getZ();

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
            if (mask != null && mask.width() > 0 && mask.height() > 0 && mask.depth() > 0) {
                // mask_origin 为“局部 socket 原点”（相对 anchor 的局部坐标）
                BlockPos maskOriginLocal = resolveMaskOrigin(reqMap, def);
                BlockPos originOff = ComponentTransformUtil.transformOffset(maskOriginLocal, fromFacing, transform);
                BlockPos originWorld = new BlockPos(baseX + originOff.getX(), baseY + originOff.getY(), baseZ + originOff.getZ());

                // 沿“最终朝向 targetFacing”清洞（符合 SocketSystem 语义：depth 方向跟随 facing）
                for (int x = 0; x < mask.width(); x++) {
                    for (int y = 0; y < mask.height(); y++) {
                        for (int z = 0; z < mask.depth(); z++) {
                            BlockPos p = FacingUtil.offset(originWorld, targetFacing, x, y, z);
                            out.add(new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
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
        BlockPos baseOffset = resolveBaseOffset(semantic);
        int baseX = baseOffset.getX();
        int baseY = baseOffset.getY();
        int baseZ = baseOffset.getZ();

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

        // placementSpec 过滤：避免把“需要 WALL_OPENING 的门”装到非 opening 的 socket 上
        AttachmentType hostAttachment = AttachmentRecognizer.attachmentForSocketContext(socket.context);
        if (!AttachmentRecognizer.isCompatible(mount.placementSpec, hostAttachment)) {
            FormacraftMod.LOGGER.warn("PlayerComponentExpander: mount placementSpec incompatible: mount={} need={} hostSocketContext={} hostAttachment={}",
                    mount.id, mount.placementSpec.attachment,
                    socket.context, hostAttachment);
            // 仅放置 host；不 carve、不放置 mount（避免破坏结构）
            List<BlockPatch> onlyHost = new ArrayList<>(host.blocks.size() + 16);
            addComponentPatches(onlyHost, host, hostFromFacing, hostTransform, baseX, baseY, baseZ, hostSkin, hostStyleId, world.getSeed());
            return onlyHost;
        }

        // socket world offset and facing (in host placement space)
        // v1 新版 Socket 不包含坐标，返回原点（旧行为兼容）
        BlockPos socketLocal = BlockPos.ORIGIN;
        BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, hostFromFacing, hostTransform);

        // 新版 Socket 不包含 facing()，使用默认朝向
        Direction socketLocalFacing = Direction.SOUTH;
        Direction socketWorldFacing = FacingTransformUtil.transformFacing(socketLocalFacing, hostFromFacing, hostTransform);
        if (socketWorldFacing == null || !socketWorldFacing.getAxis().isHorizontal()) socketWorldFacing = hostTargetFacing;

        // mount transform：对齐到 socketWorldFacing
        Direction mountFromFacing = parseDir(mount.anchor != null ? mount.anchor.facing : null);
        if (mountFromFacing == null || !mountFromFacing.getAxis().isHorizontal()) mountFromFacing = Direction.SOUTH;
        String explicitMountFacingStr = getString(reqMap, "mount_facing", "mountFacing");
        Direction mountTargetFacing = parseDir(explicitMountFacingStr);
        if (mountTargetFacing == null || !mountTargetFacing.getAxis().isHorizontal()) mountTargetFacing = socketWorldFacing;
        // PlacementSpec v1：根据 FacingPolicy 推导（仅当未显式指定 mount_facing）
        try {
            if (explicitMountFacingStr == null || explicitMountFacingStr.isBlank()) {
                mountTargetFacing = FacingDeriver.derive(
                        mount.placementSpec,
                        socketWorldFacing,
                        mountTargetFacing,
                        reqMap
                );
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] mount FacingDeriver failed mountId={}", mount.id, t);
        }
        // PlacementSpec v1：mount 若不需要方向且未显式给 mount_facing，则保持构件自身朝向（只做平移/镜像）
        try {
            if ((explicitMountFacingStr == null || explicitMountFacingStr.isBlank())
                    && mount.placementSpec != null
                    && mount.placementSpec.facingPolicy == FacingPolicy.NONE) {
                mountTargetFacing = mountFromFacing;
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] mount FacingPolicy.NONE handling failed mountId={}", mount.id, t);
        }
        Mirror mountMirror = parseMirror(getString(reqMap, "mount_mirror", "mountMirror"));
        ComponentTransform mountTransform = new ComponentTransform(mountTargetFacing, mountMirror);

        // carve mask defaults from socket
        boolean carve = shouldCarve(reqMap, host);
        // v1 新版 Socket：从 size 约束中提取尺寸
        SocketMask mask = null;
        if (carve && socket.size != null && socket.size.min != null && socket.size.min.length >= 2) {
            int w = socket.size.min[0];
            int h = socket.size.min[1];
            mask = new SocketMask(w, h, 1); // depth=1（默认）
        }

        List<BlockPatch> out = new ArrayList<>(host.blocks.size() + mount.blocks.size() + 64);

        // 1) place host
        addComponentPatches(out, host, hostFromFacing, hostTransform, baseX, baseY, baseZ, hostSkin, hostStyleId, world.getSeed());

        // 2) carve
        if (mask != null && mask.width() > 0 && mask.height() > 0 && mask.depth() > 0) {
            BlockPos socketWorld = new BlockPos(baseX + socketOff.getX(), baseY + socketOff.getY(), baseZ + socketOff.getZ());

            // 沿 socket 的“世界朝向”清洞（depth 方向跟随 socketWorldFacing）
            for (int x = 0; x < mask.width(); x++) {
                for (int y = 0; y < mask.height(); y++) {
                    for (int z = 0; z < mask.depth(); z++) {
                        BlockPos p = FacingUtil.offset(socketWorld, socketWorldFacing, x, y, z);
                        out.add(new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
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

    private static BlockPos resolveBaseOffset(SemanticComponent semantic) {
        if (semantic == null || semantic.source() == null || semantic.source().relativePosition() == null) {
            return BlockPos.ORIGIN;
        }

        Component component = semantic.source();
        Vec3i rp = component.relativePosition();
        int baseX = rp.x();
        int baseY = rp.y();
        int baseZ = rp.z();

        if (shouldUseCenterAnchor(component)) {
            Dimensions dims = component.dimensions();
            if (dims != null) {
                baseX -= dims.width() / 2;
                baseZ -= dims.depth() / 2;
            }
        }

        return new BlockPos(baseX, baseY, baseZ);
    }

    private static boolean shouldUseCenterAnchor(Component component) {
        if (component == null) return false;
        if (isCornerAnchor(component)) return false;
        String type = component.componentType();
        if (type == null || type.isBlank()) return false;
        String upper = type.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("MASS_") || upper.equals("TOWER");
    }

    private static boolean isCornerAnchor(Component component) {
        Map<String, Object> params = component.params();
        String anchorMode = getString(params, "anchor_mode", "anchorMode");
        return anchorMode != null && anchorMode.toLowerCase(Locale.ROOT).contains("corner");
    }

    private static ComponentSocket findSocket(ComponentDefinition def, String socketId) {
        if (def == null || def.sockets == null || def.sockets.isEmpty()) return null;
        if (socketId == null || socketId.isBlank()) return null;
        for (ComponentSocket s : def.sockets) {
            if (s == null || s.id == null) continue;
            if (socketId.equals(s.id)) return s;
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
        ComponentQuery query = parseComponentQuery(reqMap, px);
        if (query != null) {
            // Phase 8：检索最佳匹配并自动生成变体（缩放/裁剪/换材质）。
            // 变体为运行时产物；恒等变体等价于旧 retrieveBest 行为。
            java.util.Random rng = new java.util.Random(variantSeed(query, px));
            com.formacraft.common.component.query.ComponentRetriever.VariantResult vr =
                    com.formacraft.common.component.query.ComponentRetriever.retrieveBestWithVariant(query, rng);
            if (vr != null && vr.base() != null) {
                ComponentDefinition applied =
                        com.formacraft.common.component.variant.ComponentVariantApplier.apply(vr.base(), vr.variant());
                return applied != null ? applied : vr.base();
            }
        }
        ComponentRequest req = parseRequestWithPrefix(reqMap, px);
        return ComponentLibrary.findBest(worldDir, req);
    }

    /**
     * 变体随机种子：由 query 语义 + prefix 派生，保证同一请求变体稳定（可复现）。
     */
    private static long variantSeed(ComponentQuery query, String prefix) {
        long seed = 1125899906842597L;
        if (prefix != null) seed = 31 * seed + prefix.hashCode();
        if (query != null && query.semantic != null) {
            if (query.semantic.role != null) seed = 31 * seed + query.semantic.role.hashCode();
            if (query.semantic.tags != null) seed = 31 * seed + query.semantic.tags.hashCode();
        }
        return seed;
    }

    private static ComponentQuery parseComponentQuery(Map<String, Object> reqMap, String prefix) {
        if (reqMap == null) return null;
        String px = prefix == null ? "" : prefix;
        Object cq = reqMap.get(px + "component_query");
        if (cq == null) cq = reqMap.get(px + "componentQuery");
        if (cq == null) return null;
        try {
            String qjson = JsonUtil.toJson(cq);
            return JsonUtil.get().fromJson(qjson, ComponentQuery.class);
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("PlayerComponentExpander: invalid component_query json: {}", cq);
            return null;
        }
    }

    private static List<BlockPatch> generateFallbackPatches(SemanticComponent semantic, ComponentQuery query) {
        if (semantic == null || semantic.source() == null || query == null) return List.of();
        String role = (query.semantic != null && query.semantic.role != null)
                ? query.semantic.role.trim().toLowerCase(java.util.Locale.ROOT)
                : "";
        if (role.isEmpty()) return List.of();

        int baseX = 0, baseY = 0, baseZ = 0;
        if (semantic.source().relativePosition() != null) {
            baseX = semantic.source().relativePosition().x();
            baseY = semantic.source().relativePosition().y();
            baseZ = semantic.source().relativePosition().z();
        }
        int w = 1, d = 1, h = 4;
        if (semantic.source().dimensions() != null) {
            w = Math.max(1, semantic.source().dimensions().width());
            d = Math.max(1, semantic.source().dimensions().depth());
            h = Math.max(1, semantic.source().dimensions().height());
        }

        BlockState block = resolveFallbackBlock(semantic, query, role);
        String blockId = BlockStateStringUtil.fromState(block);

        List<BlockPatch> out = new ArrayList<>();
        if ("column".equals(role) || "pillar".equals(role)) {
            int spacing = 3;
            if (d <= 1) {
                for (int x = 0; x < w; x += spacing) {
                    addColumn(out, baseX + x, baseY, baseZ, h, blockId);
                }
                addColumn(out, baseX + w - 1, baseY, baseZ, h, blockId);
            } else if (w <= 1) {
                for (int z = 0; z < d; z += spacing) {
                    addColumn(out, baseX, baseY, baseZ + z, h, blockId);
                }
                addColumn(out, baseX, baseY, baseZ + d - 1, h, blockId);
            } else {
                addColumn(out, baseX, baseY, baseZ, h, blockId);
                addColumn(out, baseX + w - 1, baseY, baseZ, h, blockId);
                addColumn(out, baseX, baseY, baseZ + d - 1, h, blockId);
                addColumn(out, baseX + w - 1, baseY, baseZ + d - 1, h, blockId);
                if (w > 4) {
                    int midX = baseX + w / 2;
                    addColumn(out, midX, baseY, baseZ, h, blockId);
                    addColumn(out, midX, baseY, baseZ + d - 1, h, blockId);
                }
                if (d > 4) {
                    int midZ = baseZ + d / 2;
                    addColumn(out, baseX, baseY, midZ, h, blockId);
                    addColumn(out, baseX + w - 1, baseY, midZ, h, blockId);
                }
            }
            return out;
        }

        if ("railing".equals(role)) {
            int y = baseY;
            for (int x = 0; x < w; x++) {
                out.add(new BlockPatch(BlockPatch.PLACE, baseX + x, y, baseZ, blockId));
                if (d > 1) out.add(new BlockPatch(BlockPatch.PLACE, baseX + x, y, baseZ + d - 1, blockId));
            }
            for (int z = 0; z < d; z++) {
                out.add(new BlockPatch(BlockPatch.PLACE, baseX, y, baseZ + z, blockId));
                if (w > 1) out.add(new BlockPatch(BlockPatch.PLACE, baseX + w - 1, y, baseZ + z, blockId));
            }
            return out;
        }

        if ("door".equals(role)) {
            return generateDoorFallback(out, query, baseX, baseY, baseZ, w, d, h);
        }

        if ("window".equals(role)) {
            return generateWindowFallback(out, query, baseX, baseY, baseZ, w, d, h, blockId);
        }

        if ("ornament".equals(role) || "bracket".equals(role) || "canopy".equals(role)) {
            int x = baseX + (w / 2);
            int y = baseY + Math.max(0, h - 1);
            int z = baseZ + (d / 2);
            out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, blockId));
            return out;
        }

        return List.of();
    }

    private static void addColumn(List<BlockPatch> out, int x, int y, int z, int h, String blockId) {
        for (int dy = 0; dy < h; dy++) {
            out.add(new BlockPatch(BlockPatch.PLACE, x, y + dy, z, blockId));
        }
    }

    private static BlockState resolveFallbackBlock(SemanticComponent semantic, ComponentQuery query, String role) {
        String tone = null;
        if (query.style != null && query.style.materialTone != null) {
            tone = query.style.materialTone;
        }
        if (tone == null && semantic.styleAttributes() != null) {
            tone = semantic.styleAttributes().wallColor();
        }
        String styleProfile = null;
        if (query.style != null && query.style.styleProfile != null) {
            styleProfile = query.style.styleProfile;
        }
        if (styleProfile == null) {
            styleProfile = semantic.styleProfile();
        }
        String blockId = FallbackMaterialConfig.get().resolveBlockId(role, tone, styleProfile);
        return FallbackMaterialConfig.resolveBlockState(blockId);
    }

    private static List<BlockPatch> generateDoorFallback(List<BlockPatch> out,
                                                         ComponentQuery query,
                                                         int baseX, int baseY, int baseZ,
                                                         int w, int d, int h) {
        int openW = resolveOpeningWidth(query, 2, w);
        int openH = resolveOpeningHeight(query, 3, h);
        int x0 = baseX + Math.max(0, (w - openW) / 2);
        int y0 = baseY;
        int z0 = baseZ + Math.max(0, (d - 1) / 2);
        for (int x = 0; x < openW; x++) {
            for (int y = 0; y < openH; y++) {
                out.add(new BlockPatch(BlockPatch.REMOVE, x0 + x, y0 + y, z0, "minecraft:air"));
            }
        }
        return out;
    }

    private static List<BlockPatch> generateWindowFallback(List<BlockPatch> out,
                                                           ComponentQuery query,
                                                           int baseX, int baseY, int baseZ,
                                                           int w, int d, int h,
                                                           String blockId) {
        int openW = resolveOpeningWidth(query, 2, w);
        int openH = resolveOpeningHeight(query, 2, h);
        int x0 = baseX + Math.max(0, (w - openW) / 2);
        int y0 = resolveOpeningBaseY(query, baseY, h, openH);
        int z0 = baseZ + Math.max(0, (d - 1) / 2);

        boolean requiresOpening = query.geometry != null && query.geometry.requiresOpening;
        if (requiresOpening) {
            for (int x = 0; x < openW; x++) {
                for (int y = 0; y < openH; y++) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, x0 + x, y0 + y, z0, "minecraft:air"));
                }
            }
        }
        for (int x = 0; x < openW; x++) {
            for (int y = 0; y < openH; y++) {
                out.add(new BlockPatch(BlockPatch.PLACE, x0 + x, y0 + y, z0, blockId));
            }
        }
        return out;
    }

    private static int resolveOpeningWidth(ComponentQuery query, int fallback, int max) {
        int v = fallback;
        if (query.geometry != null && query.geometry.openingWidth != null && query.geometry.openingWidth > 0) {
            v = query.geometry.openingWidth;
        }
        return clamp(v, 1, max);
    }

    private static int resolveOpeningHeight(ComponentQuery query, int fallback, int max) {
        int v = fallback;
        if (query.geometry != null && query.geometry.openingHeight != null && query.geometry.openingHeight > 0) {
            v = query.geometry.openingHeight;
        }
        return clamp(v, 1, max);
    }

    private static int resolveOpeningBaseY(ComponentQuery query, int baseY, int h, int openH) {
        if (query.context != null && query.context.heightLevel != null) {
            String level = query.context.heightLevel.toLowerCase(java.util.Locale.ROOT);
            if (level.contains("roof")) {
                return baseY + Math.max(0, h - openH);
            }
            if (level.contains("mid")) {
                return baseY + Math.max(0, (h - openH) / 2);
            }
            if (level.contains("ground")) {
                return baseY;
            }
        }
        return baseY + Math.max(0, (h - openH) / 2);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static ComponentRequest parseRequestWithPrefix(Map<String, Object> reqMap, String prefix) {
        if (prefix == null) prefix = "";
        ComponentRequest req = new ComponentRequest();
        String cat = getString(reqMap, prefix + "category", prefix + "type");
        if (cat != null) {
            try {
                req.category = ComponentCategory.valueOf(cat.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                FormacraftMod.LOGGER.debug("[PlayerComponentExpander] invalid category: {}", cat);
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
                if (s != null && socketId.equals(s.id)) {
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
                    if (s == null || s.id == null) continue;
                    if (!socketId.equals(s.id)) continue;
                    // v1 新版 Socket 不包含坐标（由 SocketPlacement 提供），返回默认原点
                    return BlockPos.ORIGIN;
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
                if (s == null || s.id == null) continue;
                if (!socketId.equals(s.id)) continue;
                // v1 新版 Socket：从 size 约束中提取尺寸
                if (s.size != null && s.size.min != null && s.size.min.length >= 2) {
                    int w = s.size.min[0];
                    int h = s.size.min[1];
                    if (w > 0 && h > 0) {
                        return new SocketMask(w, h, 1); // depth=1（默认）
                    }
                }
            }
        }

        // 3) 默认（按类型）
        String type = getString(reqMap, "socket_type", "socketType", "category", "type");
        if (type == null) return null;
        String typeLower = type.toLowerCase();
        if (typeLower.contains("door")) return new SocketMask(2, 3, 1);
        if (typeLower.contains("window")) return new SocketMask(2, 2, 1);
        if (typeLower.contains("balcony")) return new SocketMask(3, 2, 1);
        if (typeLower.contains("gate")) return new SocketMask(4, 4, 1);
        if (typeLower.contains("arch")) return new SocketMask(3, 4, 1);
        return null;
    }

    private static SocketContext parseSocketContext(String s) {
        if (s == null || s.isBlank()) return SocketContext.WALL;
        String u = s.trim().toUpperCase(Locale.ROOT);
        if (u.contains("WALL")) return SocketContext.WALL;
        if (u.contains("EDGE")) return SocketContext.EDGE;
        if (u.contains("CORNER")) return SocketContext.CORNER;
        if (u.contains("ROOF")) return SocketContext.ROOF;
        if (u.contains("GROUND")) return SocketContext.GROUND;
        if (u.contains("INTERIOR")) return SocketContext.INTERIOR;
        return SocketContext.WALL; // 默认墙面
    }

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
        } catch (IllegalArgumentException e) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] invalid direction: {}", s);
            return null;
        }
    }

    private static Mirror parseMirror(String s) {
        if (s == null || s.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] invalid mirror: {}", s);
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
            } catch (NumberFormatException e) {
                FormacraftMod.LOGGER.debug("[PlayerComponentExpander] invalid int for keys {}", java.util.Arrays.toString(keys));
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

