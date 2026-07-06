package com.formacraft.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-time gate: freeze {@link com.formacraft.server.generation.structure.router.StructureGeneratorRegistry}
 * unless an explicit ADR / typology exemption is present on the register line.
 */
public final class ValidateGeneratorRegistryMain {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Pattern REGISTER = Pattern.compile("register\\s*\\(\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        Path registryJava = Path.of(
                "src/main/java/com/formacraft/server/generation/structure/router/StructureGeneratorRegistry.java");
        Path allowlistJson = Path.of(
                "src/main/resources/assets/formacraft/ci/generator_registry_allowlist.json");
        if (args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()) {
            registryJava = Path.of(args[0]);
        }
        if (args != null && args.length >= 2 && args[1] != null && !args[1].isBlank()) {
            allowlistJson = Path.of(args[1]);
        }

        if (!Files.isRegularFile(registryJava)) {
            System.err.println("[validateGeneratorRegistry] registry source not found: " + registryJava.toAbsolutePath());
            System.exit(2);
            return;
        }
        if (!Files.isRegularFile(allowlistJson)) {
            System.err.println("[validateGeneratorRegistry] allowlist not found: " + allowlistJson.toAbsolutePath());
            System.exit(2);
            return;
        }

        Set<String> allowed = loadAllowlist(allowlistJson);
        if (allowed.isEmpty()) {
            System.err.println("[validateGeneratorRegistry] allowlist has no allowedKeys");
            System.exit(2);
            return;
        }

        List<String> lines = Files.readAllLines(registryJava, StandardCharsets.UTF_8);
        Set<String> found = new HashSet<>();
        int bad = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = REGISTER.matcher(line);
            if (!m.find()) {
                continue;
            }
            String key = m.group(1).trim().toLowerCase(Locale.ROOT);
            found.add(key);
            String prev = i > 0 ? lines.get(i - 1) : "";

            if (allowed.contains(key)) {
                continue;
            }
            if (hasRegisterExemption(line, prev)) {
                System.out.println("[validateGeneratorRegistry] WARN new key with exemption (update allowlist): " + key);
                continue;
            }
            bad++;
            System.err.println("[validateGeneratorRegistry] FAIL unapproved register key '" + key
                    + "' at " + registryJava.getFileName() + ":" + (i + 1)
                    + " — add to generator_registry_allowlist.json or // ADR: comment");
        }

        for (String key : allowed) {
            if (!found.contains(key)) {
                bad++;
                System.err.println("[validateGeneratorRegistry] FAIL allowlist key missing from registry: " + key);
            }
        }

        if (bad > 0) {
            System.err.println("[validateGeneratorRegistry] summary: " + bad + " errors");
            System.exit(1);
            return;
        }
        System.out.println("[validateGeneratorRegistry] OK (" + found.size() + " keys, allowlist " + allowed.size() + ")");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadAllowlist(Path allowlistJson) throws Exception {
        String text = Files.readString(allowlistJson, StandardCharsets.UTF_8);
        Object root = GSON.fromJson(text, Object.class);
        if (!(root instanceof Map<?, ?> mm)) {
            return Set.of();
        }
        Object keysObj = mm.get("allowedKeys");
        if (!(keysObj instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (Object it : list) {
            if (it instanceof String s && !s.trim().isEmpty()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean hasRegisterExemption(String line, String prevLine) {
        String combined = (prevLine == null ? "" : prevLine) + "\n" + line;
        String low = combined.toLowerCase(Locale.ROOT);
        if (low.contains("adr:") || low.contains("// adr") || low.contains("@adr")) {
            return true;
        }
        return line.contains("TypologyBackedStructureGenerator");
    }
}
