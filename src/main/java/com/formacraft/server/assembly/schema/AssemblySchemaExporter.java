package com.formacraft.server.assembly.schema;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.server.assembly.preset.AssemblyPresetDefinition;
import com.formacraft.server.assembly.preset.AssemblyPresetRegistry;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime export of MetaAssembly AI schema (ops, graph components, ports, presets).
 * Snapshot is written to assets/formacraft/ai-assembly-schema.json and loaded at mod init.
 */
public final class AssemblySchemaExporter {

    public static final int SCHEMA_VERSION = 3;
    public static final String RESOURCE_PATH = "assets/formacraft/ai-assembly-schema.json";

    public record ExportedComponent(
            String type,
            String category,
            List<String> aliases,
            List<String> requiredParams,
            List<String> optionalParams,
            List<String> ports
    ) {}

    public record ExportedPreset(
            String id,
            String displayName,
            String description,
            List<String> matchKeywords,
            List<String> parameters
    ) {}

    public record SchemaSnapshot(
            int schemaVersion,
            String generatedAt,
            List<String> ops,
            List<ExportedComponent> components,
            List<ExportedPreset> presets,
            List<String> compatibilityRules
    ) {}

    private static volatile SchemaSnapshot snapshot;

    private AssemblySchemaExporter() {}

    public static void initialize() {
        SchemaSnapshot runtime = exportRuntime();
        SchemaSnapshot loaded = loadFromClasspath();
        if (loaded == null) {
            snapshot = runtime;
            FormacraftMod.LOGGER.warn("AssemblySchemaExporter: {} missing; using runtime export", RESOURCE_PATH);
        } else {
            snapshot = mergeSnapshots(loaded, runtime);
        }
        FormacraftMod.LOGGER.info(
                "AssemblySchemaExporter: schema v{} with {} components, {} presets, {} ops",
                snapshot.schemaVersion(),
                snapshot.components().size(),
                snapshot.presets().size(),
                snapshot.ops().size()
        );
    }

    private static SchemaSnapshot mergeSnapshots(SchemaSnapshot loaded, SchemaSnapshot runtime) {
        Map<String, ExportedComponent> byType = new LinkedHashMap<>();
        for (ExportedComponent c : loaded.components()) {
            byType.put(c.type(), c);
        }
        for (ExportedComponent c : runtime.components()) {
            byType.putIfAbsent(c.type(), c);
        }
        List<ExportedPreset> presets = loaded.presets().isEmpty() ? runtime.presets() : loaded.presets();
        List<String> ops = loaded.ops().isEmpty() ? runtime.ops() : loaded.ops();
        List<String> rules = loaded.compatibilityRules().isEmpty()
                ? runtime.compatibilityRules()
                : loaded.compatibilityRules();
        return new SchemaSnapshot(
                Math.max(loaded.schemaVersion(), runtime.schemaVersion()),
                loaded.generatedAt().isBlank() ? runtime.generatedAt() : loaded.generatedAt(),
                ops,
                List.copyOf(byType.values()),
                presets,
                rules
        );
    }

    public static SchemaSnapshot snapshot() {
        SchemaSnapshot local = snapshot;
        if (local != null) {
            return local;
        }
        synchronized (AssemblySchemaExporter.class) {
            if (snapshot == null) {
                initialize();
            }
            return snapshot;
        }
    }

    public static SchemaSnapshot exportRuntime() {
        List<ExportedComponent> components = new ArrayList<>();
        for (AssemblyComponentSchemaRegistry.ComponentSchemaDef def : AssemblyComponentSchemaRegistry.definitions()) {
            components.add(new ExportedComponent(
                    def.type(),
                    def.category(),
                    def.aliases(),
                    def.requiredParams(),
                    def.optionalParams(),
                    AssemblyComponentSchemaRegistry.resolvePorts(def)
            ));
        }

        List<ExportedPreset> presets = new ArrayList<>();
        for (AssemblyPresetDefinition preset : AssemblyPresetRegistry.listPresets()) {
            presets.add(new ExportedPreset(
                    preset.id(),
                    preset.displayName(),
                    preset.description(),
                    preset.matchKeywords(),
                    new ArrayList<>(preset.parameters().keySet())
            ));
        }

        return new SchemaSnapshot(
                SCHEMA_VERSION,
                Instant.now().toString(),
                AssemblyComponentSchemaRegistry.engineOps(),
                List.copyOf(components),
                List.copyOf(presets),
                AssemblyComponentSchemaRegistry.compatibilityRules()
        );
    }

    public static Map<String, Object> toMap(SchemaSnapshot snap) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", snap.schemaVersion());
        root.put("generatedAt", snap.generatedAt());
        root.put("ops", snap.ops());
        root.put("compatibilityRules", snap.compatibilityRules());

        List<Map<String, Object>> componentMaps = new ArrayList<>();
        for (ExportedComponent c : snap.components()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", c.type());
            m.put("category", c.category());
            m.put("aliases", c.aliases());
            m.put("requiredParams", c.requiredParams());
            m.put("optionalParams", c.optionalParams());
            m.put("ports", c.ports());
            componentMaps.add(m);
        }
        root.put("components", componentMaps);

        List<Map<String, Object>> presetMaps = new ArrayList<>();
        for (ExportedPreset p : snap.presets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("displayName", p.displayName());
            m.put("description", p.description());
            m.put("matchKeywords", p.matchKeywords());
            m.put("parameters", p.parameters());
            presetMaps.add(m);
        }
        root.put("presets", presetMaps);
        return root;
    }

    public static String toJson(SchemaSnapshot snap) {
        return JsonUtil.toJson(toMap(snap));
    }

    public static void writeToPath(Path path, SchemaSnapshot snap) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson(snap), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static SchemaSnapshot loadFromClasspath() {
        ModContainer mod = FabricLoader.getInstance().getModContainer(FormacraftMod.MOD_ID).orElse(null);
        if (mod == null) {
            return null;
        }
        return mod.findPath(RESOURCE_PATH).map(path -> {
            try (var in = Files.newInputStream(path)) {
                return parseRoot(JsonUtil.get().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType()
                ));
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("AssemblySchemaExporter: failed loading {}: {}", path, e.getMessage());
                return null;
            }
        }).orElseGet(() -> {
            try (var in = AssemblySchemaExporter.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
                if (in == null) {
                    return null;
                }
                return parseRoot(JsonUtil.get().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType()
                ));
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("AssemblySchemaExporter: classpath load failed: {}", e.getMessage());
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static SchemaSnapshot parseRoot(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return null;
        }
        int version = intVal(root.get("schemaVersion"), SCHEMA_VERSION);
        String generatedAt = str(root.get("generatedAt"));
        List<String> ops = strList(root.get("ops"));
        List<String> rules = strList(root.get("compatibilityRules"));

        List<ExportedComponent> components = new ArrayList<>();
        Object compObj = root.get("components");
        if (compObj instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) raw;
                components.add(new ExportedComponent(
                        str(m.get("type")),
                        str(m.get("category")),
                        strList(m.get("aliases")),
                        strList(m.get("requiredParams")),
                        strList(m.get("optionalParams")),
                        strList(m.get("ports"))
                ));
            }
        }

        List<ExportedPreset> presets = new ArrayList<>();
        Object presetObj = root.get("presets");
        if (presetObj instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) raw;
                presets.add(new ExportedPreset(
                        str(m.get("id")),
                        str(m.get("displayName")),
                        str(m.get("description")),
                        strList(m.get("matchKeywords")),
                        strList(m.get("parameters"))
                ));
            }
        }

        if (components.isEmpty()) {
            return null;
        }
        if (ops.isEmpty()) {
            ops = AssemblyComponentSchemaRegistry.engineOps();
        }
        if (rules.isEmpty()) {
            rules = AssemblyComponentSchemaRegistry.compatibilityRules();
        }
        return new SchemaSnapshot(version, generatedAt, ops, List.copyOf(components), List.copyOf(presets), rules);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
