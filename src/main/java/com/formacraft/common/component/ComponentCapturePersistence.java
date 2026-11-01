package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import java.io.IOException;
import java.util.*;

/** Capture submissions create or reuse assets; incoming IDs never select an overwrite target. */
public final class ComponentCapturePersistence {
    private ComponentCapturePersistence() {}
    public record Saved(ComponentPackageInstallations.ImportResult imported, String displayName) {
        public boolean isNew() {
            return imported.status() == ComponentPackageInstallations.ImportStatus.NEW_ASSET
                    || imported.status() == ComponentPackageInstallations.ImportStatus.NEW_CONFIGURATION;
        }
        public String message(boolean global) {
            String action = switch (imported.status()) {
                case NEW_ASSET -> "已保存构件到";
                case NEW_CONFIGURATION -> "已保存新的使用配置到";
                case SOURCE_ADDED -> "已复用相同构件并补充来源至";
                case REUSED -> "已复用已有构件：";
            };
            return action + (imported.status() == ComponentPackageInstallations.ImportStatus.REUSED ? "" : (global ? "全局库：" : "私人库："))
                    + displayName;
        }
    }

    public static Saved save(ComponentDefinition input, UUID submittingPlayer, ComponentStorage.SaveContext context,
                             ComponentPackageIO.Environment environment, Map<String, String> namespaceOwners) throws IOException {
        if (context == null || submittingPlayer == null || !submittingPlayer.equals(context.playerId()))
            throw new IOException("Capture requires a trusted player save context");
        if (input == null || input.blocks == null || input.blocks.isEmpty() || input.blocks.size() > ComponentIds.MAX_BLOCKS)
            throw new IOException("Invalid capture block count");
        if (input.name == null || input.name.isBlank() || input.name.length() > 64)
            throw new IOException("Capture name must contain 1 to 64 characters");
        if (environment == null) throw new IOException("Runtime environment is required");
        String json = JsonUtil.toJson(input);
        if (json.length() > ComponentIds.MAX_JSON_CHARS) throw new IOException("Capture payload too large");
        var definition = JsonUtil.fromJson(json, ComponentDefinition.class);
        definition.id = "capture"; definition.name = definition.name.trim(); definition.archetypeRef = null;
        definition.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        String typeError = ComponentTypes.error(definition.type, definition.category);
        if (typeError != null) throw new IOException(typeError);
        definition.type = ComponentTypes.effective(definition.type, definition.category);
        com.formacraft.common.component.semantic.ComponentSemanticInference.ensureSemanticFields(definition);
        // Semantic inference supplies a legacy archetype reference; captures are self-contained.
        definition.archetypeRef = null;
        com.formacraft.common.component.autofix.ComponentAutoFix.apply(definition);

        var dependencies = new TreeMap<String, String>();
        var owners = new TreeMap<String, String>();
        for (var block : definition.blocks) {
            if (block == null || block.block == null) throw new IOException("Missing capture block state");
            String id = block.block.split("\\[", 2)[0].strip(); int colon = id.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
            if (namespace.equals("minecraft")) continue;
            String owner = namespaceOwners == null ? namespace : namespaceOwners.getOrDefault(namespace, namespace);
            String version = environment.installedMods().get(owner);
            if (version == null) throw new IOException("Cannot resolve block namespace owner: " + namespace);
            dependencies.put(owner, version);
            if (!namespace.equals(owner)) owners.put(namespace, owner);
        }
        var manifest = new ComponentPackageIO.Manifest("capture_" + submittingPlayer.toString().replace("-", ""), "1",
                submittingPlayer.toString(), "LicenseRef-Unspecified", ComponentPackageIO.TrainingPermission.UNSPECIFIED,
                environment.minecraftVersion(), dependencies, owners, List.of());
        var verified = ComponentPackageIO.create(definition, manifest, environment);
        synchronized (ComponentStorage.class) {
            var result = ComponentPackageInstallations.importVerified(verified, context);
            var root = context.writeGlobal() ? ComponentStorage.getGlobalComponentDir() : ComponentStorage.getPlayerComponentDir(context.playerId());
            var stored = ComponentPackageInstallations.inspect(root, result.assetId()).component();
            return new Saved(result, stored.name);
        }
    }
}
