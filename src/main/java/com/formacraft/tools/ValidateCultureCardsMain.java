package com.formacraft.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Build-time validator for culture cards.
 *
 * Intended usage from Gradle:
 *  - validate all JSON files under src/main/resources/assets/formacraft/culture_cards
 *
 * This is a P0 schema validator: focuses on "stable shape" + references to existing assembly examples.
 */
public final class ValidateCultureCardsMain {
    private static final Gson GSON = new GsonBuilder().create();

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("src/main/resources/assets/formacraft/culture_cards");
        if (args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()) {
            dir = Path.of(args[0]);
        }
        Path examplesDir = Path.of("src/main/resources/assets/formacraft/assembly_examples");
        if (args != null && args.length >= 2 && args[1] != null && !args[1].isBlank()) {
            examplesDir = Path.of(args[1]);
        }

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("[validateCultureCards] directory not found: " + dir.toAbsolutePath());
            System.exit(2);
            return;
        }
        if (!Files.exists(examplesDir) || !Files.isDirectory(examplesDir)) {
            System.err.println("[validateCultureCards] examples directory not found: " + examplesDir.toAbsolutePath());
            System.exit(2);
            return;
        }

        Set<String> exampleNames = new HashSet<>();
        try (var s = Files.list(examplesDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
             .forEach(p -> exampleNames.add(p.getFileName().toString()));
        }

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
             .sorted(Comparator.comparing(p -> p.getFileName().toString()))
             .forEach(files::add);
        }
        if (files.isEmpty()) {
            System.err.println("[validateCultureCards] no .json files found under: " + dir.toAbsolutePath());
            System.exit(2);
            return;
        }

        int bad = 0;
        for (Path p : files) {
            Object json;
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                json = GSON.fromJson(text, Object.class);
            } catch (Exception e) {
                bad++;
                System.err.println("[validateCultureCards] FAIL " + p.getFileName() + " : invalid JSON : " + e.getMessage());
                continue;
            }

            if (!(json instanceof Map<?, ?> mm)) {
                bad++;
                System.err.println("[validateCultureCards] FAIL " + p.getFileName() + " : root must be an object (map)");
                continue;
            }

            int errs = 0;
            errs += requireString(mm, "id", p.getFileName().toString());
            errs += requireString(mm, "styleId", p.getFileName().toString());

            // soft validation
            String sid = s(mm.get("styleId"));
            if (sid != null) {
                String up = sid.toUpperCase(Locale.ROOT);
                boolean knownBucket = up.contains("GOTHIC")
                        || up.contains("INDUSTRIAL")
                        || up.contains("MODERN")
                        || up.contains("JAPANESE")
                        || up.contains("JIANGNAN");
                if (!knownBucket) {
                    System.out.println("[validateCultureCards] WARN " + p.getFileName() + " : styleId bucket unknown (ok for now): " + sid);
                }
            }

            errs += requireStringList(mm, "intents", p.getFileName().toString(), false);
            errs += requireStringList(mm, "keywords", p.getFileName().toString(), true);
            errs += requireList(mm, "archetypes", p.getFileName().toString(), true);
            errs += requireStringList(mm, "negativeKeywords", p.getFileName().toString(), true);
            errs += requireStringListMap(mm, "synonyms", p.getFileName().toString());
            errs += requireDoubleMap(mm, "keywordWeights", p.getFileName().toString());

            // exampleRefs: optional at root
            errs += validateExampleRefs(mm.get("exampleRefs"), exampleNames, p.getFileName().toString(), "$.exampleRefs");
            // archetypes[].exampleRef: optional
            Object archObj = mm.get("archetypes");
            if (archObj instanceof List<?> archs) {
                for (int i = 0; i < archs.size(); i++) {
                    Object it = archs.get(i);
                    if (!(it instanceof Map<?, ?> am)) continue;
                    errs += validateExampleRefs(am.get("exampleRef"), exampleNames, p.getFileName().toString(), "$.archetypes[" + i + "].exampleRef");
                }
            }

            if (errs > 0) {
                bad++;
                System.err.println("[validateCultureCards] FAIL " + p.getFileName() + " : " + errs + " errors");
            }
        }

        if (bad > 0) {
            System.err.println("[validateCultureCards] summary: " + bad + " failing files");
            System.exit(1);
            return;
        }
        System.out.println("[validateCultureCards] OK (" + files.size() + " files)");
    }

    private static int validateExampleRefs(Object v, Set<String> examples, String file, String path) {
        if (v == null) return 0;
        if (v instanceof String s) {
            if (!examples.contains(s)) {
                System.err.println("[validateCultureCards] ERROR " + file + " : " + path + " : missing example: " + s);
                return 1;
            }
            return 0;
        }
        if (v instanceof List<?> list) {
            int e = 0;
            for (int i = 0; i < list.size(); i++) {
                Object it = list.get(i);
                if (!(it instanceof String ss)) continue;
                if (!examples.contains(ss)) {
                    System.err.println("[validateCultureCards] ERROR " + file + " : " + path + "[" + i + "] : missing example: " + ss);
                    e++;
                }
            }
            return e;
        }
        System.out.println("[validateCultureCards] WARN " + file + " : " + path + " should be string or list of strings");
        return 0;
    }

    private static int requireString(Map<?, ?> m, String k, String file) {
        Object v = m.get(k);
        if (!(v instanceof String s) || s.trim().isEmpty()) {
            System.err.println("[validateCultureCards] ERROR " + file + " : missing/empty string: " + k);
            return 1;
        }
        return 0;
    }

    private static int requireList(Map<?, ?> m, String k, String file, boolean allowEmpty) {
        Object v = m.get(k);
        if (!(v instanceof List<?> list)) {
            System.err.println("[validateCultureCards] ERROR " + file + " : missing list: " + k);
            return 1;
        }
        if (!allowEmpty && list.isEmpty()) {
            System.err.println("[validateCultureCards] ERROR " + file + " : list must be non-empty: " + k);
            return 1;
        }
        return 0;
    }

    private static int requireStringList(Map<?, ?> m, String k, String file, boolean allowEmpty) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (!(v instanceof List<?> list)) {
            System.err.println("[validateCultureCards] ERROR " + file + " : " + k + " must be a list of strings");
            return 1;
        }
        if (!allowEmpty && list.isEmpty()) {
            System.err.println("[validateCultureCards] ERROR " + file + " : " + k + " must be non-empty");
            return 1;
        }
        int e = 0;
        for (int i = 0; i < list.size(); i++) {
            Object it = list.get(i);
            if (!(it instanceof String s) || s.trim().isEmpty()) {
                System.err.println("[validateCultureCards] ERROR " + file + " : " + k + "[" + i + "] must be non-empty string");
                e++;
            }
        }
        return e;
    }

    private static int requireStringListMap(Map<?, ?> m, String k, String file) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (!(v instanceof Map<?, ?> mm)) {
            System.err.println("[validateCultureCards] ERROR " + file + " : " + k + " must be a map<string, string[]>");
            return 1;
        }
        int e = 0;
        for (var ent : mm.entrySet()) {
            if (!(ent.getKey() instanceof String ks) || ks.trim().isEmpty()) {
                System.err.println("[validateCultureCards] ERROR " + file + " : " + k + " key must be non-empty string");
                e++;
                continue;
            }
            Object vv = ent.getValue();
            if (!(vv instanceof List<?> list)) {
                System.err.println("[validateCultureCards] ERROR " + file + " : " + k + "." + ks + " must be string[]");
                e++;
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                Object it = list.get(i);
                if (!(it instanceof String s) || s.trim().isEmpty()) {
                    System.err.println("[validateCultureCards] ERROR " + file + " : " + k + "." + ks + "[" + i + "] must be non-empty string");
                    e++;
                }
            }
        }
        return e;
    }

    private static int requireDoubleMap(Map<?, ?> m, String k, String file) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (!(v instanceof Map<?, ?> mm)) {
            System.err.println("[validateCultureCards] ERROR " + file + " : " + k + " must be a map<string, number>");
            return 1;
        }
        int e = 0;
        for (var ent : mm.entrySet()) {
            if (!(ent.getKey() instanceof String ks) || ks.trim().isEmpty()) {
                System.err.println("[validateCultureCards] ERROR " + file + " : " + k + " key must be non-empty string");
                e++;
                continue;
            }
            Object vv = ent.getValue();
            if (!(vv instanceof Number)) {
                try { Double.parseDouble(String.valueOf(vv).trim()); }
                catch (Exception ex) {
                    System.err.println("[validateCultureCards] ERROR " + file + " : " + k + "." + ks + " must be number");
                    e++;
                }
            }
        }
        return e;
    }

    private static String s(Object v) {
        if (v == null) return null;
        String ss = String.valueOf(v).trim();
        return ss.isEmpty() ? null : ss;
    }
}


