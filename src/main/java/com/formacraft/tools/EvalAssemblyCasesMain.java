package com.formacraft.tools;

import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.macro.AssemblyMacroApplier;
import com.formacraft.server.assembly.macro.AssemblyMacroApplyResult;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import com.formacraft.server.rag.CultureCardRepository;
import com.formacraft.server.rag.KeywordCultureRetriever;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * P0 build-time evaluation runner for "prompt -> assembly" (without calling LLM).
 *
 * Case schema (P0):
 * {
 *   "id": "case_id",
 *   "prompt": "...",
 *   "assembly": { ... extra.assembly object ... },
 *   "expect": {
 *     "mustOps": ["OPENINGS", "FACADE_GRID"],
 *     "maxOps": 500
 *   }
 * }
 *
 * Pipeline:
 *  - Normalizer -> MacroApplier -> Validator -> Compiler
 *  - Metrics: opCount, opType histogram, errors/warnings
 */
public final class EvalAssemblyCasesMain {
    private static final Gson GSON = new GsonBuilder().create();

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("src/main/resources/assets/formacraft/eval_cases");
        if (args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()) {
            dir = Path.of(args[0]);
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("[evalAssemblyCases] directory not found: " + dir.toAbsolutePath());
            System.exit(2);
            return;
        }

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
             .sorted(Comparator.comparing(p -> p.getFileName().toString()))
             .forEach(files::add);
        }
        if (files.isEmpty()) {
            System.err.println("[evalAssemblyCases] no .json files found under: " + dir.toAbsolutePath());
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
                System.err.println("[evalAssemblyCases] FAIL " + p.getFileName() + " : invalid JSON : " + e.getMessage());
                continue;
            }

            if (!(json instanceof Map<?, ?> root)) {
                bad++;
                System.err.println("[evalAssemblyCases] FAIL " + p.getFileName() + " : root must be an object (map)");
                continue;
            }

            String id = str(root.get("id"), p.getFileName().toString());
            Object assembly = root.get("assembly");
            if (!(assembly instanceof Map<?, ?>)) {
                bad++;
                System.err.println("[evalAssemblyCases] FAIL " + p.getFileName() + " : missing assembly object");
                continue;
            }

            Object expectObj = root.get("expect");
            Map<?, ?> expect = (expectObj instanceof Map<?, ?> em) ? em : Map.of();
            Set<String> mustOps = readStringSet(expect.get("mustOps"));
            Set<String> mustNotOps = readStringSet(expect.get("mustNotOps"));
            Integer maxOps = intOrNull(expect.get("maxOps"));
            String expectedCultureCardId = str(expect.get("expectedCultureCardId"), null);
            Map<?, ?> mustMacro = (expect.get("mustMacro") instanceof Map<?, ?> mm) ? mm : null;
            // mustOpKinds: {"OPENINGS": ["ROSE_WINDOW", "ARCH_WINDOW"]} - verify op has at least one matching kind
            Map<?, ?> mustOpKinds = (expect.get("mustOpKinds") instanceof Map<?, ?> mok) ? mok : null;
            
            // RAG validation: if case has prompt, verify retrieval hits expected culture card
            String prompt = str(root.get("prompt"), null);
            KeywordCultureRetriever.RetrievalResult ragResult = null;
            if (prompt != null && !prompt.isBlank()) {
                try {
                    CultureCardRepository repo = CultureCardRepository.load();
                    KeywordCultureRetriever retriever = new KeywordCultureRetriever(repo);
                    Object ragBudgetObj = root.get("ragBudget");
                    int topK = 3, fewShotK = 3;
                    if (ragBudgetObj instanceof Map<?, ?> rb) {
                        topK = intOrNull(rb.get("topK")) != null ? intOrNull(rb.get("topK")) : topK;
                        fewShotK = intOrNull(rb.get("fewShotK")) != null ? intOrNull(rb.get("fewShotK")) : fewShotK;
                    }
                    ragResult = retriever.retrieve(prompt, topK, fewShotK);
                    
                    if (expectedCultureCardId != null && !expectedCultureCardId.isBlank()) {
                        String actualCardId = null;
                        if (ragResult != null && !ragResult.hits().isEmpty()) {
                            actualCardId = ragResult.hits().get(0).card().id();
                        }
                        if (!expectedCultureCardId.equals(actualCardId)) {
                            System.err.println("[evalAssemblyCases] ERROR " + id + " : RAG expected cultureCardId=" + expectedCultureCardId + " but got=" + actualCardId);
                            bad++;
                            continue;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[evalAssemblyCases] WARN " + id + " : RAG retrieval failed: " + e.getMessage());
                }
            }

            AssemblySpecNormalizeResult norm = AssemblySpecNormalizer.normalize(assembly);
            AssemblyMacroApplyResult macro = AssemblyMacroApplier.apply(norm.normalized());
            Object applied = macro.applied();

            List<AssemblyValidationIssue> issues = AssemblySpecValidator.validate(applied);
            long err = issues.stream().filter(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR).count();
            if (err > 0) {
                bad++;
                System.err.println("[evalAssemblyCases] FAIL " + id + " (" + p.getFileName() + ") : " + err + " validation errors");
                int shown = 0;
                for (AssemblyValidationIssue is : issues) {
                    if (is.severity() != AssemblyValidationIssue.Severity.ERROR) continue;
                    if (shown++ >= 10) break;
                    System.err.println("  - " + is.path() + " [" + is.code() + "] : " + is.message());
                }
                continue;
            }

            AssemblySpec spec = MetaAssemblyCompiler.compile(applied);
            if (spec == null || spec.ops == null) {
                bad++;
                System.err.println("[evalAssemblyCases] FAIL " + id + " (" + p.getFileName() + ") : compile returned null");
                continue;
            }

            int opCount = spec.ops.size();
            Map<String, Integer> hist = new TreeMap<>();
            Map<String, Set<String>> opKinds = new TreeMap<>(); // opName -> set of kinds found
            for (var op : spec.ops) {
                if (op == null) continue;
                String opName = str(op.get("op"), null);
                if (opName == null) opName = str(op.get("type"), null);
                if (opName == null) continue;
                opName = opName.trim().toUpperCase(Locale.ROOT);
                hist.put(opName, hist.getOrDefault(opName, 0) + 1);
                
                // Track kinds for ops that have them (e.g., OPENINGS.kind)
                String kind = str(op.get("kind"), null);
                if (kind != null && !kind.isBlank()) {
                    opKinds.computeIfAbsent(opName, k -> new HashSet<>()).add(kind.trim().toUpperCase(Locale.ROOT));
                }
            }

            boolean fail = false;
            for (String need : mustOps) {
                if (!hist.containsKey(need.toUpperCase(Locale.ROOT))) {
                    System.err.println("[evalAssemblyCases] ERROR " + id + " : missing required op: " + need);
                    fail = true;
                }
            }
            for (String forbid : mustNotOps) {
                if (hist.containsKey(forbid.toUpperCase(Locale.ROOT))) {
                    System.err.println("[evalAssemblyCases] ERROR " + id + " : forbidden op present: " + forbid);
                    fail = true;
                }
            }
            
            // Verify mustOpKinds: e.g., {"OPENINGS": ["ROSE_WINDOW"]} means at least one OPENINGS op must have kind=ROSE_WINDOW
            if (mustOpKinds != null) {
                for (Map.Entry<?, ?> entry : mustOpKinds.entrySet()) {
                    String opName = String.valueOf(entry.getKey()).trim().toUpperCase(Locale.ROOT);
                    Object kindsObj = entry.getValue();
                    Set<String> requiredKinds = readStringSet(kindsObj);
                    if (requiredKinds.isEmpty()) continue;
                    
                    Set<String> foundKinds = opKinds.getOrDefault(opName, Set.of());
                    boolean hasMatch = false;
                    for (String reqKind : requiredKinds) {
                        String reqKindUpper = reqKind.trim().toUpperCase(Locale.ROOT);
                        if (foundKinds.contains(reqKindUpper)) {
                            hasMatch = true;
                            break;
                        }
                    }
                    if (!hasMatch) {
                        System.err.println("[evalAssemblyCases] ERROR " + id + " : op " + opName + " must have kind in " + requiredKinds + " but found " + foundKinds);
                        fail = true;
                    }
                }
            }
            if (maxOps != null && opCount > maxOps) {
                System.err.println("[evalAssemblyCases] ERROR " + id + " : opCount " + opCount + " exceeds maxOps " + maxOps);
                fail = true;
            }
            
            // Verify mustMacro constraints (e.g., macro.style.styleId must match)
            if (mustMacro != null && applied instanceof Map<?, ?> appMap) {
                Object macroObj = appMap.get("macro");
                if (macroObj instanceof Map<?, ?> macroMap) {
                    for (Map.Entry<?, ?> entry : mustMacro.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        Object expectedValue = entry.getValue();
                        Object actualValue = getNestedValue(macroMap, key);
                        if (actualValue == null) {
                            System.err.println("[evalAssemblyCases] ERROR " + id + " : mustMacro." + key + " expected=" + expectedValue + " but got=null");
                            fail = true;
                        } else {
                            // Compare as numbers if both are numbers, otherwise as strings
                            boolean match = false;
                            if (expectedValue instanceof Number && actualValue instanceof Number) {
                                double exp = ((Number) expectedValue).doubleValue();
                                double act = ((Number) actualValue).doubleValue();
                                match = Math.abs(exp - act) < 0.001; // tolerance for floating point
                            } else {
                                match = String.valueOf(actualValue).equals(String.valueOf(expectedValue));
                            }
                            if (!match) {
                                System.err.println("[evalAssemblyCases] ERROR " + id + " : mustMacro." + key + " expected=" + expectedValue + " but got=" + actualValue);
                                fail = true;
                            }
                        }
                    }
                }
            }

            if (fail) {
                bad++;
                System.err.println("[evalAssemblyCases] FAIL " + id + " (" + p.getFileName() + ") : expectation failed");
                System.err.println("  opCount=" + opCount + " ops=" + hist);
            } else {
                System.out.println("[evalAssemblyCases] OK " + id + " : opCount=" + opCount + " ops=" + hist);
            }
        }

        if (bad > 0) {
            System.err.println("[evalAssemblyCases] summary: " + bad + " failing cases");
            System.exit(1);
            return;
        }
        System.out.println("[evalAssemblyCases] OK (" + files.size() + " cases)");
    }

    private static Set<String> readStringSet(Object v) {
        if (!(v instanceof List<?> list)) return Set.of();
        Set<String> out = new HashSet<>();
        for (Object it : list) {
            if (it instanceof String s && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static Integer intOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return null;
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }
    
    private static Object getNestedValue(Map<?, ?> map, String path) {
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(part);
            if (current == null) return null;
        }
        return current;
    }
}


