package com.formacraft.common.component.group;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentFeatureFlags;
import com.formacraft.common.component.ComponentGenerationOutcome;
import com.formacraft.common.component.ComponentGeometryResolver;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.ResolvedComponentGeometry;
import com.formacraft.common.component.placement.AttachmentRecognizer;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.FacingDeriver;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.FacingUtil;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.socket.SocketPlacementResolver;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.common.skeleton.transform.YRotation;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PlayerComponentGroupExpander：
 * - 从 LLM component.features 中读取 group_request:{...}
 * - 解析 group_id + 可选 mount_to/host_id/socket_id
 * - 先 carve（如果 mount 到某个 host socket），再展开 group 内部子构件为 BlockPatch
 */
public final class PlayerComponentGroupExpander {
    private PlayerComponentGroupExpander() {}

    private static final FcaLog LOG = FcaLog.of("PlayerComponentGroupExpander");

    private static final String PREFIX = "group_request:";

    public static List<BlockPatch> tryExpand(SemanticComponent semantic, ServerWorld world) {
        ComponentGenerationOutcome outcome = tryExpandResult(semantic, world);
        if (outcome == null) return null;
        return outcome.patches();
    }

    /** Structured expand; prefer this when diagnostics / hard-fail matter. */
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
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: invalid group_request json: {}", featureJson);
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.INVALID_INPUT, "invalid_json", "group_request_parse_failed");
        }
        if (reqMap == null) {
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.INVALID_INPUT, "null_request", "reqMap_null");
        }

        String groupId = getString(reqMap, "group_id", "groupId", "group");
        if (groupId == null || groupId.isBlank()) {
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.INVALID_INPUT, "missing_group_id", "group_id_required");
        }
        ComponentGroup group = ComponentGroupRegistry.get(groupId);
        if (group == null) {
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: group not found: {}", groupId);
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.LOAD_FAILED, "group_not_found", groupId);
        }

        Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);

        BlockPos baseOffset = resolveBaseOffset(semantic);
        int baseX = baseOffset.getX();
        int baseY = baseOffset.getY();
        int baseZ = baseOffset.getZ();

        Direction groupFacing = parseDir(getString(reqMap, "facing", "target_facing", "group_facing", "groupFacing"));
        if (groupFacing == null || !groupFacing.getAxis().isHorizontal()) {
            groupFacing = facingFromSlot(semantic);
        }
        if (groupFacing == null || !groupFacing.getAxis().isHorizontal()) groupFacing = Direction.SOUTH;

        Mirror groupMirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode", "group_mirror", "groupMirror"));

        List<BlockPatch> pendingCarve = new ArrayList<>();
        MountTarget mt = parseMountTarget(reqMap);
        if (mt != null && mt.hostId != null && mt.socketId != null) {
            ComponentDefinition host = ComponentStorage.loadComponent(worldDir, mt.hostId);
            if (host == null) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount host not found: {}", mt.hostId);
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.LOAD_FAILED, "host_not_found", mt.hostId);
            }

            Direction hostFromFacing = parseDir(host.anchor != null ? host.anchor.facing : null);
            if (hostFromFacing == null || !hostFromFacing.getAxis().isHorizontal()) hostFromFacing = Direction.SOUTH;
            Direction hostTargetFacing = parseDir(getString(reqMap, "host_facing", "hostFacing", "facing", "target_facing"));
            if (hostTargetFacing == null || !hostTargetFacing.getAxis().isHorizontal()) {
                hostTargetFacing = facingFromSlot(semantic);
            }
            if (hostTargetFacing == null || !hostTargetFacing.getAxis().isHorizontal()) hostTargetFacing = Direction.SOUTH;
            Mirror hostMirror = parseMirror(getString(reqMap, "host_mirror", "hostMirror", "mirror", "mirror_mode", "mirrorMode"));
            ComponentTransform hostTransform = new ComponentTransform(hostTargetFacing, hostMirror);

            ComponentSocket socket = findSocket(host, mt.socketId);
            if (socket == null) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount socket not found: {}.{}", mt.hostId, mt.socketId);
                return ComponentGenerationOutcome.failure(
                        ComponentGenerationOutcome.Status.LOAD_FAILED, "socket_not_found",
                        mt.hostId + "." + mt.socketId);
            }

            BlockPos socketLocal = SocketPlacementResolver.resolveLocalOrigin(host, mt.socketId, socket);
            BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, hostFromFacing, hostTransform);

            Direction socketLocalFacing = SocketPlacementResolver.resolveLocalFacing(host, mt.socketId, socket);
            Direction socketWorldFacing = FacingTransformUtil.transformFacing(socketLocalFacing, hostFromFacing, hostTransform);
            if (socketWorldFacing == null || !socketWorldFacing.getAxis().isHorizontal()) socketWorldFacing = hostTargetFacing;

            baseX = baseX + socketOff.getX();
            baseY = baseY + socketOff.getY();
            baseZ = baseZ + socketOff.getZ();
            groupFacing = socketWorldFacing;

            // Defer carve until children resolve (atomic: no REMOVE-only success).
            Boolean carve0 = getBoolNullable(reqMap, "carve", "carve_mask", "carveMask", "carve_socket", "carveSocket");
            boolean carve = carve0 == null || carve0;
            if (carve) {
                SocketMask mask;
                if (socket.size != null && socket.size.min != null && socket.size.min.length >= 2) {
                    mask = new SocketMask(socket.size.min[0], socket.size.min[1], 1);
                } else {
                    mask = new SocketMask(2, 3, 1);
                }
                if (mask.width() > 0 && mask.height() > 0 && mask.depth() > 0) {
                    BlockPos socketWorld = new BlockPos(baseX, baseY, baseZ);
                    for (int x = 0; x < mask.width(); x++) {
                        for (int y = 0; y < mask.height(); y++) {
                            for (int z = 0; z < mask.depth(); z++) {
                                BlockPos p = FacingUtil.offset(socketWorld, socketWorldFacing, x, y, z);
                                pendingCarve.add(new BlockPatch(
                                        BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
                            }
                        }
                    }
                }
            }
        }

        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) semanticStyleId = resolveSemanticStyleId(semantic.styleProfile());
        boolean semanticSkin = ComponentFeatureFlags.resolveSemanticSkin(
                reqMap, semanticStyleId, "semantic_skin", "semanticSkin");

        List<BlockPatch> placePatches = new ArrayList<>(256);
        ExpandStats stats = expandGroupInto(placePatches, worldDir, group, baseX, baseY, baseZ, groupFacing, groupMirror,
                semanticSkin, semanticStyleId, world.getSeed(),
                reqMap, 0);

        if (stats.missingRequiredIds != null && !stats.missingRequiredIds.isEmpty()) {
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.LOAD_FAILED,
                    "required_group_children_missing",
                    String.join(",", stats.missingRequiredIds));
        }
        if (stats.emitHardFail) {
            return ComponentGenerationOutcome.failure(
                    stats.emitFailStatus != null
                            ? stats.emitFailStatus
                            : ComponentGenerationOutcome.Status.GEOMETRY_INVALID,
                    stats.emitFailCode != null ? stats.emitFailCode : "group_emit_failed",
                    stats.failMessage != null ? stats.failMessage : group.getId());
        }
        if (!stats.childrenResolved) {
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.LOAD_FAILED, "group_children_unresolved",
                    stats.failMessage != null ? stats.failMessage : group.getId());
        }
        if (stats.placeCount <= 0) {
            return ComponentGenerationOutcome.failure(
                    ComponentGenerationOutcome.Status.GEOMETRY_INVALID, "group_empty_place",
                    group.getId());
        }

        List<BlockPatch> out = new ArrayList<>(pendingCarve.size() + placePatches.size());
        out.addAll(pendingCarve);
        out.addAll(placePatches);

        boolean partial = stats.optionalSkipped > 0
                || (stats.missingOptionalIds != null && !stats.missingOptionalIds.isEmpty());
        if (partial) {
            String msg = "optionalSkipped=" + stats.optionalSkipped
                    + (stats.missingOptionalIds == null || stats.missingOptionalIds.isEmpty()
                    ? "" : "; missingOptional=" + String.join(",", stats.missingOptionalIds));
            return ComponentGenerationOutcome.partialSuccess(out, "group_partial", msg);
        }
        return ComponentGenerationOutcome.success(out);
    }

    private static final class ExpandStats {
        boolean childrenResolved;
        int placeCount;
        int optionalSkipped;
        String failMessage;
        java.util.List<String> missingRequiredIds = new java.util.ArrayList<>();
        java.util.List<String> missingOptionalIds = new java.util.ArrayList<>();
        boolean emitHardFail;
        ComponentGenerationOutcome.Status emitFailStatus;
        String emitFailCode;
    }

    // ===== helpers (mostly mirrored from PlayerComponentExpander, kept local to avoid widening API surface) =====

    private static final int MAX_NESTED_GROUP_DEPTH = 4;

    private static ExpandStats expandGroupInto(List<BlockPatch> out,
                                        Path worldDir,
                                        ComponentGroup group,
                                        int baseX, int baseY, int baseZ,
                                        Direction groupFacing,
                                        Mirror groupMirror,
                                        boolean semanticSkin,
                                        String semanticStyleId,
                                        long seedBase,
                                        Map<String, Object> reqMap,
                                        int depth) {
        ExpandStats stats = new ExpandStats();
        if (out == null || worldDir == null || group == null) {
            stats.failMessage = "null_expand_inputs";
            return stats;
        }
        if (depth > MAX_NESTED_GROUP_DEPTH) {
            FormacraftMod.LOGGER.error("PlayerComponentGroupExpander: nested group depth exceeded at {}", group.getId());
            stats.emitHardFail = true;
            stats.emitFailStatus = ComponentGenerationOutcome.Status.LOAD_FAILED;
            stats.emitFailCode = "nested_depth_exceeded";
            stats.failMessage = "nested_depth_exceeded:" + group.getId();
            return stats;
        }

        // 预加载：必需子缺失立即失败；可选缺失记入 PARTIAL
        List<GroupComponentEntry> entries = group.getComponents();
        if (entries == null || entries.isEmpty()) {
            stats.failMessage = "group_empty_entries";
            return stats;
        }
        List<ResolvedEntry> resolved = new ArrayList<>(entries.size());
        int declared = 0;
        for (GroupComponentEntry e : entries) {
            if (e == null || e.componentId() == null || e.componentId().isBlank()) continue;
            declared++;
            String id = e.componentId().trim();
            ComponentDefinition def = ComponentStorage.loadComponent(worldDir, id);
            if (def == null || def.blocks == null || def.blocks.isEmpty()) {
                if (e.isRequired()) {
                    stats.missingRequiredIds.add(id);
                } else {
                    stats.missingOptionalIds.add(id);
                    stats.optionalSkipped++;
                }
                continue;
            }
            resolved.add(new ResolvedEntry(e, def));
        }
        if (!stats.missingRequiredIds.isEmpty()) {
            FormacraftMod.LOGGER.error(
                    "PlayerComponentGroupExpander: required children missing group={} ids={}",
                    group.getId(), stats.missingRequiredIds);
            stats.failMessage = "required_missing=" + String.join(",", stats.missingRequiredIds);
            return stats;
        }
        if (resolved.isEmpty()) {
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: group {} has no resolvable child components (missing ids?)", group.getId());
            stats.failMessage = "no_resolvable_children declared=" + declared;
            return stats;
        }
        stats.childrenResolved = true;

        // expand group entries via shared ComponentGeometryResolver
        ComponentTransform groupTransform = new ComponentTransform(groupFacing, groupMirror);
        Direction groupFromFacing = Direction.SOUTH;

        int beforePlaces = countPlaces(out);
        for (ResolvedEntry re : resolved) {
            if (re == null) continue;
            GroupComponentEntry e = re.entry;
            ComponentDefinition def = re.def;

            BlockPos childLocal = new BlockPos(e.offsetX(), e.offsetY(), e.offsetZ());
            BlockPos childOff = ComponentTransformUtil.transformOffset(childLocal, groupFromFacing, groupTransform);
            int childBaseX = baseX + childOff.getX();
            int childBaseY = baseY + childOff.getY();
            int childBaseZ = baseZ + childOff.getZ();

            Direction childFacing = applyYRotation(groupFacing, e.rotation());
            Mirror childMirror = combineMirror(groupMirror, e.mirror());
            ComponentTransform childTransform = new ComponentTransform(childFacing, childMirror);

            Direction childFromFacing = parseDir(def.anchor != null ? def.anchor.facing : null);
            if (childFromFacing == null || !childFromFacing.getAxis().isHorizontal()) childFromFacing = Direction.SOUTH;

            if (!emitComponentPatches(out, def, childFromFacing, childTransform,
                    childBaseX, childBaseY, childBaseZ,
                    semanticSkin, semanticStyleId, seedBase, e.isRequired(), stats)) {
                return stats;
            }
        }
        stats.placeCount += countPlaces(out) - beforePlaces;

        // mounts on group sockets — default required=true; only explicit false is optional
        for (MountEntry me : parseMountEntries(reqMap)) {
            if (me == null) continue;
            if (me.socketId == null || me.socketId.isBlank()) continue;
            boolean mountRequired = isMountRequired(me);

            ComponentSocket socket = findGroupSocket(group, me.socketId);
            if (socket == null) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: group socket not found: {}.{}", group.getId(), me.socketId);
                if (mountRequired) {
                    stats.emitHardFail = true;
                    stats.emitFailStatus = ComponentGenerationOutcome.Status.LOAD_FAILED;
                    stats.emitFailCode = "mount_socket_missing";
                    stats.failMessage = group.getId() + "." + me.socketId;
                    return stats;
                }
                stats.optionalSkipped++;
                continue;
            }

            // socket world offset (in group placement space)
            ComponentDefinition groupBounds = buildGroupBoundsDef(group, worldDir, groupFromFacing, groupTransform);
            BlockPos socketLocal = SocketPlacementResolver.resolveLocalOrigin(groupBounds, me.socketId, socket);
            BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, groupFromFacing, groupTransform);
            int socketX = baseX + socketOff.getX();
            int socketY = baseY + socketOff.getY();
            int socketZ = baseZ + socketOff.getZ();

            Direction socketLocalFacing = SocketPlacementResolver.resolveLocalFacing(groupBounds, me.socketId, socket);
            Direction socketWorldFacing = FacingTransformUtil.transformFacing(socketLocalFacing, groupFromFacing, groupTransform);
            if (socketWorldFacing == null || !socketWorldFacing.getAxis().isHorizontal()) socketWorldFacing = groupFacing;

            // mount anchor offset (in socket-local coords: dx=right, dz=forward)
            BlockPos anchorPos = new BlockPos(socketX, socketY, socketZ);
            if (me.offX != 0 || me.offY != 0 || me.offZ != 0) {
                anchorPos = FacingUtil.offset(anchorPos, socketWorldFacing, me.offX, me.offY, me.offZ);
            }

            boolean carve = me.carve == null || me.carve;
            if (carve) {
                // v1 新版 Socket：从 size 约束中提取尺寸
                SocketMask mask;
                if (socket.size != null && socket.size.min != null && socket.size.min.length >= 2) {
                    int w = socket.size.min[0];
                    int h = socket.size.min[1];
                    mask = new SocketMask(w, h, 1);
                } else {
                    mask = new SocketMask(2, 3, 1); // 默认门尺寸
                }
                if (mask.width() > 0 && mask.height() > 0 && mask.depth() > 0) {
                    for (int x = 0; x < mask.width(); x++) {
                        for (int y = 0; y < mask.height(); y++) {
                            for (int z = 0; z < mask.depth(); z++) {
                                BlockPos p = FacingUtil.offset(anchorPos, socketWorldFacing, x, y, z);
                                out.add(new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
                            }
                        }
                    }
                }
            }

            // Mount either a component OR a nested group
            if (me.mountGroupId != null && !me.mountGroupId.isBlank()) {
                ComponentGroup childGroup = ComponentGroupRegistry.get(me.mountGroupId);
                if (childGroup == null) {
                    FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount group not found: {}", me.mountGroupId);
                    if (mountRequired) {
                        stats.emitHardFail = true;
                        stats.emitFailStatus = ComponentGenerationOutcome.Status.LOAD_FAILED;
                        stats.emitFailCode = "mount_group_missing";
                        stats.failMessage = me.mountGroupId;
                        return stats;
                    }
                    stats.optionalSkipped++;
                    continue;
                }
                Direction childFacing0 = parseDir(me.mountFacing);
                if (childFacing0 == null || !childFacing0.getAxis().isHorizontal()) childFacing0 = socketWorldFacing;
                Mirror childMirror = parseMirror(me.mountMirror);

                // chain / repeat: mount the group multiple times using its own socket as the next anchor
                int repeat = Math.max(1, Math.min(64, me.repeat));
                String repeatSocketId = (me.repeatSocketId != null && !me.repeatSocketId.isBlank()) ? me.repeatSocketId : "next";

                BlockPos curAnchor = anchorPos;
                Direction curFacing = childFacing0;
                for (int i = 0; i < repeat; i++) {
                    int nestedBefore = countPlaces(out);
                    ExpandStats nested = expandGroupInto(out, worldDir, childGroup,
                            curAnchor.getX(), curAnchor.getY(), curAnchor.getZ(),
                            curFacing, childMirror,
                            semanticSkin, semanticStyleId, seedBase,
                            me.nestedReq != null ? me.nestedReq : Map.of(),
                            depth + 1);
                    stats.placeCount += countPlaces(out) - nestedBefore;
                    if (bubbleNestedFailure(stats, nested, mountRequired)) {
                        return stats;
                    }
                    stats.optionalSkipped += nested.optionalSkipped;

                    if (i == repeat - 1) break;
                    SocketWorld next = resolveGroupSocketWorld(childGroup, curAnchor, curFacing, childMirror, repeatSocketId, worldDir);
                    if (next == null) {
                        FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: repeat_socket not found: {}.{}", childGroup.getId(), repeatSocketId);
                        if (mountRequired) {
                            stats.emitHardFail = true;
                            stats.emitFailStatus = ComponentGenerationOutcome.Status.LOAD_FAILED;
                            stats.emitFailCode = "repeat_socket_missing";
                            stats.failMessage = childGroup.getId() + "." + repeatSocketId;
                            return stats;
                        }
                        stats.optionalSkipped++;
                        break;
                    }
                    curAnchor = next.pos;
                    curFacing = next.facing;
                }
                continue;
            }

            if (me.mountId == null || me.mountId.isBlank()) continue;
            ComponentDefinition mount = ComponentStorage.loadComponent(worldDir, me.mountId.trim());
            if (mount == null || mount.blocks == null || mount.blocks.isEmpty()) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount component not found: {}", me.mountId);
                if (mountRequired) {
                    stats.emitHardFail = true;
                    stats.emitFailStatus = ComponentGenerationOutcome.Status.LOAD_FAILED;
                    stats.emitFailCode = "mount_component_missing";
                    stats.failMessage = me.mountId;
                    return stats;
                }
                stats.optionalSkipped++;
                continue;
            }

            // placementSpec 过滤：用 group socket 的语义附着类型约束挂载物
            AttachmentType hostAttachment = AttachmentRecognizer.attachmentForSocketContext(socket.context);
            if (!AttachmentRecognizer.isCompatible(mount.placementSpec, hostAttachment)) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount placementSpec incompatible: mount={} need={} groupSocketContext={} hostAttachment={}",
                        mount.id, mount.placementSpec.attachment,
                        socket.context, hostAttachment);
                if (mountRequired) {
                    stats.emitHardFail = true;
                    stats.emitFailStatus = ComponentGenerationOutcome.Status.INCOMPATIBLE_MOUNT;
                    stats.emitFailCode = "mount_incompatible";
                    stats.failMessage = me.mountId;
                    return stats;
                }
                stats.optionalSkipped++;
                continue;
            }

            Direction mountFromFacing = parseDir(mount.anchor != null ? mount.anchor.facing : null);
            if (mountFromFacing == null || !mountFromFacing.getAxis().isHorizontal()) mountFromFacing = Direction.SOUTH;

            Direction mountFacing = parseDir(me.mountFacing);
            if (mountFacing == null || !mountFacing.getAxis().isHorizontal()) mountFacing = socketWorldFacing;
            // PlacementSpec v1：根据 FacingPolicy 推导（仅当未显式指定 mount_facing）
            try {
                if (me.mountFacing == null || me.mountFacing.isBlank()) {
                    mountFacing = FacingDeriver.derive(
                            mount.placementSpec,
                            socketWorldFacing,
                            mountFacing,
                            me.nestedReq // mounts 内的 hints（可塞 edge endpoints 等）；没有则 null
                    );
                }
            } catch (Throwable t) {
                LOG.debug("FacingDeriver failed mountId={}", me.mountId, t);
            }
            // PlacementSpec v1：若不需要方向且未显式给 mount_facing，则保持构件自身朝向
            try {
                if ((me.mountFacing == null || me.mountFacing.isBlank())
                        && mount.placementSpec != null
                        && mount.placementSpec.facingPolicy == FacingPolicy.NONE) {
                    mountFacing = mountFromFacing;
                }
            } catch (Throwable t) {
                LOG.debug("facing policy NONE fallback failed mountId={}", me.mountId, t);
            }
            Mirror mountMirror = parseMirror(me.mountMirror);
            ComponentTransform mountTransform = new ComponentTransform(mountFacing, mountMirror);

            int mountBefore = countPlaces(out);
            if (!emitComponentPatches(out, mount, mountFromFacing, mountTransform,
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(),
                    semanticSkin, semanticStyleId, seedBase, mountRequired, stats)) {
                return stats;
            }
            stats.placeCount += countPlaces(out) - mountBefore;
        }
        return stats;
    }

    /** Default true; only mount_required/required=false makes a mount optional. */
    static boolean isMountRequired(MountEntry me) {
        return me == null || isMountRequiredParams(me.nestedReq());
    }

    /** Null/empty params → required. Exposed for unit tests. */
    public static boolean isMountRequiredParams(Map<String, Object> nr) {
        if (nr == null || nr.isEmpty()) return true;
        if (Boolean.FALSE.equals(nr.get("mount_required")) || Boolean.FALSE.equals(nr.get("required"))) {
            return false;
        }
        Object mr = nr.get("mount_required");
        if (mr != null && "false".equalsIgnoreCase(String.valueOf(mr).trim())) return false;
        Object r = nr.get("required");
        if (r != null && "false".equalsIgnoreCase(String.valueOf(r).trim())) return false;
        return true;
    }

    /** Bubble nested hard failures / missing required; optional nest → optionalSkipped. */
    private static boolean bubbleNestedFailure(ExpandStats parent, ExpandStats nested, boolean mountRequired) {
        if (nested == null) return false;
        if (nested.emitHardFail
                || (nested.missingRequiredIds != null && !nested.missingRequiredIds.isEmpty())) {
            if (mountRequired) {
                parent.emitHardFail = true;
                parent.emitFailStatus = nested.emitFailStatus != null
                        ? nested.emitFailStatus
                        : ComponentGenerationOutcome.Status.LOAD_FAILED;
                parent.emitFailCode = nested.emitFailCode != null
                        ? nested.emitFailCode
                        : "required_group_children_missing";
                parent.failMessage = nested.failMessage != null
                        ? nested.failMessage
                        : (nested.missingRequiredIds == null
                        ? "nested_fail"
                        : String.join(",", nested.missingRequiredIds));
                if (nested.missingRequiredIds != null) {
                    parent.missingRequiredIds.addAll(nested.missingRequiredIds);
                }
                return true;
            }
            parent.optionalSkipped++;
            return false;
        }
        if (!nested.childrenResolved) {
            if (mountRequired) {
                parent.emitHardFail = true;
                parent.emitFailStatus = ComponentGenerationOutcome.Status.LOAD_FAILED;
                parent.emitFailCode = "nested_children_unresolved";
                parent.failMessage = nested.failMessage;
                return true;
            }
            parent.optionalSkipped++;
        }
        return false;
    }

    /**
     * Shared PLACE emission via {@link ComponentGeometryResolver}.
     * @return false if hard-fail was recorded on stats
     */
    private static boolean emitComponentPatches(
            List<BlockPatch> out,
            ComponentDefinition def,
            Direction fromFacing,
            ComponentTransform transform,
            int baseX, int baseY, int baseZ,
            boolean semanticSkin,
            String semanticStyleId,
            long seedBase,
            boolean required,
            ExpandStats stats
    ) {
        ResolvedComponentGeometry geo = ComponentGeometryResolver.emit(
                def, fromFacing, transform, baseX, baseY, baseZ,
                semanticSkin, semanticStyleId, seedBase);
        if (!geo.ok()) {
            if (required) {
                stats.emitHardFail = true;
                stats.emitFailStatus = ComponentGenerationOutcome.Status.GEOMETRY_INVALID;
                stats.emitFailCode = "group_child_emit_failed";
                stats.failMessage = (def != null ? def.id : "?") + ":" + geo.message();
                FormacraftMod.LOGGER.error(
                        "PlayerComponentGroupExpander: required child emit failed id={} status={} msg={}",
                        def != null ? def.id : "?", geo.status(), geo.message());
                return false;
            }
            stats.optionalSkipped++;
            FormacraftMod.LOGGER.warn(
                    "PlayerComponentGroupExpander: optional child emit skipped id={} msg={}",
                    def != null ? def.id : "?", geo.message());
            return true;
        }
        out.addAll(geo.patches());
        return true;
    }

    private static int countPlaces(List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) return 0;
        int n = 0;
        for (BlockPatch p : patches) {
            if (p != null && BlockPatch.PLACE.equals(p.action())) n++;
        }
        return n;
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

    private static ComponentSocket findGroupSocket(ComponentGroup group, String socketId) {
        if (group == null || group.getSockets() == null || group.getSockets().isEmpty()) return null;
        if (socketId == null || socketId.isBlank()) return null;
        for (ComponentSocket s : group.getSockets()) {
            if (s == null || s.id == null) continue;
            if (socketId.equals(s.id)) return s;
        }
        return null;
    }

    private static SocketWorld resolveGroupSocketWorld(ComponentGroup group,
                                                      BlockPos groupAnchor,
                                                      Direction groupFacing,
                                                      Mirror groupMirror,
                                                      String socketId,
                                                      Path worldDir) {
        if (group == null || groupAnchor == null) return null;
        ComponentSocket s = findGroupSocket(group, socketId);
        if (s == null) return null;

        Direction groupFromFacing = Direction.SOUTH;
        ComponentTransform groupTransform = new ComponentTransform(groupFacing, groupMirror);

        ComponentDefinition groupBounds = buildGroupBoundsDef(group, worldDir, groupFromFacing, groupTransform);
        BlockPos socketLocal = SocketPlacementResolver.resolveLocalOrigin(groupBounds, socketId, s);
        BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, groupFromFacing, groupTransform);
        BlockPos pos = new BlockPos(
                groupAnchor.getX() + socketOff.getX(),
                groupAnchor.getY() + socketOff.getY(),
                groupAnchor.getZ() + socketOff.getZ()
        );

        Direction socketLocalFacing = SocketPlacementResolver.resolveLocalFacing(groupBounds, socketId, s);
        Direction facing = FacingTransformUtil.transformFacing(socketLocalFacing, groupFromFacing, groupTransform);
        if (facing == null || !facing.getAxis().isHorizontal()) facing = groupFacing;

        return new SocketWorld(pos, facing);
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
        } catch (Throwable t) {
            LOG.debug("parse direction failed value={}", s, t);
            return null;
        }
    }

    private static Mirror parseMirror(String s) {
        if (s == null || s.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            LOG.debug("parse mirror failed value={}", s, t);
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

    @SuppressWarnings("unused")
    private static long mixSeed(long base, int x, int y, int z, int t) {
        long h = 1469598103934665603L ^ base;
        h ^= x; h *= 1099511628211L;
        h ^= y; h *= 1099511628211L;
        h ^= z; h *= 1099511628211L;
        h ^= t; h *= 1099511628211L;
        return h;
    }

    private static Direction applyYRotation(Direction facing, YRotation rot) {
        Direction f = (facing != null) ? facing : Direction.SOUTH;
        if (!f.getAxis().isHorizontal()) f = Direction.SOUTH;
        YRotation r = (rot != null) ? rot : YRotation.NONE;
        return switch (r) {
            case NONE -> f;
            case CW_90 -> f.rotateYClockwise();
            case CW_180 -> f.getOpposite();
            case CW_270 -> f.rotateYCounterclockwise();
        };
    }

    private static Mirror combineMirror(Mirror a, Mirror b) {
        Mirror x = (a != null) ? a : Mirror.NONE;
        Mirror y = (b != null) ? b : Mirror.NONE;
        if (x == Mirror.NONE) return y;
        if (y == Mirror.NONE) return x;
        // same axis cancels out (best-effort)
        if (x == y) return Mirror.NONE;
        // cannot represent X+Z simultaneously in current Mirror enum, keep outer mirror for determinism
        return x;
    }

    private static MountTarget parseMountTarget(Map<String, Object> reqMap) {
        if (reqMap == null) return null;
        String mountTo = getString(reqMap, "mount_to", "mountTo");
        String hostId = getString(reqMap, "host_id", "hostId");
        String socketId = getString(reqMap, "socket_id", "socketId");
        if ((hostId == null || socketId == null) && mountTo != null && mountTo.contains(".")) {
            int idx = mountTo.indexOf('.');
            String a = mountTo.substring(0, idx).trim();
            String b = mountTo.substring(idx + 1).trim();
            if (!a.isEmpty() && !b.isEmpty()) {
                hostId = a;
                socketId = b;
            }
        }
        if (hostId == null || hostId.isBlank() || socketId == null || socketId.isBlank()) return null;
        return new MountTarget(hostId.trim(), socketId.trim());
    }

    private record MountTarget(String hostId, String socketId) {}

    private record ResolvedEntry(GroupComponentEntry entry, ComponentDefinition def) {}

    private static List<MountEntry> parseMountEntries(Map<String, Object> reqMap) {
        if (reqMap == null) return List.of();
        Object v = reqMap.get("mounts");
        if (v == null) v = reqMap.get("mount");
        if (v == null) v = reqMap.get("attachments");
        if (v == null) return List.of();

        List<MountEntry> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object it : list) {
                if (it instanceof Map<?, ?> mm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) mm;
                    MountEntry me = parseMountEntry(m);
                    if (me != null) out.add(me);
                }
            }
            return out;
        }
        if (v instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) mm;
            MountEntry me = parseMountEntry(m);
            return me != null ? List.of(me) : List.of();
        }
        return List.of();
    }

    private static MountEntry parseMountEntry(Map<String, Object> m) {
        if (m == null) return null;
        String socketId = getString(m, "socket_id", "socketId", "socket");
        String mountId = getString(m, "mount_id", "mountId", "component_id", "componentId", "component");
        String mountGroupId = getString(m, "mount_group_id", "mountGroupId", "group_id", "groupId", "group");
        Boolean carve = getBoolNullable(m, "carve", "carve_mask", "carveMask", "carve_socket", "carveSocket");
        String facing = getString(m, "mount_facing", "mountFacing", "facing");
        String mirror = getString(m, "mount_mirror", "mountMirror", "mirror");
        int offX, offY, offZ;
        Object off0 = m.get("mount_offset");
        if (!(off0 instanceof Map<?, ?>)) off0 = m.get("offset");
        if (off0 instanceof Map<?, ?> mm) {
            offX = getInt(mm, 0, "x", "dx");
            offY = getInt(mm, 0, "y", "dy");
            offZ = getInt(mm, 0, "z", "dz");
        } else {
            offX = getInt(m, 0, "mount_offset_x", "offsetX", "offX");
            offY = getInt(m, 0, "mount_offset_y", "offsetY", "offY");
            offZ = getInt(m, 0, "mount_offset_z", "offsetZ", "offZ");
        }
        Object nested = m.get("mounts");
        if (nested == null) nested = m.get("mount");
        Map<String, Object> nestedReq = null;
        if (nested instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> n = (Map<String, Object>) mm;
            nestedReq = n;
        } else if (nested instanceof List<?> list) {
            // Keep original list form by packing into a map so parseMountEntries works.
            nestedReq = new java.util.HashMap<>();
            nestedReq.put("mounts", list);
        }

        if (socketId == null) return null;
        if ((mountId == null || mountId.isBlank()) && (mountGroupId == null || mountGroupId.isBlank())) return null;

        int repeat = getInt(m, 1, "repeat", "count", "n");
        String repeatSocketId = getString(m, "repeat_socket", "repeatSocket", "chain_socket", "chainSocket", "next_socket", "nextSocket");

        return new MountEntry(socketId.trim(),
                mountId != null ? mountId.trim() : null,
                mountGroupId != null ? mountGroupId.trim() : null,
                carve, facing, mirror, nestedReq,
                offX, offY, offZ,
                Math.max(1, repeat),
                repeatSocketId);
    }

    private record MountEntry(String socketId,
                              String mountId,
                              String mountGroupId,
                              Boolean carve,
                              String mountFacing,
                              String mountMirror,
                              Map<String, Object> nestedReq,
                              int offX,
                              int offY,
                              int offZ,
                              int repeat,
                              String repeatSocketId) {}

    private static int getInt(Map<?, ?> m, int def, String... keys) {
        if (m == null || keys == null) return def;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Throwable t) {
                LOG.debug("parse int hint failed key={} value={}", k, v);
            }
        }
        return def;
    }

    private record SocketWorld(BlockPos pos, Direction facing) {}

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
        Map<String, Object> params = component.params();
        String anchorMode = getString(params, "anchor_mode", "anchorMode");
        if (anchorMode != null && anchorMode.toLowerCase(Locale.ROOT).contains("corner")) {
            return false;
        }
        String type = component.componentType();
        if (type == null || type.isBlank()) return true;
        String upper = type.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("MASS_") || upper.equals("TOWER") || upper.equals("WALL")
                || upper.equals("WALL_SEGMENT") || upper.equals("FENCE_OR_WALL");
    }

    private static ComponentDefinition buildGroupBoundsDef(ComponentGroup group,
                                                           Path worldDir,
                                                           Direction groupFromFacing,
                                                           ComponentTransform groupTransform) {
        ComponentDefinition def = new ComponentDefinition();
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = groupFromFacing != null ? groupFromFacing.name() : "SOUTH";
        List<ComponentDefinition.BlockEntry> blocks = new ArrayList<>();
        if (group == null || worldDir == null) {
            def.blocks = blocks;
            return def;
        }
        List<GroupComponentEntry> entries = group.getComponents();
        if (entries == null || entries.isEmpty()) {
            def.blocks = blocks;
            return def;
        }
        for (GroupComponentEntry e : entries) {
            if (e == null || e.componentId() == null || e.componentId().isBlank()) continue;
            ComponentDefinition child = ComponentStorage.loadComponent(worldDir, e.componentId().trim());
            if (child == null || child.blocks == null || child.blocks.isEmpty()) continue;
            BlockPos childLocal = new BlockPos(e.offsetX(), e.offsetY(), e.offsetZ());
            BlockPos childOff = ComponentTransformUtil.transformOffset(childLocal, groupFromFacing, groupTransform);
            for (ComponentDefinition.BlockEntry be : child.blocks) {
                if (be == null) continue;
                ComponentDefinition.BlockEntry out = new ComponentDefinition.BlockEntry();
                out.dx = childOff.getX() + be.dx;
                out.dy = childOff.getY() + be.dy;
                out.dz = childOff.getZ() + be.dz;
                out.block = be.block;
                blocks.add(out);
            }
        }
        def.blocks = blocks;
        return def;
    }
}

