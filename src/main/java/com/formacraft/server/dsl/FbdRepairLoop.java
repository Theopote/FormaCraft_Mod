package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Pre-preview, world-free Java validation. No repair response is executable without revalidation. */
public final class FbdRepairLoop {
    public static final int MAX_ROUNDS = 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REPAIRABLE = Set.of("E_FBD_UNKNOWN_FUNCTION", "E_FBD_INVALID_ENUM",
            "E_FBD_UNKNOWN_ROLE", "E_FBD_TYPE_MISMATCH");
    public record Patch(String path, JsonNode value) {}
    public record Result(LlmPlan plan, int attempts) {}
    @FunctionalInterface public interface Exchange {
        CompletableFuture<Patch> propose(LlmPlan plan, CapabilityGap diagnostic, int round);
    }
    private FbdRepairLoop() {}

    public static boolean hasProgram(LlmPlan plan) {
        return plan != null && plan.components() != null && plan.components().stream().anyMatch(c ->
                c != null && c.params() != null && c.params().containsKey("fbd_program"));
    }

    public static CapabilityGap inspect(LlmPlan plan) {
        if (!hasProgram(plan) || plan.hasCapabilityGap()) return null;
        var previous = AssemblyCompileDiagnostics.get();
        try {
            AssemblyCompileDiagnostics.set(null);
            FbdPlanLowering.lower(plan.components());
            return AssemblyCompileDiagnostics.get();
        } finally {
            AssemblyCompileDiagnostics.set(previous);
        }
    }

    public static CompletableFuture<Result> run(LlmPlan plan, Exchange exchange, Consumer<String> progress) {
        return step(plan, exchange, progress, 0, null);
    }

    private static CompletableFuture<Result> step(LlmPlan plan, Exchange exchange, Consumer<String> progress,
                                                   int attempts, String lastRejection) {
        var gap = inspect(plan);
        if (gap == null) return CompletableFuture.completedFuture(new Result(plan, attempts));
        if (!REPAIRABLE.contains(gap.code()) || attempts == MAX_ROUNDS)
            return CompletableFuture.completedFuture(new Result(failure(plan, gap, attempts, lastRejection), attempts));
        int round = attempts + 1;
        progress.accept("Java 校验未通过，正在修订程序（" + round + "/" + MAX_ROUNDS + "）");
        var feedback = gap;
        if (lastRejection != null) feedback = new CapabilityGap(gap.code(), gap.message(), gap.path(),
                List.of("Previous patch rejected: " + lastRejection), gap.details());
        CompletableFuture<Patch> response;
        try { response = exchange.propose(plan, feedback, round); }
        catch (RuntimeException e) { response = CompletableFuture.failedFuture(e); }
        return response.handle((patch, error) -> {
            if (error != null) return new Candidate(plan, "Repair transport/provider failed; original program retained");
            try { return new Candidate(applyPatch(plan, gap, patch), null); }
            catch (IllegalArgumentException e) { return new Candidate(plan, e.getMessage()); }
        }).thenCompose(candidate -> step(candidate.plan(), exchange, progress, round, candidate.rejection()));
    }

    private record Candidate(LlmPlan plan, String rejection) {}

    /** Only an existing scalar leaf in the diagnosed statement may change. Control flow is immutable. */
    public static LlmPlan applyPatch(LlmPlan plan, CapabilityGap gap, Patch patch) {
        if (patch == null || patch.path() == null || patch.value() == null || patch.value().isNull()
                || !(patch.value().isTextual() || patch.value().isNumber()) || patch.value().asText().length() > 4096)
            throw new IllegalArgumentException("Expected one bounded scalar replacement");
        if (!REPAIRABLE.contains(gap.code()) || gap.details() == null || !(gap.details().get("node") instanceof String id))
            throw new IllegalArgumentException("Diagnostic has no repairable statement");
        var tree = JSON.<ObjectNode>valueToTree(plan);
        var path = patch.path();
        if (!path.matches("/components/[0-9]+/params/fbd_program/.*") || path.contains("~"))
            throw new IllegalArgumentException("Patch must target the diagnosed FBD program");
        var old = tree.at(path);
        if (old.isMissingNode() || !old.isValueNode() || old.isNull())
            throw new IllegalArgumentException("Only existing scalar leaves may be replaced");
        String nodePath = path;
        JsonNode node = null;
        while (nodePath.contains("/")) {
            nodePath = nodePath.substring(0, nodePath.lastIndexOf('/'));
            var candidate = tree.at(nodePath);
            if (candidate.isObject() && candidate.path("id").asText().equals(id) && candidate.has("stmt")) {
                node = candidate; break;
            }
        }
        if (node == null || !(gap.path().equals(nodePath) || gap.path().startsWith(nodePath + "/")))
            throw new IllegalArgumentException("Patch must stay in the diagnosed statement");
        if ("E_FBD_UNKNOWN_FUNCTION".equals(gap.code())) {
            if (!"call".equals(node.path("stmt").asText()) || !path.equals(nodePath + "/fn") || !patch.value().isTextual())
                throw new IllegalArgumentException("Unknown function repair may only replace call.fn");
            String marker = "/params/fbd_program";
            var functions = tree.at(nodePath.substring(0,nodePath.indexOf(marker)+marker.length()) + "/functions");
            int best = 3, matches = 0;
            String closest = null;
            for (var function : functions) {
                String name = function.path("name").asText();
                int distance = typoDistance(old.asText(),name);
                if (distance < best) { best=distance; matches=1; closest=name; }
                else if (distance == best) matches++;
            }
            if (best > 2 || matches != 1 || !patch.value().asText().equals(closest))
                throw new IllegalArgumentException("Function repair requires one unambiguous existing name within two edits");
        } else {
            if (!"emit".equals(node.path("stmt").asText()) || !path.startsWith(nodePath + "/params/")
                    || !gap.path().equals(path))
                throw new IllegalArgumentException("Parameter repair must target the reported emit parameter");
            if (old.isNumber() || patch.value().isNumber()) {
                try {
                    if (new BigDecimal(old.asText()).compareTo(new BigDecimal(patch.value().asText())) != 0)
                        throw new IllegalArgumentException("Repair cannot change numeric dimensions or quantities");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Numeric repair must preserve the original value");
                }
            }
        }
        if (old.equals(patch.value())) throw new IllegalArgumentException("Repair made no change");
        String parent = path.substring(0, path.lastIndexOf('/'));
        if (!(tree.at(parent) instanceof ObjectNode object)) throw new IllegalArgumentException("Expected object field");
        object.set(path.substring(path.lastIndexOf('/') + 1), patch.value());
        try { return JSON.treeToValue(tree, LlmPlan.class); }
        catch (Exception e) { throw new IllegalArgumentException("Repair produced an invalid plan envelope", e); }
    }

    private static int typoDistance(String a, String b) {
        if (a.length()>64 || b.length()>64 || Math.abs(a.length()-b.length())>2) return 3;
        int[] previous=new int[b.length()+1];
        for(int j=0;j<previous.length;j++) previous[j]=j;
        for(int i=1;i<=a.length();i++) {
            int[] current=new int[b.length()+1]; current[0]=i;
            for(int j=1;j<=b.length();j++) current[j]=Math.min(Math.min(previous[j]+1,current[j-1]+1),
                    previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            previous=current;
        }
        return previous[b.length()];
    }

    private static LlmPlan failure(LlmPlan plan, CapabilityGap gap, int attempts, String rejection) {
        var tree = JSON.<ObjectNode>valueToTree(plan);
        var suggestions = new ArrayList<>(gap.suggestions() == null ? List.<String>of() : gap.suggestions());
        if (rejection != null) suggestions.add(rejection);
        var terminal = new CapabilityGap(gap.code(), gap.message() + "（自动修订 " + attempts + "/" + MAX_ROUNDS
                + "；未返回部分建筑）", gap.path(), suggestions, gap.details());
        tree.put("plan_status", "capability_gap"); tree.put("error", terminal.message());
        tree.set("capability_gap", JSON.valueToTree(terminal));
        try { return JSON.treeToValue(tree, LlmPlan.class); }
        catch (Exception e) { throw new IllegalStateException("Cannot report FBD diagnostic", e); }
    }
}
