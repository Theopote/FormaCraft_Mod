package com.formacraft.tools;

import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Build-time validator for assembly examples.
 *
 * Intended usage from Gradle:
 *  - validate all JSON files under src/main/resources/assets/formacraft/assembly_examples
 */
public final class ValidateAssemblyExamplesMain {
    private static final Gson GSON = new GsonBuilder().create();

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("src/main/resources/assets/formacraft/assembly_examples");
        if (args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()) {
            dir = Path.of(args[0]);
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("[validateAssemblyExamples] directory not found: " + dir.toAbsolutePath());
            System.exit(2);
            return;
        }

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
             .sorted(Comparator.comparing(p -> p.getFileName().toString()))
             .forEach(files::add);
        }
        if (files.isEmpty()) {
            System.err.println("[validateAssemblyExamples] no .json files found under: " + dir.toAbsolutePath());
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
                System.err.println("[validateAssemblyExamples] FAIL " + p.getFileName() + " : invalid JSON : " + e.getMessage());
                continue;
            }

            List<AssemblyValidationIssue> issues = AssemblySpecValidator.validate(json);
            long err = issues.stream().filter(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR).count();
            if (err > 0) {
                bad++;
                System.err.println("[validateAssemblyExamples] FAIL " + p.getFileName() + " : " + err + " errors");
                int shown = 0;
                for (AssemblyValidationIssue is : issues) {
                    if (is.severity() != AssemblyValidationIssue.Severity.ERROR) continue;
                    if (shown++ >= 10) break;
                    System.err.println("  - " + is.path() + " : " + is.message());
                }
            }
        }

        if (bad > 0) {
            System.err.println("[validateAssemblyExamples] summary: " + bad + " failing files");
            System.exit(1);
            return;
        }
        System.out.println("[validateAssemblyExamples] OK (" + files.size() + " files)");
    }
}


