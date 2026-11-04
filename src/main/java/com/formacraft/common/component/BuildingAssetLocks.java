package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.component.community.CommunityDownloadCache;
import com.formacraft.common.patch.BlockPatch;
import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Private world-scoped immutable building designs. Reading/restoring never writes the world. */
public final class BuildingAssetLocks {
    private BuildingAssetLocks() {}
    public static final int MAX_PATCHES = 1_000_000, MAX_PATCH_BYTES = 128*1024*1024, MAX_MANIFEST = 16*1024*1024, MAX_PLAN = 2*1024*1024;
    public static final String ENGINE = "formacraft.building-assets.v1";
    public record Dependency(String contentRef, String manifestSha256) {}
    public record Revision(String parentId, UUID buildingId, UUID transactionId, long number) {}
    public record Manifest(String schema, String id, String owner, String dimension, long createdAt, String engine, String producerVersion, long seed,
                           int originX,int originY,int originZ,String planSha256,String patchesSha256,int patchCount,
                           Map<String,Dependency> dependencies,Map<String,String> shapes,List<BuildingAssetCapture.Use> uses,Map<String,String> communityReceipts,
                           Revision revision) {
        public Manifest { dependencies = Map.copyOf(dependencies); shapes = Map.copyOf(shapes); uses = List.copyOf(uses); communityReceipts = Map.copyOf(communityReceipts); }
    }
    public record Loaded(Manifest manifest,String inputPlan,List<BlockPatch> patches) {
        public Loaded { patches = List.copyOf(patches); }
    }
    public record RestoreResult(Map<String,String> aliases,List<String> failures,Map<Integer,String> useAliases,Map<Integer,ComponentSourceBinding.Selection> selections) {
        public RestoreResult { aliases = Map.copyOf(aliases); failures = List.copyOf(failures); useAliases = Map.copyOf(useAliases); selections = Map.copyOf(selections); }
        public boolean complete() { return failures.isEmpty(); }
    }
    public static boolean validId(String id) { return id != null && id.matches("lock_[0-9a-f]{32}"); }
    private static Path vault(Path world,UUID owner,boolean create) throws IOException {
        if (world == null || owner == null) throw new IOException("缺少建筑锁上下文");
        Path root = ComponentAssetSnapshots.child(world,"formacraft",create);
        root = ComponentAssetSnapshots.child(root,"building_assets",create);
        root = ComponentAssetSnapshots.child(root,"players",create);
        return ComponentAssetSnapshots.child(root,owner.toString(),create);
    }
    private static Path directory(Path world,UUID owner,String id) throws IOException {
        if (!validId(id)) throw new IOException("建筑锁 ID 无效");
        return ComponentAssetSnapshots.child(ComponentAssetSnapshots.child(vault(world,owner,false),"locks",false),id,false);
    }
    private static Path file(Path directory,String name) throws IOException {
        Path file = directory.resolve(name);
        if (!file.toRealPath().equals(directory.toRealPath().resolve(name))) throw new IOException("建筑锁文件重定向");
        return file;
    }
    public static String save(Path world,UUID owner,String dimension,long seed,int x,int y,int z,String inputPlan,
                              BuildingAssetCapture.Data data,List<BlockPatch> patches) throws IOException {
        return save(world,owner,dimension,seed,x,y,z,inputPlan,data,patches,ignored -> {});
    }
    @FunctionalInterface interface BeforePublish { void run(Path staging) throws IOException; }
    static String save(Path world,UUID owner,String dimension,long seed,int x,int y,int z,String inputPlan,
                       BuildingAssetCapture.Data data,List<BlockPatch> patches,BeforePublish hook) throws IOException {
        return save(world,owner,dimension,seed,x,y,z,inputPlan,data,patches,hook,
                "lock_"+UUID.randomUUID().toString().replace("-",""),null);
    }

    public static String saveRevision(Path world, UUID owner, String dimension, Revision revision,
                                      int x, int y, int z, String inputPlan,
                                      BuildingAssetCapture.Data assets, List<BlockPatch> committed) throws IOException {
        if (!validRevision(revision) || assets == null || committed == null || committed.isEmpty() || inputPlan == null) {
            throw new IOException("建筑锁修订内容无效");
        }
        String id = revisionId(revision);
        synchronized (ComponentStorage.class) {
            var parent = load(world, owner, revision.parentId(), dimension);
            var previous = parent.manifest().revision();
            if (previous == null ? revision.number() != 1
                    : !previous.buildingId().equals(revision.buildingId())
                    || previous.number() != revision.number() - 1
                    || parent.manifest().originX() != x || parent.manifest().originY() != y || parent.manifest().originZ() != z) {
                throw new IOException("建筑锁父版本不一致");
            }
            var patches = mergePatches(parent.patches(), committed);
            Loaded existing;
            try {
                directory(world, owner, id);
            } catch (NoSuchFileException missing) {
                return save(world, owner, dimension, parent.manifest().seed(), x, y, z, inputPlan,
                        assets, patches, ignored -> {}, id, revision);
            }
            existing = load(world, owner, id, dimension);
            var manifest = existing.manifest();
            if (!revision.equals(manifest.revision()) || manifest.originX() != x || manifest.originY() != y || manifest.originZ() != z
                    || !inputPlan.equals(existing.inputPlan()) || !patches.equals(existing.patches())
                    || !assets.sources().keySet().equals(manifest.dependencies().keySet())
                    || !assets.shapes().equals(manifest.shapes()) || !assets.uses().equals(manifest.uses())
                    || !assets.receipts().keySet().equals(manifest.communityReceipts().keySet())) {
                throw new IOException("建筑锁事务已归档为不同内容");
            }
            return id;
        }
    }

    private static boolean validRevision(Revision revision) {
        return revision != null && validId(revision.parentId()) && revision.buildingId() != null
                && revision.transactionId() != null && revision.number() > 0;
    }

    private static String revisionId(Revision revision) {
        String key = "formacraft.asset-revision:" + revision.buildingId() + ":" + revision.transactionId();
        return "lock_" + BuildingAssetCapture.digest(key).substring(0, 32);
    }

    private static String save(Path world,UUID owner,String dimension,long seed,int x,int y,int z,String inputPlan,
                       BuildingAssetCapture.Data data,List<BlockPatch> patches,BeforePublish hook,String id,Revision revision) throws IOException {
        if (owner == null || dimension == null || dimension.isBlank() || dimension.length() > 256 || inputPlan == null
                || data == null || revision == null && data.sources().isEmpty() || patches == null || patches.isEmpty() || patches.size() > MAX_PATCHES)
            throw new IOException("建筑锁内容为空或超出限制");
        byte[] plan = inputPlan.getBytes(StandardCharsets.UTF_8); if (plan.length > MAX_PLAN) throw new IOException("建筑方案过大");
        for (var patch : patches) validatePatch(patch);
        long sourceBytes = 0;
        for (var source : data.sources().values()) { sourceBytes += source.toBytes().length; if (sourceBytes > 32L*1024*1024) throw new IOException("建筑原包快照总量过大"); }
        synchronized (ComponentStorage.class) {
            Path root = vault(world,owner,true), locks = ComponentAssetSnapshots.child(root,"locks",true);
            var dependencies = new TreeMap<String,Dependency>();
            for (var entry : data.sources().entrySet()) {
                if (!entry.getKey().equals(ComponentAssetSnapshots.key(entry.getValue()))) throw new IOException("构件锁身份不一致");
                ComponentAssetSnapshots.save(root,entry.getValue());
                dependencies.put(entry.getKey(),new Dependency(entry.getValue().contentRef(),entry.getValue().manifestSha256()));
            }
            var receiptSources = new TreeMap<String,String>();
            long rawBytes = 0, receiptBytes = 0; var rawObjects = new HashSet<String>();
            for (var entry : data.receipts().entrySet()) {
                var receipt = entry.getValue(); var source = receipt.download().verified(); String snapshot = ComponentAssetSnapshots.key(source);
                if (!entry.getKey().equals(receipt.receiptId()) || !data.sources().containsKey(snapshot)) throw new IOException("建筑收据缺少原包依赖");
                ComponentSourceBinding.assertSource(receipt,data.sources().get(snapshot));
                String hash = receipt.details().descriptor().releaseRef().packageSha256();
                receiptBytes += receipt.details().descriptor().byteLength();
                if (rawObjects.add(hash)) rawBytes += receipt.details().descriptor().byteLength();
                if (rawBytes > 32L*1024*1024 || receiptBytes > CommunityDownloadCache.MAX_OBJECT_BYTES) throw new IOException("建筑原始下载对象或收据总量超限");
                CommunityDownloadCache.pin(root.resolve("community_downloads"),receipt,ComponentPackageIO.currentEnvironment());
                receiptSources.put(entry.getKey(),snapshot);
            }
            Path staging = Files.createTempDirectory(locks,".pending-");
            try {
                Files.write(staging.resolve("input-plan.json"),plan,StandardOpenOption.CREATE_NEW);
                Path patchFile = staging.resolve("patches.json.gz");
                try (var output = new LimitedOutput(new GZIPOutputStream(Files.newOutputStream(patchFile,StandardOpenOption.CREATE_NEW)),MAX_PATCH_BYTES);
                     var writer = new OutputStreamWriter(output,StandardCharsets.UTF_8)) { JsonUtil.get().toJson(patches,writer); }
                String producerVersion = ComponentPackageIO.currentEnvironment().installedMods().getOrDefault("formacraft","development");
                var manifest = new Manifest("formacraft.building_asset_lock.v1",id,owner.toString(),dimension,System.currentTimeMillis(),ENGINE,producerVersion,seed,
                        x,y,z,BuildingAssetCapture.digest(plan),fileHash(patchFile),patches.size(),dependencies,data.shapes(),data.uses(),receiptSources,revision);
                validateManifest(manifest,owner,id);
                try (var output = new LimitedOutput(Files.newOutputStream(staging.resolve("manifest.json"),StandardOpenOption.CREATE_NEW),MAX_MANIFEST);
                     var writer = new OutputStreamWriter(output,StandardCharsets.UTF_8)) { JsonUtil.get().toJson(manifest,writer); }
                hook.run(staging); Files.move(staging,locks.resolve(id),StandardCopyOption.ATOMIC_MOVE); return id;
            } finally { cleanup(staging); }
        }
    }
    private static Manifest manifest(Path directory,UUID owner,String id) throws IOException {
        try {
            String json = text(file(directory,"manifest.json"),MAX_MANIFEST);
            var manifest = JsonUtil.fromJson(json,Manifest.class); validateManifest(manifest,owner,id); return manifest;
        } catch (RuntimeException invalid) { throw new IOException("建筑锁清单无效",invalid); }
    }
    private static void validateManifest(Manifest m,UUID owner,String id) throws IOException {
        if (m == null || !"formacraft.building_asset_lock.v1".equals(m.schema()) || !ENGINE.equals(m.engine())
                || !validId(id) || !id.equals(m.id()) || !owner.toString().equals(m.owner()) || m.dimension() == null || m.dimension().isBlank()
                || m.dimension().length() > 256 || m.createdAt() <= 0 || !hash(m.planSha256()) || !hash(m.patchesSha256())
                || m.producerVersion() == null || m.producerVersion().isBlank() || m.producerVersion().length() > 256
                || m.patchCount() <= 0 || m.patchCount() > MAX_PATCHES || m.dependencies().size() > 256
                || m.shapes().size() > 1024 || m.uses().size() > 65536
                || (m.revision() == null
                    ? m.dependencies().isEmpty() || m.shapes().isEmpty() || m.uses().isEmpty()
                    : !validRevision(m.revision()) || !id.equals(revisionId(m.revision())) || id.equals(m.revision().parentId()))
                || m.communityReceipts() == null || m.communityReceipts().size() > 256)
            throw new IOException("建筑锁清单契约不一致");
        for (var dependency : m.dependencies().entrySet())
            if (!dependency.getKey().matches("[0-9a-f]{64}_[0-9a-f]{64}") || dependency.getValue() == null
                    || !hash(dependency.getValue().manifestSha256()) || dependency.getValue().contentRef() == null || dependency.getValue().contentRef().length() > 2048)
                throw new IOException("建筑依赖身份无效");
        for (var shape : m.shapes().entrySet()) {
            if (!hash(shape.getKey()) || shape.getValue() == null || shape.getValue().length() > ComponentIds.MAX_JSON_CHARS
                    || !shape.getKey().equals(BuildingAssetCapture.digest(shape.getValue()))) throw new IOException("适配构件指纹不一致");
            try {
                var definition = JsonUtil.fromJson(shape.getValue(),ComponentDefinition.class);
                if (!com.formacraft.common.component.validate.ComponentValidator.validate(definition).ok()) throw new IOException("适配构件无效");
            } catch (RuntimeException invalid) { throw new IOException("适配构件无效",invalid); }
        }
        for (var entry : m.communityReceipts().entrySet())
            if (!entry.getKey().matches("receipt_[0-9a-f]{64}") || !m.dependencies().containsKey(entry.getValue())) throw new IOException("建筑收据身份无效");
        var usedReceipts = new HashSet<String>();
        for (var use : m.uses()) {
            if (use == null || !ComponentIds.isSafeId(use.assetId()) || !m.dependencies().containsKey(use.snapshot()) || !m.shapes().containsKey(use.shape())
                    || !Set.of("NORTH","SOUTH","EAST","WEST").contains(use.fromFacing()) || !Set.of("NORTH","SOUTH","EAST","WEST").contains(use.facing())
                    || !Set.of("NONE","X","Z").contains(use.mirror()) || use.style() != null && use.style().length() > 256
                    || use.materialApplied() && !use.materialRequested()) throw new IOException("构件使用记录无效");
            if (use.receiptId() != null) {
                if (!use.snapshot().equals(m.communityReceipts().get(use.receiptId()))) throw new IOException("构件来源与收据不一致");
                usedReceipts.add(use.receiptId());
            }
        }
        if (!usedReceipts.equals(m.communityReceipts().keySet())) throw new IOException("建筑收据没有对应使用记录");
    }
    private static Map<String,ComponentPackageIO.Verified> sources(Path root,Manifest manifest) throws IOException {
        var sources = new LinkedHashMap<String,ComponentPackageIO.Verified>();
        long sourceBytes = 0;
        for (var entry : manifest.dependencies().entrySet()) {
            var source = ComponentAssetSnapshots.read(root,entry.getKey());
            if (!source.contentRef().equals(entry.getValue().contentRef()) || !source.manifestSha256().equals(entry.getValue().manifestSha256())) throw new IOException("建筑依赖快照不一致");
            sourceBytes += source.toBytes().length; if (sourceBytes > 32L*1024*1024) throw new IOException("建筑原包快照总量过大");
            sources.put(entry.getKey(),source);
        }
        return sources;
    }
    private static Map<String,CommunityDownloadCache.Cached> receipts(Path root, Manifest manifest, Map<String,ComponentPackageIO.Verified> sources) throws IOException {
        var result = new LinkedHashMap<String,CommunityDownloadCache.Cached>(); long rawBytes = 0, receiptBytes = 0; var rawObjects = new HashSet<String>();
        for (var entry : manifest.communityReceipts().entrySet()) {
            var receipt = CommunityDownloadCache.read(root.resolve("community_downloads"),entry.getKey(),ComponentPackageIO.currentEnvironment());
            ComponentSourceBinding.assertSource(receipt,sources.get(entry.getValue()));
            receiptBytes += receipt.details().descriptor().byteLength();
            if (rawObjects.add(receipt.details().descriptor().releaseRef().packageSha256())) rawBytes += receipt.details().descriptor().byteLength();
            if (rawBytes > 32L*1024*1024 || receiptBytes > CommunityDownloadCache.MAX_OBJECT_BYTES) throw new IOException("建筑原始下载对象或收据总量超限");
            result.put(entry.getKey(),receipt);
        }
        return result;
    }
    public static Loaded load(Path world,UUID owner,String id,String dimension) throws IOException {
        Path directory = directory(world,owner,id); var manifest = manifest(directory,owner,id);
        if (!Objects.equals(dimension,manifest.dimension())) throw new IOException("请在锁记录所属维度恢复预览");
        return loadValidated(vault(world,owner,false),directory,manifest);
    }
    static Path backupVault(Path world,UUID owner,boolean create) throws IOException { return vault(world,owner,create); }
    static Loaded backupRead(Path root,UUID owner,String id) throws IOException {
        if(!validId(id)) throw new IOException("建筑锁 ID 无效");
        Path directory=ComponentAssetSnapshots.child(ComponentAssetSnapshots.child(root,"locks",false),id,false);
        return loadValidated(root,directory,manifest(directory,owner,id));
    }
    private static Loaded loadValidated(Path root,Path directory,Manifest manifest) throws IOException {
        var sources = sources(root,manifest); receipts(root,manifest,sources);
        String plan = text(file(directory,"input-plan.json"),MAX_PLAN);
        if (!BuildingAssetCapture.digest(plan).equals(manifest.planSha256())) throw new IOException("建筑方案指纹不一致");
        Path patchFile = file(directory,"patches.json.gz");
        if (Files.size(patchFile) > MAX_PATCH_BYTES || !fileHash(patchFile).equals(manifest.patchesSha256())) throw new IOException("建筑 Patch 指纹不一致");
        var patches = new ArrayList<BlockPatch>();
        try (var stream = new LimitedInput(new GZIPInputStream(Files.newInputStream(patchFile)),MAX_PATCH_BYTES);
             var reader = new JsonReader(new InputStreamReader(stream,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
            reader.beginArray();
            while (reader.hasNext()) {
                if (patches.size() >= manifest.patchCount()) throw new IOException("建筑 Patch 数量超出声明");
                var patch = JsonUtil.get().getAdapter(BlockPatch.class).read(reader); validatePatch(patch); patches.add(patch);
            }
            reader.endArray(); if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("建筑 Patch 尾部有多余数据");
        } catch (RuntimeException invalid) { throw new IOException("建筑 Patch 无效",invalid); }
        if (patches.size() != manifest.patchCount()) throw new IOException("建筑 Patch 数量不一致");
        return new Loaded(manifest,plan,patches);
    }
    public static List<Manifest> list(Path world,UUID owner,int page) throws IOException {
        if (page < 0 || page > 10000) throw new IOException("页码无效");
        Path root;
        try { root = ComponentAssetSnapshots.child(vault(world,owner,false),"locks",false); }
        catch (NoSuchFileException missing) { return List.of(); }
        var entries = new ArrayList<Manifest>();
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                String id = child.getFileName().toString(); if (!validId(id)) continue;
                try { entries.add(manifest(ComponentAssetSnapshots.child(root,id,false),owner,id)); }
                catch (IOException invalid) { /* Corrupt or incomplete records are not offered. */ }
            }
        }
        return entries.stream().sorted(Comparator.comparingLong(Manifest::createdAt).reversed().thenComparing(Manifest::id)).skip((long)page*10).limit(10).toList();
    }
    public static RestoreResult restore(Path world,UUID owner,String id,String dimension) throws IOException {
        var locked = load(world,owner,id,dimension); var manifest = locked.manifest(); var sources = sources(vault(world,owner,false),manifest);
        var aliases = new LinkedHashMap<String,String>(); var failures = new ArrayList<String>();
        var useAliases = new LinkedHashMap<Integer,String>(); var selections = new LinkedHashMap<Integer,ComponentSourceBinding.Selection>();
        var receipts = receipts(vault(world,owner,false),manifest,sources);
        var context = new ComponentStorage.SaveContext(world,owner,false,false);
        var ordered = new ArrayList<>(sources.entrySet());
        ordered.sort(Comparator.<Map.Entry<String,ComponentPackageIO.Verified>>comparingInt(entry -> manifest.uses().stream()
                .anyMatch(use -> use.snapshot().equals(entry.getKey()) && use.receiptId() == null) ? 0 : 1).thenComparing(Map.Entry::getKey));
        for (var entry : ordered) {
            try {
                var source = entry.getValue(); String actualId = null;
                var bound = new HashMap<String,ComponentSourceBinding.Selection>();
                for (int i = 0; i < manifest.uses().size(); i++) {
                    var use = manifest.uses().get(i);
                    if (!use.snapshot().equals(entry.getKey()) || use.receiptId() == null) continue;
                    var selected = bound.get(use.receiptId());
                    if (selected == null) { selected = ComponentSourceBinding.install(receipts.get(use.receiptId()),context); bound.put(use.receiptId(),selected); }
                    selections.put(i,selected);
                }
                boolean ordinary = manifest.uses().stream().anyMatch(use -> use.snapshot().equals(entry.getKey()) && use.receiptId() == null);
                if (!ordinary) continue;
                for (var visible : ComponentStorage.loadCatalog(world,owner).components) {
                    if (visible.recipe || !source.contentRef().equals(visible.packageRef)) continue;
                    if (ComponentLibraryOperations.export(world,owner,visible.id,visible.packageRef,0,visible.packageRef).manifestSha256().equals(source.manifestSha256())) { actualId = visible.id; break; }
                }
                if (actualId == null) actualId = ComponentPackageInstallations.importVerified(source,context).assetId();
                for (int i = 0; i < manifest.uses().size(); i++) {
                    var use = manifest.uses().get(i);
                    if (use.snapshot().equals(entry.getKey()) && use.receiptId() == null) { aliases.put(use.assetId(),actualId); useAliases.put(i,actualId); }
                }
            } catch (IOException | RuntimeException failed) {
                failures.add(entry.getKey()); com.formacraft.FormacraftMod.LOGGER.warn("Building asset dependency restore failed key={}",entry.getKey(),failed);
            }
        }
        for (var use : manifest.uses()) if (use.receiptId() != null) aliases.remove(use.assetId());
        return new RestoreResult(aliases,failures,useAliases,selections);
    }
    /**
     * Merge committed writes expressed relative to the actual build origin into the parent snapshot.
     * The resulting snapshot can be replayed at a new origin.
     */
    static List<BlockPatch> mergePatches(List<BlockPatch> parent, List<BlockPatch> committed) throws IOException {
        if (parent.size() > MAX_PATCHES || committed.size() > MAX_PATCHES) {
            throw new IOException("建筑 Patch 数量超出限制");
        }
        record Cell(int x, int y, int z) {}
        Map<Cell, BlockPatch> writes = new LinkedHashMap<>();
        for (BlockPatch patch : parent) {
            validatePatch(patch);
            writes.put(new Cell(patch.dx(), patch.dy(), patch.dz()), patch);
        }
        for (BlockPatch patch : committed) {
            validatePatch(patch);
            Cell cell = new Cell(patch.dx(), patch.dy(), patch.dz());
            boolean remove = BlockPatch.REMOVE.equals(patch.action());
            writes.put(cell, new BlockPatch(remove ? BlockPatch.REMOVE : BlockPatch.PLACE,
                    cell.x(), cell.y(), cell.z(), remove ? null : patch.targetBlock()));
            if (writes.size() > MAX_PATCHES) {
                throw new IOException("建筑 Patch 数量超出限制");
            }
        }
        return List.copyOf(writes.values());
    }

    private static void validatePatch(BlockPatch patch) throws IOException {
        if (patch == null || patch.action() == null || !Set.of(BlockPatch.PLACE,BlockPatch.REPLACE,BlockPatch.REMOVE).contains(patch.action())
                || Math.abs((long)patch.dx()) > 30_000_000 || Math.abs((long)patch.dy()) > 30_000_000 || Math.abs((long)patch.dz()) > 30_000_000
                || !BlockPatch.REMOVE.equals(patch.action()) && (patch.targetBlock() == null || patch.targetBlock().isBlank() || patch.targetBlock().length() > 2048)) throw new IOException("建筑 Patch 内容超出限制");
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String text(Path path,int max) throws IOException {
        byte[] bytes; try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(max+1); }
        if (bytes.length > max) throw new IOException("建筑锁文件过大");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static String fileHash(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(file),digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void cleanup(Path directory) throws IOException {
        if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return;
        if (!directory.toRealPath().equals(directory.getParent().toRealPath().resolve(directory.getFileName()))) throw new IOException("建筑暂存目录重定向");
        for (String file : List.of("manifest.json","input-plan.json","patches.json.gz")) Files.deleteIfExists(directory.resolve(file));
        Files.delete(directory);
    }
    static final class LimitedInput extends FilterInputStream {
        private final long max; private long count;
        LimitedInput(InputStream input,long max) { super(input); this.max = max; }
        private void add(int size) throws IOException { if (size > 0 && (count += size) > max) throw new IOException("解压数据超出限制"); }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0) add(1); return value; }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException { int size = in.read(bytes,offset,length); add(size); return size; }
    }
    static final class LimitedOutput extends FilterOutputStream {
        private final long max; private long count;
        LimitedOutput(OutputStream output,long max) { super(output); this.max = max; }
        private void add(int size) throws IOException { if ((count += size) > max) throw new IOException("建筑锁数据超出限制"); }
        @Override public void write(int value) throws IOException { add(1); out.write(value); }
        @Override public void write(byte[] bytes,int offset,int length) throws IOException { add(length); out.write(bytes,offset,length); }
    }
}
