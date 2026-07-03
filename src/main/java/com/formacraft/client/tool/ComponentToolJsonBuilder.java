package com.formacraft.client.tool;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.placement.AttachmentType;
import com.formacraft.common.component.placement.ComponentPlacementAnalyzer;
import com.formacraft.common.component.placement.PlacementCaptureContext;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 构件捕获：从选区/草案构造 ComponentDefinition JSON。
 */
final class ComponentToolJsonBuilder {
    private ComponentToolJsonBuilder() {}

    static String build(MinecraftClient client, ComponentToolState state, List<ComponentSocket> sockets, ComponentCaptureDraft draft) {
        if (client == null || client.world == null) return null;
        if (!ComponentToolCaptureSupport.hasValidSelection(draft)) return null;

        BlockPos anchor = draft.anchor.worldPos;
        if (anchor == null) return null;
        if (!ComponentToolCaptureSupport.isAnchorAllowed(state, anchor, draft)) return null;

        BlockPos min, max;
        if (draft.hasExplicitSelection()) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : draft.selection.blocks) {
                if (pos == null) continue;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            min = new BlockPos(minX, minY, minZ);
            max = new BlockPos(maxX, maxY, maxZ);
        } else {
            min = SelectionTool.INSTANCE.getMin();
            max = SelectionTool.INSTANCE.getMax();
            if (min == null || max == null) {
                min = draft.selection.aabbMin;
                max = draft.selection.aabbMax;
            }
            if (min == null || max == null) return null;
        }

        int minX = min.getX();
        int minY = min.getY();
        int minZ = min.getZ();
        int maxX = max.getX();
        int maxY = max.getY();
        int maxZ = max.getZ();

        ComponentDefinition def = new ComponentDefinition();
        def.id = makeId(state.category, state.name);
        def.name = state.name;
        def.category = state.category != null ? state.category : ComponentCategory.GENERIC;
        def.tags = state.tags != null ? new ArrayList<>(state.tags) : new ArrayList<>();

        ComponentDefinition.Size size = new ComponentDefinition.Size();
        size.w = (maxX - minX + 1);
        size.h = (maxY - minY + 1);
        size.d = (maxZ - minZ + 1);
        def.size = size;

        ComponentDefinition.Anchor a = new ComponentDefinition.Anchor();
        a.dx = 0;
        a.dy = 0;
        a.dz = 0;
        a.facing = (draft.orientation.facing != null ? draft.orientation.facing : Direction.SOUTH).name();
        def.anchor = a;

        if (size.w > 0 && size.h > 0 && size.d > 0) {
            ComponentDefinition.AnchorHint hint = new ComponentDefinition.AnchorHint();
            hint.u = (float) ((anchor.getX() - minX + 0.5) / (double) size.w);
            hint.v = (float) ((anchor.getY() - minY) / (double) size.h);
            hint.w = (float) ((anchor.getZ() - minZ + 0.5) / (double) size.d);
            def.anchorHint = hint;
        }

        def.allowed_facing = java.util.Set.of("NORTH", "SOUTH", "EAST", "WEST");
        def.placement_rules = new ComponentDefinition.PlacementRules();
        if (sockets != null && !sockets.isEmpty()) {
            def.sockets = new ArrayList<>(sockets);
        }

        ComponentDefinition.DirectionHints hints = buildDirectionHints(anchor, draft);
        if (hints != null) {
            def.directionHints = hints;
        }

        def.blocks = new ArrayList<>();
        int minDy = Integer.MAX_VALUE;

        java.util.Set<BlockPos> blocksToScan;
        if (draft.hasExplicitSelection()) {
            blocksToScan = draft.selection.blocks;
        } else {
            blocksToScan = new java.util.HashSet<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        blocksToScan.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        if (state.semanticTagOnSave || state.semanticSkin) {
            for (BlockPos p : blocksToScan) {
                if (p == null) continue;
                BlockState bs = client.world.getBlockState(p);
                if (bs == null || bs.isAir()) continue;
                minDy = Math.min(minDy, p.getY() - anchor.getY());
            }
            if (minDy == Integer.MAX_VALUE) minDy = 0;
        }

        for (BlockPos p : blocksToScan) {
            if (p == null) continue;
            BlockState bs = client.world.getBlockState(p);
            if (bs == null || bs.isAir()) continue;

            ComponentDefinition.BlockEntry be = new ComponentDefinition.BlockEntry();
            be.dx = p.getX() - anchor.getX();
            be.dy = p.getY() - anchor.getY();
            be.dz = p.getZ() - anchor.getZ();
            be.block = serializeBlockState(bs);
            if (state.semanticTagOnSave) {
                be.semantic = guessSemanticPart(bs, be.dy, minDy);
            } else if (state.semanticSkin) {
                be.semantic = (state.semanticPart != null) ? state.semanticPart : guessSemanticPart(bs, be.dy, minDy);
            }
            def.blocks.add(be);
        }

        def.placementSpec = ComponentPlacementAnalyzer.analyze(def, toPlacementContext(draft));
        applyPlacementHints(def, draft);

        com.formacraft.common.component.semantic.ComponentSemanticInference.ensureSemanticFields(def);
        if (state.culturalStyleOverride != null && !state.culturalStyleOverride.isBlank()) {
            def.culturalStyle = state.culturalStyleOverride;
        }
        if (state.geometryArchetypeOverride != null && !state.geometryArchetypeOverride.isBlank()) {
            def.geometryArchetype = state.geometryArchetypeOverride;
        } else if (def.geometryArchetype == null || def.geometryArchetype.isBlank()) {
            def.geometryArchetype = com.formacraft.common.component.semantic.ComponentSemanticInference.inferGeometryArchetype(def);
        }
        return JsonUtil.toJson(def);
    }

    static PlacementCaptureContext toPlacementContext(ComponentCaptureDraft draft) {
        PlacementCaptureContext ctx = PlacementCaptureContext.createDefault();
        if (draft == null) {
            return ctx;
        }
        ctx.userAttachment = draft.host.attachment != null ? draft.host.attachment : AttachmentType.NONE;
        ctx.userAttachmentManual = draft.host.manualAttachment || draft.host.confirmed;
        ctx.hasInteriorExterior = draft.orientation.hasInteriorExterior;
        ctx.hasBottomTop = draft.orientation.hasBottomTop;
        ctx.hasInsideOutsideMarks = draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null;
        ctx.hasBottomTopMarks = draft.orientation.bottomMarkWorld != null && draft.orientation.topMarkWorld != null;
        ctx.hasHostFace = draft.host.referenceBlock != null && draft.host.normal != null;
        return ctx;
    }

    static void applyPlacementHints(ComponentDefinition def, ComponentCaptureDraft draft) {
        if (def == null || draft == null) {
            return;
        }
        ComponentDefinition.PlacementHints hints = new ComponentDefinition.PlacementHints();
        if (def.placementSpec != null) {
            hints.attachment = def.placementSpec.attachment.name();
            hints.needsHostFace = def.placementSpec.attachment == AttachmentType.WALL_OPENING
                    || def.placementSpec.attachment == AttachmentType.WALL_SURFACE
                    || def.placementSpec.attachment == AttachmentType.ROOF_SURFACE;
        } else if (draft.host.attachment != null) {
            hints.attachment = draft.host.attachment.name();
        }
        if (draft.orientation.hasBottomTop || draft.orientation.bottomMarkWorld != null) {
            hints.primaryAxis = "V";
        } else if (draft.orientation.hasInteriorExterior || draft.orientation.insideMarkWorld != null) {
            hints.primaryAxis = "W";
        }
        def.placementHints = hints;
    }

    static ComponentDefinition.DirectionHints buildDirectionHints(BlockPos anchor, ComponentCaptureDraft draft) {
        ComponentDefinition.DirectionHints hints = new ComponentDefinition.DirectionHints();
        boolean hasAny = false;

        if (draft.host.attachment != null) {
            hints.attachmentMode = draft.host.attachment.name();
            hasAny = true;
        }
        if (draft.orientation.hasInteriorExterior) {
            hints.hasInteriorExterior = true;
            hasAny = true;
        }
        if (draft.orientation.hasBottomTop) {
            hints.hasBottomTop = true;
            hasAny = true;
        }

        if (draft.orientation.insideMarkWorld != null && anchor != null) {
            hints.inside = toMark(draft.orientation.insideMarkWorld, anchor);
            hasAny = true;
        }
        if (draft.orientation.outsideMarkWorld != null && anchor != null) {
            hints.outside = toMark(draft.orientation.outsideMarkWorld, anchor);
            hasAny = true;
        }
        if (draft.orientation.bottomMarkWorld != null && anchor != null) {
            hints.bottom = toMark(draft.orientation.bottomMarkWorld, anchor);
            hasAny = true;
        }
        if (draft.orientation.topMarkWorld != null && anchor != null) {
            hints.top = toMark(draft.orientation.topMarkWorld, anchor);
            hasAny = true;
        }

        if (draft.host.referenceBlock != null && draft.host.normal != null && anchor != null) {
            ComponentDefinition.DirectionHints.HostFace host = new ComponentDefinition.DirectionHints.HostFace();
            host.dx = draft.host.referenceBlock.getX() - anchor.getX();
            host.dy = draft.host.referenceBlock.getY() - anchor.getY();
            host.dz = draft.host.referenceBlock.getZ() - anchor.getZ();
            host.normal = draft.host.normal.name();
            host.allowAir = draft.anchor.allowOutsideSelection;
            hints.hostFace = host;
            hasAny = true;
        }

        return hasAny ? hints : null;
    }

    static ComponentDefinition.DirectionHints.Mark toMark(BlockPos pos, BlockPos anchor) {
        ComponentDefinition.DirectionHints.Mark m = new ComponentDefinition.DirectionHints.Mark();
        m.dx = pos.getX() - anchor.getX();
        m.dy = pos.getY() - anchor.getY();
        m.dz = pos.getZ() - anchor.getZ();
        return m;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static SemanticPart guessSemanticPart(BlockState bs, int dy, int minDy) {
        if (bs == null) return SemanticPart.WALL;

        if (dy == minDy) {
            return SemanticPart.FOUNDATION;
        }

        var b = bs.getBlock();
        if (b instanceof net.minecraft.block.DoorBlock || b instanceof net.minecraft.block.TrapdoorBlock) {
            return SemanticPart.DOORWAY;
        }
        String id = null;
        try {
            var bid = Registries.BLOCK.getId(b);
            id = bid.toString();
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[ComponentToolJsonBuilder] resolve block id for semantic guess failed", t);
        }
        if ((id != null && id.contains("glass_pane")) || b == net.minecraft.block.Blocks.GLASS) {
            return SemanticPart.WINDOW;
        }
        if (b instanceof net.minecraft.block.FenceBlock || b instanceof net.minecraft.block.FenceGateBlock || b == net.minecraft.block.Blocks.IRON_BARS) {
            return SemanticPart.RAILING;
        }
        if (b instanceof net.minecraft.block.LanternBlock || b instanceof net.minecraft.block.TorchBlock) {
            return SemanticPart.LIGHT;
        }
        if (b instanceof net.minecraft.block.StairsBlock) {
            return SemanticPart.STAIR_STEP;
        }
        if (b instanceof net.minecraft.block.SlabBlock) {
            return SemanticPart.FLOOR;
        }

        try {
            for (Property<?> p : bs.getProperties()) {
                if (p == null) continue;
                if (!"axis".equalsIgnoreCase(p.getName())) continue;
                Object v = bs.get((Property) p);
                if (v instanceof net.minecraft.util.math.Direction.Axis axis) {
                    return axis == net.minecraft.util.math.Direction.Axis.Y ? SemanticPart.PILLAR : SemanticPart.BEAM;
                }
            }
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[ComponentToolJsonBuilder] read axis property for semantic guess failed", t);
        }

        return SemanticPart.WALL;
    }

    static SemanticPart guessSemanticPartFromString(String blockStateString, int dy, int minDy) {
        if (dy == minDy) return SemanticPart.FOUNDATION;
        if (blockStateString == null) return SemanticPart.WALL;
        String s = blockStateString.toLowerCase(Locale.ROOT);
        if (s.contains("door") || s.contains("trapdoor")) return SemanticPart.DOORWAY;
        if (s.contains("glass_pane") || s.contains("stained_glass_pane") || s.contains(":glass")) return SemanticPart.WINDOW;
        if (s.contains("fence") || s.contains("iron_bars") || s.contains("bars")) return SemanticPart.RAILING;
        if (s.contains("lantern") || s.contains("torch")) return SemanticPart.LIGHT;
        if (s.contains("stairs")) return SemanticPart.STAIR_STEP;
        if (s.contains("slab")) return SemanticPart.FLOOR;
        if (s.contains("log") || s.contains("stem")) return SemanticPart.PILLAR;
        return SemanticPart.WALL;
    }

    static long mixSeed(BlockPos anchor, BlockPos off, SemanticPart part) {
        long ax = anchor != null ? anchor.getX() : 0;
        long ay = anchor != null ? anchor.getY() : 0;
        long az = anchor != null ? anchor.getZ() : 0;
        long x = off != null ? off.getX() : 0;
        long y = off != null ? off.getY() : 0;
        long z = off != null ? off.getZ() : 0;
        long p = part != null ? part.ordinal() : 0;

        long h = 1469598103934665603L;
        h ^= ax; h *= 1099511628211L;
        h ^= ay; h *= 1099511628211L;
        h ^= az; h *= 1099511628211L;
        h ^= x;  h *= 1099511628211L;
        h ^= y;  h *= 1099511628211L;
        h ^= z;  h *= 1099511628211L;
        h ^= p;  h *= 1099511628211L;
        return h;
    }

    static Direction parseDir(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Direction.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            FormacraftMod.LOGGER.debug("[ComponentToolJsonBuilder] invalid direction string: {}", s);
            return null;
        }
    }

    static String makeId(ComponentCategory cat, String name) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (n.isBlank()) n = "component";
        return n;
    }

    static String serializeBlockState(BlockState state) {
        String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        if (state.getEntries().isEmpty()) return id;

        List<Map.Entry<Property<?>, Comparable<?>>> entries = new ArrayList<>(state.getEntries().entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().getName()));

        StringBuilder sb = new StringBuilder(id);
        sb.append("[");
        boolean first = true;
        for (Map.Entry<Property<?>, Comparable<?>> e : entries) {
            if (!first) sb.append(",");
            first = false;
            sb.append(e.getKey().getName()).append("=").append(e.getValue());
        }
        sb.append("]");
        return sb.toString();
    }
}
