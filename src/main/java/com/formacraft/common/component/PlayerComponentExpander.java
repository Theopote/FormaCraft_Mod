package com.formacraft.common.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
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
import com.formacraft.common.component.socket.SocketPlacementResolver;
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
        ComponentGenerationOutcome outcome = tryExpandResult(semantic, world);
        if (outcome == null) return null;
        return outcome.patches();
    }

    /** Structured expand; prefer this over {@link #tryExpand} when diagnostics matter. */
    public static ComponentGenerationOutcome tryExpandResult(SemanticComponent semantic, ServerWorld world) {
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
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.INVALID_INPUT, "invalid_json", "component_request_parse_failed");
        }
        if (reqMap == null) {
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.INVALID_INPUT, "null_request", "reqMap_null");
        }

        Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);

        if (isMountRequest(reqMap)) {
            return expandMountResult(semantic, world, worldDir, reqMap);
        }

        return expandSingleResult(semantic, world, worldDir, reqMap);
    }

    private static ComponentGenerationOutcome expandSingleResult(
            SemanticComponent semantic, ServerWorld world, Path worldDir, Map<String, Object> reqMap) {
        com.formacraft.common.component.query.ComponentResolveResult resolved =
                resolveSingleRequest(semantic.source(), worldDir, reqMap);
        if (!resolved.ok()) {
            if (resolved.status() == com.formacraft.common.component.query.ComponentResolveResult.Status.ADAPT_FAILED) {
                FormacraftMod.LOGGER.error(
                        "PlayerComponentExpander: library resolve failed status={} id={} message={} (no procedural fallback)",
                        resolved.status(), resolved.sourceId(), resolved.message());
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.ADAPT_FAILED, "adapt_failed", resolved.message());
            }
            if (resolved.status() == com.formacraft.common.component.query.ComponentResolveResult.Status.LOAD_FAILED) {
                FormacraftMod.LOGGER.error(
                        "PlayerComponentExpander: library resolve failed status={} id={} message={} (no procedural fallback)",
                        resolved.status(), resolved.sourceId(), resolved.message());
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.LOAD_FAILED, "load_failed", resolved.message());
            }
            ComponentQuery query = parseComponentQuery(reqMap, null);
            if (query == null) {
                query = com.formacraft.common.component.query.ComponentRequestConverter.fromLegacyMap(reqMap, "");
            }
            if (query != null) {
                List<BlockPatch> fallback = generateFallbackPatches(semantic, query);
                if (!fallback.isEmpty()) {
                    FormacraftMod.LOGGER.debug("PlayerComponentExpander: fallback generated for component_request");
                    return ComponentGenerationOutcome.success(fallback);
                }
            }
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.NO_CANDIDATE, "no_candidate", resolved.message());
        }
        ComponentDefinition def = resolved.definition();

        Direction fromFacing = parseDir(def.anchor != null ? def.anchor.facing : null);
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) fromFacing = Direction.SOUTH;

        String explicitFacingStr = getString(reqMap, "facing", "target_facing");
        Direction targetFacing = parseDir(explicitFacingStr);
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) {
            targetFacing = facingFromSlot(semantic);
        }
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) targetFacing = Direction.SOUTH;

        try {
            if (explicitFacingStr == null || explicitFacingStr.isBlank()) {
                targetFacing = FacingDeriver.derive(def.placementSpec, targetFacing, targetFacing, reqMap);
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] FacingDeriver failed componentId={}", def.id, t);
        }

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

        BlockPos baseOffset = resolveBaseOffset(semantic);
        int baseX = baseOffset.getX();
        int baseY = baseOffset.getY();
        int baseZ = baseOffset.getZ();

        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) {
            semanticStyleId = resolveSemanticStyleId(semantic.styleProfile());
        }
        boolean semanticSkin = ComponentFeatureFlags.resolveSemanticSkin(
                reqMap, semanticStyleId, "semantic_skin", "semanticSkin");
        long worldSeed = world.getSeed();

        ResolvedComponentGeometry geo = ComponentGeometryResolver.emit(
                def, fromFacing, transform, baseX, baseY, baseZ, semanticSkin, semanticStyleId, worldSeed);
        if (!geo.ok()) {
            FormacraftMod.LOGGER.error(
                    "PlayerComponentExpander: emit failed id={} status={} message={}",
                    geo.sourceComponentId(), geo.status(), geo.message());
            return ComponentGenerationOutcome.failure(
                    mapGeoStatus(geo.status()), "emit_failed", geo.message());
        }

        ResolvedCarveVolume carveVol = ResolvedCarveVolume.none(def.id);
        if (shouldCarve(reqMap, def)) {
            SocketMask mask = resolveMask(reqMap, def);
            if (mask != null && mask.width() > 0 && mask.height() > 0 && mask.depth() > 0) {
                BlockPos maskOriginLocal = resolveMaskOrigin(reqMap, def);
                carveVol = resolveCarveVolume(def, null, mask, maskOriginLocal,
                        fromFacing, transform, baseX, baseY, baseZ, targetFacing, geo.patches(), null);
                if (!carveVol.ok()) {
                    FormacraftMod.LOGGER.error(
                            "PlayerComponentExpander: carve failed id={} message={}",
                            def.id, carveVol.message());
                    return ComponentGenerationOutcome.failure(
                            ComponentGenerationOutcome.Status.CARVE_FAILED, "carve_failed", carveVol.message());
                }
            }
        }

        List<BlockPatch> out = new ArrayList<>(geo.patches().size() + carveVol.cells().size());
        out.addAll(carveVol.toRemovePatches());
        out.addAll(geo.patches());
        return ComponentGenerationOutcome.success(out);
    }

    private static ComponentGenerationOutcome expandMountResult(
            SemanticComponent semantic, ServerWorld world, Path worldDir, Map<String, Object> reqMap) {
        Object host0 = reqMap.get("host");
        Object mount0 = reqMap.get("mount");
        if (!(host0 instanceof Map<?, ?>) || !(mount0 instanceof Map<?, ?>)) {
            host0 = reqMap;
            mount0 = reqMap;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> hostMap = (Map<String, Object>) host0;
        @SuppressWarnings("unchecked")
        Map<String, Object> mountMap = (Map<String, Object>) mount0;

        var hostResolved = resolveComponentResult(worldDir, hostMap, "host_");
        var mountResolved = resolveComponentResult(worldDir, mountMap, "mount_");
        if (!hostResolved.ok()) {
            FormacraftMod.LOGGER.error("PlayerComponentExpander: host resolve failed status={} id={} message={}",
                    hostResolved.status(), hostResolved.sourceId(), hostResolved.message());
            return ComponentGenerationOutcome.failure(mapResolveStatus(hostResolved.status()),
                    "host_resolve", hostResolved.message());
        }
        if (!mountResolved.ok()) {
            FormacraftMod.LOGGER.error("PlayerComponentExpander: mount resolve failed status={} id={} message={}",
                    mountResolved.status(), mountResolved.sourceId(), mountResolved.message());
            return ComponentGenerationOutcome.failure(mapResolveStatus(mountResolved.status()),
                    "mount_resolve", mountResolved.message());
        }
        ComponentDefinition host = hostResolved.definition();
        ComponentDefinition mount = mountResolved.definition();

        Direction hostFromFacing = parseDir(host.anchor != null ? host.anchor.facing : null);
        if (hostFromFacing == null || !hostFromFacing.getAxis().isHorizontal()) hostFromFacing = Direction.SOUTH;

        Direction hostTargetFacing = parseDir(getString(reqMap, "facing", "target_facing", "host_facing", "host_target_facing"));
        if (hostTargetFacing == null || !hostTargetFacing.getAxis().isHorizontal()) {
            hostTargetFacing = facingFromSlot(semantic);
        }
        if (hostTargetFacing == null || !hostTargetFacing.getAxis().isHorizontal()) hostTargetFacing = Direction.SOUTH;

        Mirror hostMirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode", "host_mirror", "hostMirror"));
        ComponentTransform hostTransform = new ComponentTransform(hostTargetFacing, hostMirror);

        BlockPos baseOffset = resolveBaseOffset(semantic);
        int baseX = baseOffset.getX();
        int baseY = baseOffset.getY();
        int baseZ = baseOffset.getZ();

        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) {
            semanticStyleId = resolveSemanticStyleId(semantic != null ? semantic.styleProfile() : null);
        }
        boolean semanticSkin = ComponentFeatureFlags.resolveSemanticSkin(
                reqMap, semanticStyleId, "semantic_skin", "semanticSkin");
        Boolean hostSkin0 = getBoolNullable(reqMap, "host_semantic_skin", "hostSemanticSkin");
        Boolean mountSkin0 = getBoolNullable(reqMap, "mount_semantic_skin", "mountSemanticSkin");
        boolean hostSkin = hostSkin0 != null ? hostSkin0 : semanticSkin;
        boolean mountSkin = mountSkin0 != null ? mountSkin0 : semanticSkin;

        String hostStyleId = getString(reqMap, "host_semantic_style_id", "hostSemanticStyleId");
        String mountStyleId = getString(reqMap, "mount_semantic_style_id", "mountSemanticStyleId");
        if (hostStyleId == null) hostStyleId = semanticStyleId;
        if (mountStyleId == null) mountStyleId = semanticStyleId;

        String socketId = getString(reqMap, "socket_id", "socketId");
        ComponentSocket socket = findSocket(host, socketId);
        if (socket == null) {
            FormacraftMod.LOGGER.error("PlayerComponentExpander: mount requested but socket_id not found: {}", socketId);
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.INVALID_INPUT, "socket_missing",
                    "socket_id_not_found:" + socketId);
        }

        AttachmentType hostAttachment = AttachmentRecognizer.attachmentForSocketContext(socket.context);
        boolean mountRequired = isMountRequired(reqMap);
        if (!AttachmentRecognizer.isCompatible(mount.placementSpec, hostAttachment)) {
            if (mountRequired) {
                FormacraftMod.LOGGER.error(
                        "PlayerComponentExpander: required mount incompatible mount={} need={} hostSocketContext={} hostAttachment={}",
                        mount.id, mount.placementSpec != null ? mount.placementSpec.attachment : null,
                        socket.context, hostAttachment);
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.INCOMPATIBLE_MOUNT, "incompatible_mount",
                        "mount_incompatible_with_host_socket");
            }
            FormacraftMod.LOGGER.warn(
                    "PlayerComponentExpander: degraded_optional_mount mount={} need={} hostAttachment={}",
                    mount.id, mount.placementSpec != null ? mount.placementSpec.attachment : null, hostAttachment);
            ResolvedComponentGeometry onlyHost = ComponentGeometryResolver.emit(
                    host, hostFromFacing, hostTransform, baseX, baseY, baseZ, hostSkin, hostStyleId, world.getSeed());
            if (!onlyHost.ok()) {
                return ComponentGenerationOutcome.failure(mapGeoStatus(onlyHost.status()),
                        "host_emit", onlyHost.message());
            }
            return ComponentGenerationOutcome.partialSuccess(
                    onlyHost.patches(),
                    "degraded_optional_mount",
                    "墙体已生成，但装饰构件无法安装到指定位置");
        }

        BlockPos socketLocal = com.formacraft.common.component.socket.SocketPlacementResolver
                .resolveLocalOrigin(host, socketId, socket);
        BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, hostFromFacing, hostTransform);

        Direction socketLocalFacing = com.formacraft.common.component.socket.SocketPlacementResolver
                .resolveLocalFacing(host, socketId, socket);
        Direction socketWorldFacing = FacingTransformUtil.transformFacing(socketLocalFacing, hostFromFacing, hostTransform);
        if (socketWorldFacing == null || !socketWorldFacing.getAxis().isHorizontal()) socketWorldFacing = hostTargetFacing;

        Direction mountFromFacing = parseDir(mount.anchor != null ? mount.anchor.facing : null);
        if (mountFromFacing == null || !mountFromFacing.getAxis().isHorizontal()) mountFromFacing = Direction.SOUTH;
        String explicitMountFacingStr = getString(reqMap, "mount_facing", "mountFacing");
        Direction mountTargetFacing = parseDir(explicitMountFacingStr);
        if (mountTargetFacing == null || !mountTargetFacing.getAxis().isHorizontal()) mountTargetFacing = socketWorldFacing;
        try {
            if (explicitMountFacingStr == null || explicitMountFacingStr.isBlank()) {
                mountTargetFacing = FacingDeriver.derive(
                        mount.placementSpec, socketWorldFacing, mountTargetFacing, reqMap);
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PlayerComponentExpander] mount FacingDeriver failed mountId={}", mount.id, t);
        }
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

        int mountBaseX = baseX + socketOff.getX();
        int mountBaseY = baseY + socketOff.getY();
        int mountBaseZ = baseZ + socketOff.getZ();
        long seed = world.getSeed();

        // Emit host + mount first (atomic: no patches returned until all stages ok).
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, hostFromFacing, hostTransform, baseX, baseY, baseZ, hostSkin, hostStyleId, seed);
        if (!hostGeo.ok()) {
            FormacraftMod.LOGGER.error("PlayerComponentExpander: host emit failed id={} status={} message={}",
                    hostGeo.sourceComponentId(), hostGeo.status(), hostGeo.message());
            return ComponentGenerationOutcome.failure(mapGeoStatus(hostGeo.status()), "host_emit", hostGeo.message());
        }
        ResolvedComponentGeometry mountGeo = ComponentGeometryResolver.emit(
                mount, mountFromFacing, mountTransform, mountBaseX, mountBaseY, mountBaseZ, mountSkin, mountStyleId, seed);
        if (!mountGeo.ok()) {
            FormacraftMod.LOGGER.error("PlayerComponentExpander: mount emit failed id={} status={} message={}",
                    mountGeo.sourceComponentId(), mountGeo.status(), mountGeo.message());
            return ComponentGenerationOutcome.failure(mapGeoStatus(mountGeo.status()), "mount_emit", mountGeo.message());
        }

        ResolvedCarveVolume carveVol = ResolvedCarveVolume.none(host.id);
        boolean wantCarve = shouldCarve(reqMap, host);
        if (wantCarve) {
            if (socket.size == null || socket.size.min == null || socket.size.min.length < 2) {
                FormacraftMod.LOGGER.error("PlayerComponentExpander: carve requested but socket size invalid socket={}", socketId);
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.CARVE_FAILED, "socket_size_invalid",
                        "carve_requires_socket_size");
            }
            SocketMask mask = new SocketMask(socket.size.min[0], socket.size.min[1], 1);
            carveVol = resolveCarveVolume(host, socket, mask, socketLocal,
                    hostFromFacing, hostTransform, baseX, baseY, baseZ, socketWorldFacing,
                    hostGeo.patches(), socketId);
            if (!carveVol.ok()) {
                FormacraftMod.LOGGER.error("PlayerComponentExpander: carve failed host={} socket={} message={}",
                        host.id, socketId, carveVol.message());
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.CARVE_FAILED, "carve_failed", carveVol.message());
            }
        }

        List<BlockPatch> out = new ArrayList<>(
                hostGeo.patches().size() + mountGeo.patches().size() + carveVol.cells().size());
        out.addAll(hostGeo.patches());
        out.addAll(carveVol.toRemovePatches());
        out.addAll(mountGeo.patches());
        return ComponentGenerationOutcome.success(out);
    }

    /** Default true whenever a mount is present; set mount_required=false for optional decor. */
    static boolean isMountRequired(Map<String, Object> reqMap) {
        Boolean explicit = getBoolNullable(reqMap, "mount_required", "mountRequired", "require_mount", "requireMount");
        if (explicit != null) return explicit;
        return true;
    }

    /**
     * Build and validate carve cells against an independently computed authorized socket volume.
     * Requested cells (mask + request origin) must be a subset of host-socket authorized cells.
     */
    static ResolvedCarveVolume resolveCarveVolume(
            ComponentDefinition host,
            ComponentSocket socket,
            SocketMask mask,
            BlockPos maskOriginLocal,
            Direction fromFacing,
            ComponentTransform transform,
            int baseX, int baseY, int baseZ,
            Direction carveFacing,
            List<BlockPatch> hostPlacePatches,
            String socketId
    ) {
        String hostId = host != null && host.id != null ? host.id : "";
        String sid = socketId != null ? socketId : "";
        if (mask == null || maskOriginLocal == null || transform == null) {
            return ResolvedCarveVolume.failure(hostId, sid, "carve_inputs_null");
        }
        if (mask.width() <= 0 || mask.height() <= 0 || mask.depth() <= 0) {
            return ResolvedCarveVolume.failure(hostId, sid, "carve_mask_nonpositive");
        }
        if (socket != null && !carveWithinSocketEnvelope(mask, socket)) {
            return ResolvedCarveVolume.failure(hostId, sid, "carve_exceeds_socket_envelope");
        }

        Set<BlockPos> authorized = authorizedSocketCarveCells(
                host, socket, sid, fromFacing, transform, baseX, baseY, baseZ, carveFacing);
        if (authorized.isEmpty() && socket != null) {
            return ResolvedCarveVolume.failure(hostId, sid, "authorized_socket_empty");
        }

        List<BlockPatch> removes = emitCarveRemoves(mask, maskOriginLocal, fromFacing, transform,
                baseX, baseY, baseZ, carveFacing);
        Set<BlockPos> requested = ResolvedCarveVolume.cellsFromRemoves(removes);
        if (requested.size() != mask.width() * mask.height() * mask.depth()) {
            return ResolvedCarveVolume.failure(hostId, sid, "carve_cell_count_mismatch");
        }

        for (BlockPos cell : requested) {
            if (!authorized.contains(cell)) {
                return ResolvedCarveVolume.failure(hostId, sid,
                        "CARVE_OUTSIDE_AUTHORIZED_SOCKET:" + cell.getX() + "," + cell.getY() + "," + cell.getZ());
            }
        }

        // Protected host PLACE cells must not be deleted by carve.
        if (hostPlacePatches != null && host != null) {
            Map<String, String> hostAt = new java.util.HashMap<>();
            for (BlockPatch p : hostPlacePatches) {
                if (p == null || !BlockPatch.PLACE.equals(p.action())) continue;
                hostAt.put(p.dx() + "," + p.dy() + "," + p.dz(), p.targetBlock());
            }
            for (BlockPos cell : requested) {
                String key = cell.getX() + "," + cell.getY() + "," + cell.getZ();
                String block = hostAt.get(key);
                if (block != null && isProtectedHostBlock(host, cell, fromFacing, transform, baseX, baseY, baseZ, block)) {
                    return ResolvedCarveVolume.failure(hostId, sid, "carve_hits_protected_host:" + key);
                }
            }
        }
        return ResolvedCarveVolume.success(hostId, sid, requested);
    }

    /**
     * Host-authoritative carve volume.
     * Prefer {@link ComponentDefinition.SocketPlacement#openingRegion}; else size.max opening box.
     * Independent of any request-side maskOrigin offset. Socket.size remains install-size only.
     */
    static Set<BlockPos> authorizedSocketCarveCells(
            ComponentDefinition host,
            ComponentSocket socket,
            String socketId,
            Direction fromFacing,
            ComponentTransform transform,
            int baseX, int baseY, int baseZ,
            Direction carveFacing
    ) {
        if (host == null || socket == null || transform == null) return Set.of();
        BlockPos authOrigin = SocketPlacementResolver.resolveLocalOrigin(host, socketId, socket);
        OpeningRegion region = resolveOpeningRegion(host, socketId);
        if (region != null && !region.isEmpty()) {
            return openingRegionAuthorizedCells(
                    region, authOrigin, fromFacing, transform, baseX, baseY, baseZ, carveFacing);
        }
        SocketMask authMask = authorizedSocketMask(socket);
        if (authMask == null) return Set.of();
        return ResolvedCarveVolume.cellsFromRemoves(emitCarveRemoves(
                authMask, authOrigin, fromFacing, transform, baseX, baseY, baseZ, carveFacing));
    }

    static OpeningRegion resolveOpeningRegion(ComponentDefinition host, String socketId) {
        if (host == null || host.socketPlacements == null || socketId == null) return null;
        for (ComponentDefinition.SocketPlacement sp : host.socketPlacements) {
            if (sp == null || sp.id == null) continue;
            if (socketId.equals(sp.id) && sp.openingRegion != null && !sp.openingRegion.isEmpty()) {
                return sp.openingRegion;
            }
        }
        return null;
    }

    /**
     * Box OpeningRegion shares emitCarveRemoves with request masks; explicit cells use transformOffset.
     */
    static Set<BlockPos> openingRegionAuthorizedCells(
            OpeningRegion region,
            BlockPos socketOriginLocal,
            Direction fromFacing,
            ComponentTransform transform,
            int baseX, int baseY, int baseZ,
            Direction carveFacing
    ) {
        if (region == null || transform == null || socketOriginLocal == null) return Set.of();
        // Explicit cells are sole authority (do not union with leftover AABB).
        if (region.hasExplicitCells()) {
            Set<BlockPos> out = new java.util.LinkedHashSet<>();
            for (int[] c : region.cells) {
                if (c == null || c.length < 3) continue;
                BlockPos absoluteLocal = socketOriginLocal.add(c[0], c[1], c[2]);
                BlockPos off = ComponentTransformUtil.transformOffset(absoluteLocal, fromFacing, transform);
                out.add(new BlockPos(baseX + off.getX(), baseY + off.getY(), baseZ + off.getZ()));
            }
            return out;
        }
        if (region.w > 0 && region.h > 0 && region.d > 0) {
            BlockPos boxOrigin = socketOriginLocal.add(region.ox, region.oy, region.oz);
            return ResolvedCarveVolume.cellsFromRemoves(emitCarveRemoves(
                    new SocketMask(region.w, region.h, region.d),
                    boxOrigin, fromFacing, transform, baseX, baseY, baseZ, carveFacing));
        }
        return Set.of();
    }

    static SocketMask authorizedSocketMask(ComponentSocket socket) {
        if (socket == null || socket.size == null) return null;
        int[] max = socket.size.max;
        int[] min = socket.size.min;
        int[] dims = finiteOpeningDims(max) ? max : min;
        if (dims == null || dims.length < 2) return null;
        int w = Math.max(1, dims[0]);
        int h = Math.max(1, dims[1]);
        int d = dims.length >= 3 ? Math.max(1, dims[2]) : 1;
        return new SocketMask(w, h, d);
    }

    /** Reject SizeConstraint sentinel max (Integer.MAX_VALUE) as an opening envelope. */
    private static boolean finiteOpeningDims(int[] dims) {
        if (dims == null || dims.length < 2) return false;
        final int cap = 256;
        return dims[0] > 0 && dims[0] <= cap
                && dims[1] > 0 && dims[1] <= cap
                && (dims.length < 3 || (dims[2] > 0 && dims[2] <= cap));
    }

    /** Carve REMOVEs share ComponentTransformUtil.transformOffset with PLACE. */
    static List<BlockPatch> emitCarveRemoves(SocketMask mask, BlockPos maskOriginLocal,
            Direction fromFacing, ComponentTransform transform,
            int baseX, int baseY, int baseZ, Direction carveFacing) {
        if (mask == null || maskOriginLocal == null || transform == null) return List.of();
        if (mask.width() <= 0 || mask.height() <= 0 || mask.depth() <= 0) return List.of();
        Direction depth = carveFacing != null && carveFacing.getAxis().isHorizontal()
                ? carveFacing : transform.facing();
        BlockPos originOff = ComponentTransformUtil.transformOffset(maskOriginLocal, fromFacing, transform);
        BlockPos originWorld = new BlockPos(baseX + originOff.getX(), baseY + originOff.getY(), baseZ + originOff.getZ());
        List<BlockPatch> removes = new ArrayList<>(mask.width() * mask.height() * mask.depth());
        for (int x = 0; x < mask.width(); x++) {
            for (int y = 0; y < mask.height(); y++) {
                for (int z = 0; z < mask.depth(); z++) {
                    BlockPos p = FacingUtil.offset(originWorld, depth, x, y, z);
                    removes.add(new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
                }
            }
        }
        return removes;
    }

    /** Carve volume must not exceed socket size.max envelope (width×height×depth). */
    static boolean carveWithinSocketEnvelope(SocketMask mask, ComponentSocket socket) {
        if (mask == null || socket == null || socket.size == null) return true;
        int[] max = socket.size.max;
        if (max == null || max.length < 2) return true;
        int maxW = max[0];
        int maxH = max[1];
        int maxD = max.length >= 3 ? max[2] : Math.max(1, mask.depth());
        if (maxW <= 0 || maxH <= 0) return true;
        return mask.width() <= maxW && mask.height() <= maxH && mask.depth() <= Math.max(1, maxD);
    }

    /**
     * Carve permission: only OPENING_FILL / WALL_FILL may be removed inside the opening.
     * FRAME / STRUCTURE / FOUNDATION / UNKNOWN are protected (UNKNOWN = default protect).
     */
    static boolean isProtectedHostBlock(ComponentDefinition host, BlockPos worldCell,
            Direction fromFacing, ComponentTransform transform,
            int baseX, int baseY, int baseZ, String placedBlock) {
        if (host == null || host.blocks == null) {
            return resolveCarveRole(null, placedBlock).protectedFromCarve();
        }
        for (ComponentDefinition.BlockEntry be : host.blocks) {
            if (be == null) continue;
            BlockPos off = ComponentTransformUtil.transformOffset(
                    new BlockPos(be.dx, be.dy, be.dz), fromFacing, transform);
            if (baseX + off.getX() != worldCell.getX()
                    || baseY + off.getY() != worldCell.getY()
                    || baseZ + off.getZ() != worldCell.getZ()) {
                continue;
            }
            return resolveCarveRole(be, placedBlock != null ? placedBlock : be.block).protectedFromCarve();
        }
        return resolveCarveRole(null, placedBlock).protectedFromCarve();
    }

    enum CarveRole {
        OPENING_FILL, WALL_FILL, FRAME, STRUCTURE, FOUNDATION, UNKNOWN;

        boolean protectedFromCarve() {
            return this != OPENING_FILL && this != WALL_FILL;
        }
    }

    /**
     * Explicit carveRole → FOUNDATION semantic → name heuristic → matched entry WALL_FILL
     * (legacy unmarked fill) → UNKNOWN when no entry (default protect).
     */
    static CarveRole resolveCarveRole(ComponentDefinition.BlockEntry be, String blockState) {
        if (be != null && be.carveRole != null && !be.carveRole.isBlank()) {
            try {
                return CarveRole.valueOf(be.carveRole.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CarveRole.UNKNOWN;
            }
        }
        if (be != null && be.semantic == com.formacraft.common.semantic.SemanticPart.FOUNDATION) {
            return CarveRole.FOUNDATION;
        }
        String raw = blockState != null ? blockState.toLowerCase(Locale.ROOT)
                : (be != null && be.block != null ? be.block.toLowerCase(Locale.ROOT) : "");
        if (raw.isBlank()) return CarveRole.UNKNOWN;
        if (raw.contains("pillar") || raw.contains("beam")
                || (raw.contains("log") && !raw.contains("catalog"))) {
            return CarveRole.STRUCTURE;
        }
        // Ordinary fill blocks (with or without a matched entry) are carveable in openings.
        return CarveRole.WALL_FILL;
    }

    private static ComponentGenerationOutcome.Status mapGeoStatus(
            com.formacraft.common.component.variant.VariantCompileResult.Status s) {
        if (s == null) return ComponentGenerationOutcome.Status.GEOMETRY_INVALID;
        return switch (s) {
            case TEMPLATE_MISSING -> ComponentGenerationOutcome.Status.TEMPLATE_MISSING;
            case INVALID_INPUT -> ComponentGenerationOutcome.Status.INVALID_INPUT;
            case GEOMETRY_INVALID -> ComponentGenerationOutcome.Status.GEOMETRY_INVALID;
            case EMPTY_ALLOWED -> ComponentGenerationOutcome.Status.EMPTY_REJECTED;
            default -> ComponentGenerationOutcome.Status.GEOMETRY_INVALID;
        };
    }

    private static ComponentGenerationOutcome.Status mapResolveStatus(
            com.formacraft.common.component.query.ComponentResolveResult.Status s) {
        if (s == null) return ComponentGenerationOutcome.Status.NO_CANDIDATE;
        return switch (s) {
            case ADAPT_FAILED -> ComponentGenerationOutcome.Status.ADAPT_FAILED;
            case LOAD_FAILED -> ComponentGenerationOutcome.Status.LOAD_FAILED;
            case NO_CANDIDATE -> ComponentGenerationOutcome.Status.NO_CANDIDATE;
            case SUCCESS -> ComponentGenerationOutcome.Status.SUCCESS;
        };
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

    /** Query-only standalone requests can participate; explicit IDs and mounts keep their existing paths. */
    static ComponentQuery batchQuery(com.formacraft.common.llm.dto.Component component) {
        if (component == null || com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter
                .isExplicitlyDisabled(component)) return null;
        String json = extractFeatureJson(component.features());
        if (json == null) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = JsonUtil.get().fromJson(json, Map.class);
            if (request == null || isMountRequest(request) || getString(request, "id", "component_id", "componentId") != null) return null;
            ComponentQuery query = parseComponentQuery(request, null);
            if (query == null) query = com.formacraft.common.component.query.ComponentRequestConverter.fromLegacyMap(request, "");
            return query != null && (query.selection == null || query.selection.coordinateSuite) ? query : null;
        } catch (RuntimeException invalidRequest) {
            return null; // Normal expansion retains the original parse diagnostic.
        }
    }

    static com.formacraft.common.component.query.ComponentResolveResult resolveSingleRequest(
            com.formacraft.common.llm.dto.Component component, Path worldDir, Map<String, Object> request) {
        var prepared = ComponentLibraryBatchContext.lookup(component);
        return prepared != null ? prepared : resolveComponentResult(worldDir, request, null);
    }

    private static com.formacraft.common.component.query.ComponentResolveResult resolveComponentResult(
            Path worldDir, Map<String, Object> reqMap, String prefix) {
        if (worldDir == null || reqMap == null) {
            return com.formacraft.common.component.query.ComponentResolveResult.noCandidate("null_request");
        }
        String px = prefix == null ? "" : prefix;
        String explicitId = getString(reqMap, px + "id", px + "component_id", px + "componentId");
        ComponentQuery query = parseComponentQuery(reqMap, px);
        if (explicitId != null && !explicitId.isBlank()) {
            if (query == null) {
                query = com.formacraft.common.component.query.ComponentRequestConverter.fromLegacyMap(reqMap, px);
            }
            ComponentDefinition base = ComponentStorage.loadComponent(worldDir, explicitId);
            if (base == null) {
                return com.formacraft.common.component.query.ComponentResolveResult.loadFailed(
                        explicitId, "component_not_found");
            }
            ComponentDefinition fitted =
                    com.formacraft.common.component.model.ComponentModelApi.fitToQuery(base, query, null);
            if (fitted == null) {
                return com.formacraft.common.component.query.ComponentResolveResult.adaptFailed(
                        explicitId, "fit_to_query_failed");
            }
            return com.formacraft.common.component.query.ComponentResolveResult.success(fitted);
        }
        if (query == null) {
            query = com.formacraft.common.component.query.ComponentRequestConverter.fromLegacyMap(reqMap, px);
        }
        if (query != null) {
            java.util.Random rng = new java.util.Random(variantSeed(query, px));
            return com.formacraft.common.component.query.ComponentRetriever.retrieveBestResolved(query, rng);
        }
        ComponentRequest req = parseRequestWithPrefix(reqMap, px);
        ComponentDefinition legacy = ComponentLibrary.findBest(worldDir, req);
        if (legacy == null) {
            return com.formacraft.common.component.query.ComponentResolveResult.noCandidate("legacy_find_best_miss");
        }
        return com.formacraft.common.component.query.ComponentResolveResult.success(legacy);
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
        switch (role) {
            case "column", "pillar" -> {
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
            case "railing" -> {
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
            case "door" -> {
                com.formacraft.common.llm.dto.GlobalConstraints.Facing facing = semantic.slot() != null
                        ? semantic.slot().facing() : null;
                return generateDoorFallback(out, query, baseX, baseY, baseZ, w, d, h, facing);
            }
            case "window" -> {
                com.formacraft.common.llm.dto.GlobalConstraints.Facing facing = semantic.slot() != null
                        ? semantic.slot().facing() : null;
                return generateWindowFallback(out, query, baseX, baseY, baseZ, w, d, h, blockId, facing);
            }
            case "ornament", "bracket", "canopy" -> {
                int x = baseX + (w / 2);
                int y = baseY + Math.max(0, h - 1);
                int z = baseZ + (d / 2);
                out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, blockId));
                return out;
            }
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
                                                         int w, int d, int h,
                                                         com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        int openW = resolveOpeningWidth(query, 2, w);
        int openH = resolveOpeningHeight(query, 3, h);
        if (facing == null) facing = com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;
        for (int x = 0; x < openW; x++) {
            for (int y = 0; y < openH; y++) {
                int[] pos = openingPosition(baseX, baseY, baseZ, w, d, openW, x, y, facing);
                out.add(new BlockPatch(BlockPatch.REMOVE, pos[0], pos[1], pos[2], "minecraft:air"));
            }
        }
        return out;
    }

    private static List<BlockPatch> generateWindowFallback(List<BlockPatch> out,
                                                           ComponentQuery query,
                                                           int baseX, int baseY, int baseZ,
                                                           int w, int d, int h,
                                                           String blockId,
                                                           com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        int openW = resolveOpeningWidth(query, 2, w);
        int openH = resolveOpeningHeight(query, 2, h);
        if (facing == null) facing = com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;
        int y0 = resolveOpeningBaseY(query, baseY, h, openH);

        boolean requiresOpening = query.geometry != null && query.geometry.requiresOpening;
        for (int x = 0; x < openW; x++) {
            for (int y = 0; y < openH; y++) {
                int[] pos = openingPosition(baseX, y0, baseZ, w, d, openW, x, y, facing);
                if (requiresOpening) {
                    out.add(new BlockPatch(BlockPatch.REMOVE, pos[0], pos[1], pos[2], "minecraft:air"));
                }
                out.add(new BlockPatch(BlockPatch.PLACE, pos[0], pos[1], pos[2], blockId));
            }
        }
        return out;
    }

    /** Returns [x, y, z] for an opening cell on the facade matching {@code facing}. */
    private static int[] openingPosition(int baseX, int baseY, int baseZ,
                                         int w, int d, int openW,
                                         int localX, int localY,
                                         com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        return switch (facing) {
            case NORTH -> new int[]{
                    baseX + Math.max(0, (w - openW) / 2) + localX,
                    baseY + localY,
                    baseZ + Math.max(0, d - 1)
            };
            case EAST -> new int[]{
                    baseX,
                    baseY + localY,
                    baseZ + Math.max(0, (d - openW) / 2) + localX
            };
            case WEST -> new int[]{
                    baseX + Math.max(0, w - 1),
                    baseY + localY,
                    baseZ + Math.max(0, (d - openW) / 2) + localX
            };
            default -> new int[]{
                    baseX + Math.max(0, (w - openW) / 2) + localX,
                    baseY + localY,
                    baseZ
            };
        };
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
        return Math.min(v, max);
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
            // 若提供 socket_id，则优先使用 def 中记录的 socketPlacements
            String socketId = getString(reqMap, "socket_id", "socketId");
            if (socketId != null && def != null) {
                ComponentSocket socket = findSocket(def, socketId);
                return SocketPlacementResolver.resolveLocalOrigin(def, socketId, socket);
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

