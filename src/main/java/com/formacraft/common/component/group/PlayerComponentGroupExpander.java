package com.formacraft.common.component.group;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.placement.AttachmentRecognizer;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.FacingDeriver;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.FacingUtil;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.component.semantic.BlockStatePropertyUtil;
import com.formacraft.common.component.semantic.SemanticBlockStatePicker;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.common.skeleton.transform.YRotation;
import net.minecraft.block.BlockState;
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

    private static final String PREFIX = "group_request:";

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
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: invalid group_request json: {}", featureJson);
            return List.of();
        }
        if (reqMap == null) return List.of();

        // resolve group
        String groupId = getString(reqMap, "group_id", "groupId", "group");
        ComponentGroup group = ComponentGroupRegistry.get(groupId);
        if (group == null) {
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: group not found: {}", groupId);
            return List.of();
        }

        Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);

        List<BlockPatch> out = new ArrayList<>(256);

        // base anchor (relative to slot anchor)
        int baseX = 0, baseY = 0, baseZ = 0;
        if (semantic.source().relativePosition() != null) {
            baseX = semantic.source().relativePosition().x();
            baseY = semantic.source().relativePosition().y();
            baseZ = semantic.source().relativePosition().z();
        }

        // default group placement facing: use request facing, else inherit from slot
        Direction groupFacing = parseDir(getString(reqMap, "facing", "target_facing", "group_facing", "groupFacing"));
        if (groupFacing == null || !groupFacing.getAxis().isHorizontal()) {
            groupFacing = facingFromSlot(semantic);
        }
        if (groupFacing == null || !groupFacing.getAxis().isHorizontal()) groupFacing = Direction.SOUTH;

        Mirror groupMirror = parseMirror(getString(reqMap, "mirror", "mirror_mode", "mirrorMode", "group_mirror", "groupMirror"));

        // If mounting to a host socket: anchor and facing come from that socket
        MountTarget mt = parseMountTarget(reqMap);
        if (mt != null && mt.hostId != null && mt.socketId != null) {
            ComponentDefinition host = ComponentStorage.loadComponent(worldDir, mt.hostId);
            if (host == null) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount host not found: {}", mt.hostId);
                return List.of();
            }

            // host transform (reuse mount convention)
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
                return List.of();
            }

            // v1 新版 Socket 不包含坐标和朝向，使用默认值（旧行为兼容）
            BlockPos socketLocal = BlockPos.ORIGIN;
            BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, hostFromFacing, hostTransform);

            // 新版 Socket 不包含 facing()，使用默认朝向
            Direction socketLocalFacing = Direction.SOUTH;
            Direction socketWorldFacing = FacingTransformUtil.transformFacing(socketLocalFacing, hostFromFacing, hostTransform);
            if (socketWorldFacing == null || !socketWorldFacing.getAxis().isHorizontal()) socketWorldFacing = hostTargetFacing;

            // Set group anchor to socket position and facing to socket facing
            baseX = baseX + socketOff.getX();
            baseY = baseY + socketOff.getY();
            baseZ = baseZ + socketOff.getZ();
            groupFacing = socketWorldFacing;

            // carve default: true when mounting, unless explicitly disabled
            Boolean carve0 = getBoolNullable(reqMap, "carve", "carve_mask", "carveMask", "carve_socket", "carveSocket");
            boolean carve = carve0 == null || carve0;
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
                    BlockPos socketWorld = new BlockPos(baseX, baseY, baseZ);
                    for (int x = 0; x < mask.width(); x++) {
                        for (int y = 0; y < mask.height(); y++) {
                            for (int z = 0; z < mask.depth(); z++) {
                                BlockPos p = FacingUtil.offset(socketWorld, socketWorldFacing, x, y, z);
                                out.add(new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air"));
                            }
                        }
                    }
                }
            }
        }

        // style / semantic skin
        boolean semanticSkin = getBool(reqMap, "semantic_skin", "semanticSkin");
        String semanticStyleId = getString(reqMap, "semantic_style_id", "semanticStyleId", "style_id", "styleId");
        if (semanticStyleId == null) semanticStyleId = resolveSemanticStyleId(semantic.styleProfile());

        // Expand group + optional mounts (components OR nested groups)
        expandGroupInto(out, worldDir, group, baseX, baseY, baseZ, groupFacing, groupMirror,
                semanticSkin, semanticStyleId, world.getSeed(),
                reqMap, 0);

        return out;
    }

    // ===== helpers (mostly mirrored from PlayerComponentExpander, kept local to avoid widening API surface) =====

    private static final int MAX_NESTED_GROUP_DEPTH = 4;

    private static void expandGroupInto(List<BlockPatch> out,
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
        if (out == null || worldDir == null || group == null) return;
        if (depth > MAX_NESTED_GROUP_DEPTH) {
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: nested group depth exceeded at {}", group.getId());
            return;
        }

        // 预加载：避免“组件缺失但先 carve 挖洞”的副作用
        List<GroupComponentEntry> entries = group.getComponents();
        if (entries == null || entries.isEmpty()) return;
        List<ResolvedEntry> resolved = new ArrayList<>(entries.size());
        for (GroupComponentEntry e : entries) {
            if (e == null || e.componentId() == null || e.componentId().isBlank()) continue;
            ComponentDefinition def = ComponentStorage.loadComponent(worldDir, e.componentId().trim());
            if (def == null || def.blocks == null || def.blocks.isEmpty()) continue;
            resolved.add(new ResolvedEntry(e, def));
        }
        if (resolved.isEmpty()) {
            FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: group {} has no resolvable child components (missing ids?)", group.getId());
            return;
        }

        // expand group entries
        ComponentTransform groupTransform = new ComponentTransform(groupFacing, groupMirror);
        Direction groupFromFacing = Direction.SOUTH;

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

            addComponentPatches(out, def, childFromFacing, childTransform,
                    childBaseX, childBaseY, childBaseZ,
                    semanticSkin, semanticStyleId, seedBase);
        }

        // mounts on group sockets (Group acts as host)
        for (MountEntry me : parseMountEntries(reqMap)) {
            if (me == null) continue;
            if (me.socketId == null || me.socketId.isBlank()) continue;

            ComponentSocket socket = findGroupSocket(group, me.socketId);
            if (socket == null) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: group socket not found: {}.{}", group.getId(), me.socketId);
                continue;
            }

            // socket world offset (in group placement space)
            // v1 新版 Socket 不包含坐标，返回原点（旧行为兼容）
            BlockPos socketLocal = BlockPos.ORIGIN;
            BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, groupFromFacing, groupTransform);
            int socketX = baseX + socketOff.getX();
            int socketY = baseY + socketOff.getY();
            int socketZ = baseZ + socketOff.getZ();

            // 新版 Socket 不包含 facing()，使用默认朝向
            Direction socketLocalFacing = Direction.SOUTH;
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
                    expandGroupInto(out, worldDir, childGroup,
                            curAnchor.getX(), curAnchor.getY(), curAnchor.getZ(),
                            curFacing, childMirror,
                            semanticSkin, semanticStyleId, seedBase,
                            me.nestedReq != null ? me.nestedReq : Map.of(),
                            depth + 1);

                    if (i == repeat - 1) break;
                    SocketWorld next = resolveGroupSocketWorld(childGroup, curAnchor, curFacing, childMirror, repeatSocketId);
                    if (next == null) {
                        FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: repeat_socket not found: {}.{}", childGroup.getId(), repeatSocketId);
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
                continue;
            }

            // placementSpec 过滤：用 group socket 的语义附着类型约束挂载物
            AttachmentType hostAttachment = AttachmentRecognizer.attachmentForSocketContext(socket.context);
            if (!AttachmentRecognizer.isCompatible(mount.placementSpec, hostAttachment)) {
                FormacraftMod.LOGGER.warn("PlayerComponentGroupExpander: mount placementSpec incompatible: mount={} need={} groupSocketContext={} hostAttachment={}",
                        mount.id, mount.placementSpec.attachment,
                        socket.context, hostAttachment);
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
            } catch (Throwable ignored) {}
            // PlacementSpec v1：若不需要方向且未显式给 mount_facing，则保持构件自身朝向
            try {
                if ((me.mountFacing == null || me.mountFacing.isBlank())
                        && mount.placementSpec != null
                        && mount.placementSpec.facingPolicy == FacingPolicy.NONE) {
                    mountFacing = mountFromFacing;
                }
            } catch (Throwable ignored) {}
            Mirror mountMirror = parseMirror(me.mountMirror);
            ComponentTransform mountTransform = new ComponentTransform(mountFacing, mountMirror);

            addComponentPatches(out, mount, mountFromFacing, mountTransform,
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(),
                    semanticSkin, semanticStyleId, seedBase);
        }
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
                                                      String socketId) {
        if (group == null || groupAnchor == null) return null;
        ComponentSocket s = findGroupSocket(group, socketId);
        if (s == null) return null;

        Direction groupFromFacing = Direction.SOUTH;
        ComponentTransform groupTransform = new ComponentTransform(groupFacing, groupMirror);

        // 新版 Socket 不包含坐标和朝向，使用默认值
        BlockPos socketLocal = BlockPos.ORIGIN;
        BlockPos socketOff = ComponentTransformUtil.transformOffset(socketLocal, groupFromFacing, groupTransform);
        BlockPos pos = new BlockPos(
                groupAnchor.getX() + socketOff.getX(),
                groupAnchor.getY() + socketOff.getY(),
                groupAnchor.getZ() + socketOff.getZ()
        );

        // 新版 Socket 不包含 facing()，使用默认朝向
        Direction socketLocalFacing = Direction.SOUTH;
        Direction facing = FacingTransformUtil.transformFacing(socketLocalFacing, groupFromFacing, groupTransform);
        if (facing == null || !facing.getAxis().isHorizontal()) facing = groupFacing;

        return new SocketWorld(pos, facing);
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
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    private record SocketWorld(BlockPos pos, Direction facing) {}
}

