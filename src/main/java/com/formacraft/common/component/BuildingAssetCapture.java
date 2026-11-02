package com.formacraft.common.component;

import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.community.CommunityDownloadCache;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.util.math.Direction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** Compile-local asset use, returned explicitly with the compiled artifact. Never writes files. */
public final class BuildingAssetCapture implements AutoCloseable {
    public record Use(String assetId, String snapshot, String shape, int x, int y, int z,
                      String fromFacing, String facing, String mirror,
                      boolean materialRequested, boolean materialApplied, String style, long seed, String receiptId) {
        public Use(String assetId, String snapshot, String shape, int x, int y, int z, String fromFacing, String facing, String mirror,
                   boolean materialRequested, boolean materialApplied, String style, long seed) {
            this(assetId,snapshot,shape,x,y,z,fromFacing,facing,mirror,materialRequested,materialApplied,style,seed,null);
        }
    }
    public record Data(Map<String,ComponentPackageIO.Verified> sources, Map<String,String> shapes, List<Use> uses,
                       Map<String,CommunityDownloadCache.Cached> receipts) {
        public Data { sources = Map.copyOf(sources); shapes = Map.copyOf(shapes); uses = List.copyOf(uses); receipts = Map.copyOf(receipts); }
        public Data(Map<String,ComponentPackageIO.Verified> sources, Map<String,String> shapes, List<Use> uses) { this(sources,shapes,uses,Map.of()); }
        public static Data empty() { return new Data(Map.of(),Map.of(),List.of()); }
    }
    private static final ThreadLocal<BuildingAssetCapture> ACTIVE = new ThreadLocal<>();
    private final BuildingAssetCapture previous;
    private final Path world;
    private final UUID viewer;
    private final boolean enabled;
    private final Map<String,ComponentCatalog.Entry> visible = new HashMap<>();
    private final Map<String,ComponentPackageIO.Verified> sources = new LinkedHashMap<>();
    private final Map<String,ComponentPackageIO.Verified> loaded = new HashMap<>();
    private final Map<String,String> shapes = new LinkedHashMap<>();
    private final List<Use> uses = new ArrayList<>();
    private final Map<String,CommunityDownloadCache.Cached> receipts = new LinkedHashMap<>();
    private final Set<String> rawObjects = new HashSet<>();
    private long sourceBytes,shapeBytes,rawBytes;
    private boolean closed;
    private BuildingAssetCapture(Path world, UUID viewer, boolean enabled) {
        this.world = world; this.viewer = viewer; this.enabled = enabled; previous = ACTIVE.get();
        if (enabled) for (var entry : ComponentStorage.loadCatalog(world,viewer).components)
            if (entry != null && entry.packageRef != null) visible.put(entry.id,entry);
        ACTIVE.set(this);
    }
    public static BuildingAssetCapture open(Path world, UUID viewer) { return new BuildingAssetCapture(world,viewer,true); }
    public static BuildingAssetCapture disabled() { return new BuildingAssetCapture(null,null,false); }
    public Data data() { return new Data(sources,shapes,uses,receipts); }
    public static void record(ComponentDefinition definition, Direction from, ComponentTransform transform,
                              int x,int y,int z,boolean materialRequested,boolean materialApplied,String style,long seed) {
        var capture = ACTIVE.get(); if (capture == null || !capture.enabled || definition == null) return;
        try {
            var selected = ComponentSourceBinding.lookup(capture.world,capture.viewer,definition);
            var entry = capture.visible.get(definition.id); if (entry == null) return;
            var receipt = selected == null ? entry.recipe ? ComponentRecipeLibrary.communitySource(capture.viewer,definition.id) : null : selected.receipt();
            var source = receipt == null ? capture.loaded.get(definition.id) : receipt.download().verified();
            if (source == null) {
                source = ComponentLibraryOperations.export(capture.world,capture.viewer,definition.id,entry.packageRef,0,entry.packageRef);
            }
            String snapshot = ComponentAssetSnapshots.key(source), json = JsonUtil.toJson(definition), shape = digest(json);
            if (receipt != null) ComponentSourceBinding.assertSource(receipt,source);
            String rawHash = receipt == null ? null : receipt.details().descriptor().releaseRef().packageSha256();
            long additionalRaw = receipt == null || capture.rawObjects.contains(rawHash) ? 0 : receipt.details().descriptor().byteLength();
            long additionalSource = capture.sources.containsKey(snapshot) ? 0 : source.toBytes().length;
            long additionalShape = capture.shapes.containsKey(shape) ? 0 : json.getBytes(StandardCharsets.UTF_8).length;
            if (json.length() > ComponentIds.MAX_JSON_CHARS || capture.uses.size() >= 65536
                    || capture.sources.size() >= 256 && !capture.sources.containsKey(snapshot)
                    || capture.shapes.size() >= 1024 && !capture.shapes.containsKey(shape)
                    || capture.sourceBytes+additionalSource > 32L*1024*1024 || capture.shapeBytes+additionalShape > 8L*1024*1024
                    || capture.rawBytes+additionalRaw > 32L*1024*1024 || receipt != null && capture.receipts.size() >= 256 && !capture.receipts.containsKey(receipt.receiptId()))
                throw new IOException("建筑构件锁超出容量限制");
            capture.sourceBytes += additionalSource; capture.shapeBytes += additionalShape;
            if (receipt == null) capture.loaded.put(definition.id,source);
            if (receipt != null) { capture.receipts.put(receipt.receiptId(),receipt); capture.rawObjects.add(rawHash); capture.rawBytes += additionalRaw; }
            capture.sources.put(snapshot,source); capture.shapes.put(shape,json);
            capture.uses.add(new Use(definition.id,snapshot,shape,x,y,z,from.name(),transform.facing().name(),transform.mirror().name(),
                    materialRequested,materialApplied,style,seed,receipt == null ? null : receipt.receiptId()));
        } catch (IOException invalid) { throw new IllegalStateException("无法锁定实际使用的构件版本",invalid); }
    }
    public static String digest(String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public void close() {
        if (closed) return;
        if (ACTIVE.get() != this) throw new IllegalStateException("Asset capture scopes must close in order");
        closed = true; if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}
