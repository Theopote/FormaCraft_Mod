package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.variant.SegmentScaler;
import com.formacraft.common.component.community.CommunityDownloadCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Private deterministic recipes and immutable results, independent of the live source asset. */
public final class ComponentRecipeLibrary {
    private ComponentRecipeLibrary() {}
    public static final String ENGINE = "formacraft.segment-recipe.v1";
    public record Spec(String name, int x, int y, int z) {}
    public record Recipe(String schema, String engine, String identity, String snapshot, String creator,
                         Spec spec, String resultRef, String resultManifest, String communityReceipt) {}
    public record Saved(String assetId, String name, ComponentPackageInstallations.ImportStatus status) {}
    private record Loaded(Recipe recipe, ComponentPackageIO.Verified result, ComponentPackageIO.Verified source, CommunityDownloadCache.Cached receipt) {}
    public static boolean isRecipe(String id) { return id != null && id.matches("recipe_[0-9a-f]{48}"); }

    private static Path personal(UUID player, boolean create) throws IOException {
        if (player == null) throw new IOException("Missing recipe owner");
        Path global = ComponentStorage.getGlobalComponentDir();
        if (create) Files.createDirectories(global);
        Path players = ComponentAssetSnapshots.child(global,"players",create);
        return ComponentAssetSnapshots.child(players,player.toString(),create);
    }
    private static void validate(Spec spec) throws IOException {
        if (spec == null || spec.name() == null || spec.name().isBlank() || spec.name().length() > 64
                || spec.name().codePoints().anyMatch(Character::isISOControl)) throw new IOException("配方名称无效");
        try { ComponentTypes.custom(spec.name()); } catch (IllegalArgumentException invalid) { throw new IOException("配方名称无效",invalid); }
        for (int size : new int[]{spec.x(),spec.y(),spec.z()})
            if (size < 1 || size > SegmentScaler.MAX_AXIS_SIZE) throw new IOException("配方目标尺寸超出范围");
    }
    private static String identity(String snapshot, Spec spec, String receipt) {
        try {
            String input = ENGINE+"\n"+snapshot+"\n"+spec.x()+","+spec.y()+","+spec.z();
            if (receipt != null) input += "\ncommunity:"+receipt;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Saved save(Path world, UUID player, String assetId, String expectedRef, Spec spec) throws IOException {
        validate(spec);
        synchronized (ComponentStorage.class) {
            var visible = ComponentLibraryOperations.export(world,player,assetId,expectedRef,0,expectedRef);
            var original = isRecipe(assetId) ? read(player,assetId) : null;
            return saveSource(player,original == null ? visible : original.source(),spec,original == null ? null : original.receipt());
        }
    }
    public static Saved saveSelected(Path world, UUID player, ComponentSourceBinding.Selection selected, Spec spec) throws IOException {
        validate(spec);
        synchronized (ComponentStorage.class) {
            selected.validate(world,player);
            return saveSource(player,selected.source(),spec,selected.receipt());
        }
    }
    private static Saved saveSource(UUID player, ComponentPackageIO.Verified source, Spec spec, CommunityDownloadCache.Cached receipt) throws IOException {
            if (receipt != null) ComponentSourceBinding.assertSource(receipt,source);
            var result = compile(source,spec);
            Path library = personal(player,true); String snapshot = ComponentAssetSnapshots.save(library,source);
            String receiptId = receipt == null ? null : receipt.receiptId();
            String identity = identity(snapshot,spec,receiptId), id = "recipe_"+identity.substring(0,48);
            Path recipes = ComponentAssetSnapshots.child(library,"recipes",true);
            if (Files.exists(recipes.resolve(id),LinkOption.NOFOLLOW_LINKS)) {
                var existing = read(player,id);
                if (!identity.equals(existing.recipe().identity())) throw new IOException("Recipe identity collision");
                return new Saved(id,existing.recipe().spec().name(),ComponentPackageInstallations.ImportStatus.REUSED);
            }
            long count;
            try (var children = Files.list(recipes)) { count = children.filter(p -> isRecipe(p.getFileName().toString())).count(); }
            if (count >= 256) throw new IOException("私人配方已达 256 项，请整理后重试");
            if (receipt != null) CommunityDownloadCache.pin(library.resolve("community_downloads"),receipt,ComponentPackageIO.currentEnvironment());
            var recipe = new Recipe("formacraft.component.recipe.v1",ENGINE,identity,snapshot,player.toString(),spec,result.contentRef(),result.manifestSha256(),receiptId);
            Path staging = Files.createTempDirectory(recipes,".pending-");
            try {
                result.writeTo(staging.resolve("result.fccomponent.json"));
                Files.writeString(staging.resolve("recipe.json"),JsonUtil.toJson(recipe),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                Files.move(staging,recipes.resolve(id),StandardCopyOption.ATOMIC_MOVE);
            } finally { cleanup(staging); }
            return new Saved(id,spec.name(),ComponentPackageInstallations.ImportStatus.NEW_ASSET);
    }
    private static ComponentPackageIO.Verified compile(ComponentPackageIO.Verified source, Spec spec) throws IOException {
        validate(spec); var base = source.component();
        var query = new ComponentQuery(); query.geometry = new ComponentQuery.Geometry();
        query.geometry.openingWidth = spec.x(); query.geometry.openingHeight = spec.y(); query.geometry.targetDepth = spec.z();
        query.geometry.tolerance = 0; query.geometry.scalable = true;
        var fitted = ComponentModelApi.fitToQuery(base,query,null);
        if (fitted == null) throw new IOException("目标尺寸不符合作者规则，无法收藏配方");
        var definition = JsonUtil.fromJson(JsonUtil.toJson(fitted),ComponentDefinition.class);
        definition.name = spec.name(); definition.archetypeRef = null;
        // A favorite is this exact fit, not a new authoring template with a different repeat period.
        if (definition.variantRules != null && definition.variantRules.scaling != null && definition.variantRules.scaling.axes != null) {
            int[] sizes = {spec.x(),spec.y(),spec.z()}; String[] axes = {"X","Y","Z"};
            for (int i = 0; i < 3; i++) {
                var rule = definition.variantRules.scaling.axes.get(axes[i]);
                if (rule != null) { rule.type = "FIXED"; rule.min = rule.max = sizes[i]; }
            }
        }
        var original = source.manifest(); var derived = new ArrayList<>(original.derivedFrom());
        String reference = source.contentRef()+"|manifest:"+source.manifestSha256();
        if (!derived.contains(reference)) derived.add(reference);
        var manifest = new ComponentPackageIO.Manifest(original.namespace(),original.version(),original.author(),original.license(),
                original.trainingPermission(),original.minecraftVersion(),original.requiredMods(),original.blockNamespaceOwners(),derived);
        return ComponentPackageIO.create(definition,manifest,ComponentPackageIO.currentEnvironment());
    }
    private static Loaded read(UUID player, String id) throws IOException {
        if (!isRecipe(id)) throw new IOException("Invalid recipe identity");
        Path library = personal(player,false), root = ComponentAssetSnapshots.child(library,"recipes",false);
        Path directory = ComponentAssetSnapshots.child(root,id,false);
        Path document = directory.resolve("recipe.json"), resultFile = directory.resolve("result.fccomponent.json");
        for (Path file : List.of(document,resultFile))
            if (!file.toRealPath().equals(directory.toRealPath().resolve(file.getFileName()))) throw new IOException("Recipe file redirects");
        Recipe recipe;
        try (var input = Files.newInputStream(document)) {
            byte[] bytes = input.readNBytes(16385); if (bytes.length > 16384) throw new IOException("Oversized recipe document");
            try { recipe = JsonUtil.fromJson(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(),Recipe.class); }
            catch (RuntimeException invalid) { throw new IOException("Invalid recipe document",invalid); }
        }
        if (recipe == null || !"formacraft.component.recipe.v1".equals(recipe.schema()) || !ENGINE.equals(recipe.engine())
                || !player.toString().equals(recipe.creator())) throw new IOException("Invalid recipe contract");
        validate(recipe.spec());
        if (!identity(recipe.snapshot(),recipe.spec(),recipe.communityReceipt()).equals(recipe.identity()) || !id.equals("recipe_"+recipe.identity().substring(0,48)))
            throw new IOException("Recipe identity mismatch");
        var source = ComponentAssetSnapshots.read(library,recipe.snapshot());
        CommunityDownloadCache.Cached receipt = null;
        if (recipe.communityReceipt() != null) {
            receipt = CommunityDownloadCache.read(library.resolve("community_downloads"),recipe.communityReceipt(),ComponentPackageIO.currentEnvironment());
            ComponentSourceBinding.assertSource(receipt,source);
        }
        var result = ComponentPackageIO.read(resultFile,ComponentPackageIO.currentEnvironment());
        if (!Objects.equals(result.component().name,recipe.spec().name()) || !result.contentRef().equals(recipe.resultRef()) || !result.manifestSha256().equals(recipe.resultManifest())
                || !result.manifest().derivedFrom().contains(source.contentRef()+"|manifest:"+source.manifestSha256())) throw new IOException("Recipe result mismatch");
        return new Loaded(recipe,result,source,receipt);
    }
    public static ComponentPackageIO.Verified result(UUID player, String id) throws IOException { return read(player,id).result(); }
    public static void replay(UUID player, String id) throws IOException {
        var loaded = read(player,id); var generated = compile(loaded.source(),loaded.recipe().spec());
        if (!generated.contentRef().equals(loaded.result().contentRef()) || !generated.manifestSha256().equals(loaded.result().manifestSha256()))
            throw new IOException("配方重放与已锁定结果不同，请保留原结果并检查编译器版本");
    }
    public static Saved restore(ComponentStorage.SaveContext context, String id) throws IOException {
        if (context == null || context.playerId() == null) throw new IOException("Missing recipe owner");
        var loaded = read(context.playerId(),id);
        if (loaded.receipt() != null) {
            throw new IOException("该配方需要保留社区来源绑定，当前普通恢复入口不支持");
        }
        var source = loaded.source();
        for (var entry : ComponentStorage.loadCatalog(context.worldDir(),context.playerId()).components) {
            if (entry.recipe || !source.contentRef().equals(entry.packageRef)) continue;
            var visible = ComponentLibraryOperations.export(context.worldDir(),context.playerId(),entry.id,entry.packageRef,0,entry.packageRef);
            if (visible.manifestSha256().equals(source.manifestSha256()))
                return new Saved(entry.id,ComponentSourceView.bounded(visible.component().name,128),ComponentPackageInstallations.ImportStatus.REUSED);
        }
        // A private backup restore never promotes previously private content merely because its owner became OP.
        var privateContext = new ComponentStorage.SaveContext(context.worldDir(),context.playerId(),false,false);
        var restored = ComponentPackageInstallations.importVerified(source,privateContext);
        return new Saved(restored.assetId(),ComponentSourceView.bounded(source.component().name,128),restored.status());
    }
    /** Explicit restoration API: callers retain this selection instead of treating the alias as a source. */
    public static ComponentSourceBinding.Selection restoreSelected(ComponentStorage.SaveContext context, String id) throws IOException {
        if (context == null || context.playerId() == null) throw new IOException("Missing recipe owner");
        var loaded = read(context.playerId(),id);
        if (loaded.receipt() == null) throw new IOException("配方没有社区来源收据");
        return ComponentSourceBinding.install(loaded.receipt(),context);
    }
    static CommunityDownloadCache.Cached communitySource(UUID player, String id) throws IOException { return read(player,id).receipt(); }
    /** Read-only preflight for explicit offline restore; the recipe ID resolves inside the real owner's vault. */
    public static CommunityDownloadCache.Cached selectedReceipt(UUID player,String id) throws IOException {
        if(player==null) throw new IOException("Missing recipe owner");
        var loaded=read(player,id);
        if(loaded.receipt()==null) throw new IOException("Recipe has no community source");
        return loaded.receipt();
    }
    public static List<ComponentCatalog.Entry> entries(UUID player) {
        var entries = new ArrayList<ComponentCatalog.Entry>(); if (player == null) return entries;
        try {
            Path root = ComponentAssetSnapshots.child(personal(player,false),"recipes",false);
            try (var children = Files.list(root)) {
                for (Path path : children.sorted().toList()) {
                    String id = path.getFileName().toString(); if (!isRecipe(id)) continue;
                    try {
                        var loaded = read(player,id); var definition = loaded.result().component();
                        var entry = new ComponentCatalog.Entry(); entry.id = id; entry.name = loaded.recipe().spec().name();
                        entry.category = definition.category; entry.type = ComponentTypes.effective(definition.type,definition.category);
                        entry.tags = definition.tags; entry.size = definition.size; entry.variantRules = definition.variantRules;
                        entry.sockets = definition.sockets; entry.placementSpec = definition.placementSpec;
                        entry.packageRef = loaded.result().contentRef(); entry.file = "recipes/"+id+"/result.fccomponent.json";
                        entry.recipe = true; entry.sourceCount = 1; entry.updatedAtMs = Files.getLastModifiedTime(path).toMillis();
                        entry.communityRecipe = loaded.recipe().communityReceipt()!=null;
                        entry.recipeSourceRef = loaded.source().contentRef()+"|manifest:"+loaded.source().manifestSha256();
                        entries.add(entry);
                    } catch (IOException | RuntimeException invalid) { /* Invalid recipes are never candidates. */ }
                }
            }
        } catch (IOException missing) { /* No private favorites yet. */ }
        return entries;
    }
    public static void delete(UUID player, String id, String expectedRef) throws IOException {
        synchronized (ComponentStorage.class) {
            var loaded = read(player,id); if (!loaded.result().contentRef().equals(expectedRef)) throw new IOException("Recipe changed");
            Path root = ComponentAssetSnapshots.child(personal(player,false),"recipes",false), directory = ComponentAssetSnapshots.child(root,id,false);
            Path retired = root.resolve(".removed-"+UUID.randomUUID()); Files.move(directory,retired,StandardCopyOption.ATOMIC_MOVE); cleanup(retired);
        }
    }
    private static void cleanup(Path directory) throws IOException {
        if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return;
        if (!directory.toRealPath().equals(directory.getParent().toRealPath().resolve(directory.getFileName()))) throw new IOException("Recipe staging redirects");
        Files.deleteIfExists(directory.resolve("recipe.json")); Files.deleteIfExists(directory.resolve("result.fccomponent.json")); Files.delete(directory);
    }
}
