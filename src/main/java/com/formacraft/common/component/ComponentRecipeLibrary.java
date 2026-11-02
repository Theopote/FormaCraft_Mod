package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.variant.SegmentScaler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Private deterministic recipes and immutable results, independent of the live source asset. */
public final class ComponentRecipeLibrary {
    private ComponentRecipeLibrary() {}
    public static final String ENGINE = "formacraft.segment-recipe.v1";
    public record Spec(String name, int x, int y, int z) {}
    public record Recipe(String schema, String engine, String identity, String snapshot, String creator,
                         Spec spec, String resultRef, String resultManifest) {}
    public record Saved(String assetId, String name, ComponentPackageInstallations.ImportStatus status) {}
    private record Loaded(Recipe recipe, ComponentPackageIO.Verified result, ComponentPackageIO.Verified source) {}
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
    private static String identity(String snapshot, Spec spec) {
        try {
            String input = ENGINE+"\n"+snapshot+"\n"+spec.x()+","+spec.y()+","+spec.z();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Saved save(Path world, UUID player, String assetId, String expectedRef, Spec spec) throws IOException {
        validate(spec);
        synchronized (ComponentStorage.class) {
            var source = ComponentLibraryOperations.export(world,player,assetId,expectedRef,0,expectedRef);
            var result = compile(source,spec);
            Path library = personal(player,true); String snapshot = ComponentAssetSnapshots.save(library,source);
            String identity = identity(snapshot,spec), id = "recipe_"+identity.substring(0,48);
            Path recipes = ComponentAssetSnapshots.child(library,"recipes",true);
            if (Files.exists(recipes.resolve(id),LinkOption.NOFOLLOW_LINKS)) {
                var existing = read(player,id);
                if (!identity.equals(existing.recipe().identity())) throw new IOException("Recipe identity collision");
                return new Saved(id,existing.recipe().spec().name(),ComponentPackageInstallations.ImportStatus.REUSED);
            }
            long count;
            try (var children = Files.list(recipes)) { count = children.filter(p -> isRecipe(p.getFileName().toString())).count(); }
            if (count >= 256) throw new IOException("私人配方已达 256 项，请整理后重试");
            var recipe = new Recipe("formacraft.component.recipe.v1",ENGINE,identity,snapshot,player.toString(),spec,result.contentRef(),result.manifestSha256());
            Path staging = Files.createTempDirectory(recipes,".pending-");
            try {
                result.writeTo(staging.resolve("result.fccomponent.json"));
                Files.writeString(staging.resolve("recipe.json"),JsonUtil.toJson(recipe),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                Files.move(staging,recipes.resolve(id),StandardCopyOption.ATOMIC_MOVE);
            } finally { cleanup(staging); }
            return new Saved(id,spec.name(),ComponentPackageInstallations.ImportStatus.NEW_ASSET);
        }
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
            try { recipe = JsonUtil.fromJson(new String(bytes,StandardCharsets.UTF_8),Recipe.class); }
            catch (RuntimeException invalid) { throw new IOException("Invalid recipe document",invalid); }
        }
        if (recipe == null || !"formacraft.component.recipe.v1".equals(recipe.schema()) || !ENGINE.equals(recipe.engine())
                || !player.toString().equals(recipe.creator())) throw new IOException("Invalid recipe contract");
        validate(recipe.spec());
        if (!identity(recipe.snapshot(),recipe.spec()).equals(recipe.identity()) || !id.equals("recipe_"+recipe.identity().substring(0,48)))
            throw new IOException("Recipe identity mismatch");
        var source = ComponentAssetSnapshots.read(library,recipe.snapshot());
        var result = ComponentPackageIO.read(resultFile,ComponentPackageIO.currentEnvironment());
        if (!result.contentRef().equals(recipe.resultRef()) || !result.manifestSha256().equals(recipe.resultManifest())
                || !result.manifest().derivedFrom().contains(source.contentRef()+"|manifest:"+source.manifestSha256())) throw new IOException("Recipe result mismatch");
        return new Loaded(recipe,result,source);
    }
    public static ComponentPackageIO.Verified result(UUID player, String id) throws IOException { return read(player,id).result(); }
    public static void replay(UUID player, String id) throws IOException {
        var loaded = read(player,id); var generated = compile(loaded.source(),loaded.recipe().spec());
        if (!generated.contentRef().equals(loaded.result().contentRef()) || !generated.manifestSha256().equals(loaded.result().manifestSha256()))
            throw new IOException("配方重放与已锁定结果不同，请保留原结果并检查编译器版本");
    }
    public static Saved restore(ComponentStorage.SaveContext context, String id) throws IOException {
        if (context == null || context.playerId() == null) throw new IOException("Missing recipe owner");
        var source = read(context.playerId(),id).source();
        var restored = ComponentPackageInstallations.importVerified(source,context);
        return new Saved(restored.assetId(),ComponentSourceView.bounded(source.component().name,128),restored.status());
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
