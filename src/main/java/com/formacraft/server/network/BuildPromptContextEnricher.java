package com.formacraft.server.network;

import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.component.group.ComponentGroupRegistry;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;

/**
 * Fills compact catalog / group / nearby-memory summaries on {@link FormaRequest}
 * so the Python LlmPlan prompt can see player-owned library context.
 */
public final class BuildPromptContextEnricher {
    private static final int MAX_SUMMARY_CHARS = 1800;
    private static final int MAX_CATALOG_ENTRIES = 24;

    private BuildPromptContextEnricher() {}

    public static void enrich(FormaRequest req, ServerPlayerEntity player) {
        if (req == null || player == null) {
            return;
        }
        if (blank(req.getComponentCatalogSummary())) {
            req.setComponentCatalogSummary(truncate(catalogSummary(player), MAX_SUMMARY_CHARS));
        }
        if (blank(req.getComponentGroupSummary())) {
            req.setComponentGroupSummary(truncate(ComponentGroupRegistry.summary(), MAX_SUMMARY_CHARS));
        }
        if (blank(req.getNearbyMemorySummary())) {
            req.setNearbyMemorySummary(truncate(nearbyMemorySummary(player, req.getPlayerPos()), MAX_SUMMARY_CHARS));
        }
    }

    private static String catalogSummary(ServerPlayerEntity player) {
        try {
            Path worldDir = null;
            if (player.getEntityWorld() instanceof ServerWorld sw && sw.getServer() != null) {
                worldDir = sw.getServer().getSavePath(WorldSavePath.ROOT);
            }
            ComponentCatalog cat = ComponentStorage.loadCatalogWithSockets(worldDir, player.getUuid());
            if (cat == null || cat.components == null || cat.components.isEmpty()) {
                return "(no player components registered)";
            }
            StringBuilder sb = new StringBuilder("Player component library:");
            int n = 0;
            for (ComponentCatalog.Entry e : cat.components) {
                if (e == null || e.id == null || e.id.isBlank()) {
                    continue;
                }
                sb.append("\n- ")
                        .append(e.category != null ? e.category.name() : "UNKNOWN")
                        .append(": ")
                        .append(e.name != null && !e.name.isBlank() ? e.name : e.id)
                        .append(" (id=").append(e.id).append(")");
                if (e.sockets != null && !e.sockets.isEmpty()) {
                    sb.append(" sockets=").append(e.sockets.size());
                }
                n++;
                if (n >= MAX_CATALOG_ENTRIES) {
                    int remaining = cat.components.size() - n;
                    if (remaining > 0) {
                        sb.append("\n- ... (+").append(remaining).append(" more)");
                    }
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(component catalog unavailable)";
        }
    }

    private static String nearbyMemorySummary(ServerPlayerEntity player, BlockPos origin) {
        try {
            MemoryManager memory = BuildExecutionService.getInstance().getMemoryManager();
            if (memory == null || !(player.getEntityWorld() instanceof ServerWorld sw)) {
                return "(no nearby building memory)";
            }
            BlockPos pos = origin != null ? origin : player.getBlockPos();
            ProjectMemory nearest = memory.findNearest(sw.getRegistryKey().getValue(), pos, 96.0);
            if (nearest == null) {
                return "(no nearby building memory within 96 blocks)";
            }
            StringBuilder sb = new StringBuilder("Nearby building memory:");
            sb.append("\n- name=").append(nearest.getName() == null ? "?" : nearest.getName());
            if (nearest.getUuid() != null) {
                sb.append(" uuid=").append(nearest.getUuid());
            }
            if (nearest.getTags() != null && !nearest.getTags().isEmpty()) {
                sb.append(" tags=").append(nearest.getTags());
            }
            if (nearest.getBounds() != null) {
                BlockPos min = nearest.getBounds().getMinPos();
                BlockPos max = nearest.getBounds().getMaxPos();
                if (min != null && max != null) {
                    sb.append(" aabb=(")
                            .append(min.getX()).append(',').append(min.getY()).append(',').append(min.getZ())
                            .append(")-(")
                            .append(max.getX()).append(',').append(max.getY()).append(',').append(max.getZ())
                            .append(')');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(building memory unavailable)";
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }
}
