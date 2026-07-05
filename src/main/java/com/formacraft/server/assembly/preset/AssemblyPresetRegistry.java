package com.formacraft.server.assembly.preset;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads parametric assembly presets from assets/formacraft/assembly_presets/*.json.
 */
public final class AssemblyPresetRegistry {

    private static final String MOD_ID = "formacraft";
    private static volatile List<AssemblyPresetDefinition> cached;

    private AssemblyPresetRegistry() {}

    public static List<AssemblyPresetDefinition> listPresets() {
        List<AssemblyPresetDefinition> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (AssemblyPresetRegistry.class) {
            if (cached == null) {
                cached = Collections.unmodifiableList(loadAll());
            }
            return cached;
        }
    }

    public static Optional<AssemblyPresetDefinition> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String key = id.trim();
        for (AssemblyPresetDefinition p : listPresets()) {
            if (key.equals(p.id())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public static Optional<AssemblyPresetDefinition> resolveForIntent(String userText) {
        if (userText == null || userText.isBlank()) {
            return Optional.empty();
        }
        String lower = userText.toLowerCase(Locale.ROOT);
        AssemblyPresetDefinition best = null;
        double bestScore = 0;
        for (AssemblyPresetDefinition preset : listPresets()) {
            double score = 0;
            for (String kw : preset.matchKeywords()) {
                if (kw != null && !kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    score += Math.max(2.0, kw.length() * 0.5);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = preset;
            }
        }
        return bestScore > 0.01 ? Optional.ofNullable(best) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<AssemblyPresetDefinition> loadAll() {
        List<AssemblyPresetDefinition> out = new ArrayList<>();
        Path dir = findAssetsDir("assembly_presets");
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> parseFile(p).ifPresent(out::add));
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("AssemblyPresetRegistry: failed listing presets dir: {}", e.getMessage());
            }
        }
        if (out.isEmpty()) {
            loadFromClasspath(out);
        }
        FormacraftMod.LOGGER.info("AssemblyPresetRegistry: loaded {} assembly preset(s)", out.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Optional<AssemblyPresetDefinition> parseFile(Path path) {
        try (var in = Files.newInputStream(path)) {
            Map<String, Object> root = JsonUtil.get().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() {}.getType()
            );
            return Optional.ofNullable(parseRoot(root));
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("AssemblyPresetRegistry: skip preset {}: {}", path.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadFromClasspath(List<AssemblyPresetDefinition> out) {
        ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
        if (mod == null) {
            return;
        }
        mod.findPath("assets/" + MOD_ID + "/assembly_presets").ifPresent(dir -> {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(p -> parseFile(p).ifPresent(out::add));
            } catch (Exception ignored) {
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static AssemblyPresetDefinition parseRoot(Map<String, Object> root) {
        if (root == null) {
            return null;
        }
        String id = str(root.get("id"));
        if (id.isBlank()) {
            return null;
        }
        Object assembly = root.get("assembly");
        if (!(assembly instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> assemblyMap = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) assembly).entrySet()) {
            if (e.getKey() != null) {
                assemblyMap.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        List<String> keywords = strList(root.get("matchKeywords"));
        Map<String, AssemblyPresetDefinition.ParamSpec> params = parseParams(root.get("parameters"));
        return new AssemblyPresetDefinition(
                id,
                str(root.get("displayName")),
                str(root.get("description")),
                keywords,
                assemblyMap,
                params
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AssemblyPresetDefinition.ParamSpec> parseParams(Object obj) {
        Map<String, AssemblyPresetDefinition.ParamSpec> out = new LinkedHashMap<>();
        if (!(obj instanceof Map<?, ?> pm)) {
            return out;
        }
        for (Map.Entry<?, ?> e : pm.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> spec)) {
                continue;
            }
            String name = String.valueOf(e.getKey());
            out.put(name, new AssemblyPresetDefinition.ParamSpec(
                    dbl(spec.get("default"), 0),
                    dbl(spec.get("min"), Double.NEGATIVE_INFINITY),
                    dbl(spec.get("max"), Double.POSITIVE_INFINITY),
                    str(spec.get("kind"))
            ));
        }
        return out;
    }

    private static Path findAssetsDir(String subdir) {
        Path dev = Path.of("src/main/resources/assets/formacraft", subdir);
        if (Files.isDirectory(dev)) {
            return dev;
        }
        ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
        if (mod != null) {
            return mod.findPath("assets/" + MOD_ID + "/" + subdir).orElse(null);
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static double dbl(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
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
        for (Object it : list) {
            if (it != null) {
                String s = String.valueOf(it).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
