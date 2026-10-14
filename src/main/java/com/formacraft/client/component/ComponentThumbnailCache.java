package com.formacraft.client.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.ComponentIds;
import com.formacraft.common.component.ComponentStorage;
import net.minecraft.client.MinecraftClient;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Full-resolution, bounded cache. Disk reads and rasterization never run in GUI draw calls.
 * Old flat-color PNGs are replaced in memory from the component's actual block definition. */
public final class ComponentThumbnailCache {
    private ComponentThumbnailCache() {}

    enum Status { LOADING, READY, FAILED }

    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(64, .75f, true);
    private static final Set<Cached> LOADING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AtomicInteger RESOURCE_EPOCH = new AtomicInteger(0);

    private static final class Cached {
        final long revision;
        final int resourceEpoch;
        Status status = Status.LOADING;
        int failCount;
        BufferedImage image;
        Cached(long revision, int resourceEpoch) {
            this.revision = revision;
            this.resourceEpoch = resourceEpoch;
        }
    }

    private record Loaded(com.formacraft.common.component.ComponentDefinition definition, BufferedImage fallback) {}

    public static BufferedImage getImage(String componentId, long revision) {
        String id = ComponentIds.tryNormalizeLoadId(componentId);
        if (id == null) return null;
        int epoch = RESOURCE_EPOCH.get();
        Cached cached = CACHE.get(id);
        if (cached != null && cached.revision == revision) {
            if (cached.status == Status.READY) return cached.image;
            if (cached.status == Status.LOADING) return null;
            // FAILED: retry only when under limit or resource pack epoch advanced
            if (cached.status == Status.FAILED) {
                if (cached.failCount >= 2 && cached.resourceEpoch == epoch) return null;
                // fall through to re-request
            } else {
                return null;
            }
        }
        if (LOADING.size() >= 2) return null;
        int priorFails = (cached != null && cached.status == Status.FAILED) ? cached.failCount : 0;
        Cached request = new Cached(revision, epoch);
        request.status = Status.LOADING;
        request.failCount = priorFails;
        CACHE.put(id, request);
        LOADING.add(request);
        while (CACHE.size() > 96) CACHE.remove(CACHE.keySet().iterator().next());
        MinecraftClient client = MinecraftClient.getInstance();
        UUID player = client.player == null ? null : client.player.getUuid();
        CompletableFuture.supplyAsync(() -> {
            var definition = ComponentStorage.loadComponent(null, id, player);
            if (definition != null) return new Loaded(definition, null);
            var png = ComponentIds.tryResolveComponentFile(ComponentStorage.getGlobalComponentDir(), id, ".png");
            try {
                if (png != null && Files.isRegularFile(png) && Files.size(png) < 2_000_000)
                    return new Loaded(null, ImageIO.read(png.toFile()));
            } catch (Exception e) { FormacraftMod.LOGGER.debug("Thumbnail fallback read failed: {}", id, e); }
            return new Loaded(null, null);
        }).whenComplete((loaded, error) -> client.execute(() -> {
            if (CACHE.get(id) != request) { LOADING.remove(request); return; }
            if (error != null || loaded == null || loaded.definition == null) {
                BufferedImage fb = loaded == null ? null : loaded.fallback;
                if (fb != null) {
                    request.image = fb;
                    request.status = Status.READY;
                    request.failCount = 0;
                } else {
                    request.image = null;
                    request.status = Status.FAILED;
                    request.failCount = Math.max(1, request.failCount + 1);
                }
                LOADING.remove(request);
                return;
            }
            ComponentThumbnailGenerator.generateAsync(loaded.definition).whenComplete((image, renderError) -> client.execute(() -> {
                LOADING.remove(request);
                if (CACHE.get(id) != request) return;
                if (renderError == null && image != null) {
                    request.image = image;
                    request.status = Status.READY;
                    request.failCount = 0;
                } else {
                    request.image = null;
                    request.status = Status.FAILED;
                    request.failCount = Math.max(1, request.failCount + 1);
                    if (renderError != null) {
                        FormacraftMod.LOGGER.warn("Model thumbnail failed: {}", id, renderError);
                    }
                }
            }));
        }));
        return null;
    }

    public static void clearCache(String id) { CACHE.remove(id); }

    public static void clearAllCache() {
        CACHE.clear();
        LOADING.clear();
    }

    /** Resource-pack / client resource reload: bump epoch so FAILED entries may retry. */
    public static void onResourceReload() {
        RESOURCE_EPOCH.incrementAndGet();
        clearAllCache();
    }

    /** Test / diagnostics. */
    static int resourceEpoch() { return RESOURCE_EPOCH.get(); }
}
