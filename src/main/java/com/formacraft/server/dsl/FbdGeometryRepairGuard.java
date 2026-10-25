package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formacraft.common.dsl.FbdCompiler;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.build.quality.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Candidate-only geometry contract. No provider calls, world writes or preview authority. */
public final class FbdGeometryRepairGuard {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Assessment(Permit permit, String reason) {
        public boolean eligible() { return permit != null; }
    }
    public record Evaluation(LlmPlan candidate, BuildQualityReport report, String reason) {
        public boolean accepted() { return candidate != null; }
    }
    private record Operation(String path, String node, String instance, String slot, Map<String,Object> op) {}
    private record Metric(int severity, int count) {}

    /** Private construction binds the scalar rule, baseline plan and measured quality. */
    public static final class Permit {
        private final String fingerprint, path;
        private final int original;
        private final ObjectNode snapshot;
        private final List<Operation> operations;
        private final Map<String,Metric> baseline;
        private Permit(String fingerprint, String path, int original, ObjectNode snapshot, List<Operation> operations, Map<String,Metric> baseline) {
            this.fingerprint=fingerprint; this.path=path; this.original=original; this.snapshot=snapshot;
            this.operations=operations; this.baseline=baseline;
        }
        public String path() { return path; }
        public int minimum() { return original-1; }
        public int maximum() { return original+1; }
        public int affectedInstances() { return 1; }
    }
    private FbdGeometryRepairGuard() {}

    public static Assessment assess(LlmPlan plan, BuildQualityReport report) {
        if (plan==null || plan.hasCapabilityGap() || report==null || report.hasFatal()) return no("BLOCKED_QUALITY");
        var active=report.activeDiagnostics();
        if (active.isEmpty()) return no("NO_ACTIVE_ISSUE");
        if (active.stream().anyMatch(i -> !"final".equals(i.phase()))) return no("NOT_FINAL");
        if (active.stream().anyMatch(i -> i.affectedCount()<=0)) return no("UNMEASURED_QUALITY");
        var floating=active.stream().filter(i -> "FLOATING".equals(i.code())).toList();
        if (floating.size()!=1) return no("UNSUPPORTED_ISSUE");
        var issue=floating.getFirst();
        if (issue.positions().isEmpty() || issue.origins().isEmpty()) return no("MISSING_SOURCE");
        var sampled=new HashSet<>(issue.positions());
        var located=new HashSet<BuildQualityIssue.Position>();
        var source=issue.origins().getFirst().source();
        if (!"fbd".equals(source.producer()) || source.path()==null || source.componentPath()==null)
            return no("SYSTEM_OR_UNKNOWN_SOURCE");
        for (var origin:issue.origins()) {
            if (!source.equals(origin.source())) return no("MIXED_SOURCE");
            located.add(origin.position());
        }
        if (!located.equals(sampled)) return no("MISSING_SOURCE");
        try {
            var snapshot=JSON.<ObjectNode>valueToTree(plan);
            var baselinePlan=JSON.treeToValue(snapshot,LlmPlan.class);
            var operations=compile(baselinePlan);
            var affected=operations.stream().filter(o -> o.path().equals(source.path())).toList();
            // Count the complete expansion, never infer uniqueness from at most eight samples.
            if (affected.isEmpty()) return no("STALE_SOURCE");
            if (affected.size()!=1) return no("MULTI_INSTANCE");
            var operation=affected.getFirst();
            if (!Objects.equals(source.node(),operation.node()) || !Objects.equals(source.instance(),operation.instance())
                    || !Objects.equals(source.slotId(),operation.slot())
                    || !source.path().startsWith(source.componentPath()+"/params/fbd_program/")) return no("STALE_SOURCE");
            if (!"ROOF_COVER".equals(operation.op().get("op"))) return no("UNSUPPORTED_PRIMITIVE");
            var tree=snapshot;
            var statement=tree.at(source.path());
            if (!statement.path("stmt").asText().equals("emit") || !statement.path("id").asText().equals(source.node())
                    || !statement.path("op").asText().equals("ROOF_COVER")) return no("STALE_SOURCE");
            String path=source.path()+"/params/y";
            var literal=tree.at(path);
            if (!literal.isIntegralNumber() || !literal.canConvertToInt()) return no("NON_LITERAL_PARAMETER");
            int value=literal.intValue();
            if (value==Integer.MIN_VALUE || value==Integer.MAX_VALUE) return no("PARAMETER_BOUNDARY");
            return new Assessment(new Permit(fingerprint(baselinePlan),path,value,snapshot,operations,metrics(report)),"ELIGIBLE_CANDIDATE_ONLY");
        } catch (Exception failure) { return no("INVALID_PLAN"); }
    }

    /** One existing integer leaf, one block from the assessed baseline. Returns an unapproved plan. */
    public static LlmPlan candidate(LlmPlan original, Permit permit, FbdRepairLoop.Patch patch) {
        if (permit==null || patch==null || !permit.path.equals(patch.path()) || patch.value()==null
                || !patch.value().isIntegralNumber() || !patch.value().canConvertToInt())
            throw new IllegalArgumentException("Patch must replace the permitted literal integer");
        try {
            if (!permit.fingerprint.equals(fingerprint(original))) throw new IllegalArgumentException("Stale plan");
            int value=patch.value().intValue();
            if (value==permit.original || value<permit.minimum() || value>permit.maximum())
                throw new IllegalArgumentException("Roof adjustment must be exactly one block from baseline");
            var tree=permit.snapshot.deepCopy();
            ((ObjectNode)tree.at(permit.path.substring(0,permit.path.lastIndexOf('/')))).put("y",value);
            var candidate=JSON.treeToValue(tree,LlmPlan.class);
            var compiled=compile(candidate);
            if (compiled.size()!=permit.operations.size()) throw new IllegalArgumentException("Expansion changed");
            int changed=0;
            for(int i=0;i<compiled.size();i++) {
                var before=permit.operations.get(i); var after=compiled.get(i);
                if (!before.path().equals(after.path()) || !Objects.equals(before.node(),after.node())
                        || !Objects.equals(before.instance(),after.instance()) || !Objects.equals(before.slot(),after.slot()))
                    throw new IllegalArgumentException("Expansion identity changed");
                var expected=new LinkedHashMap<>(before.op());
                if ((before.path()+"/params/y").equals(permit.path)) { expected.put("y",value); changed++; }
                if (!expected.equals(after.op())) throw new IllegalArgumentException("Unpermitted operation changed");
            }
            if (changed!=1) throw new IllegalArgumentException("Expected one affected operation");
            return candidate;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Candidate failed compilation",e); }
    }

    /** The verifier must regenerate the complete candidate and run final quality, without applying it. */
    public static Evaluation evaluate(LlmPlan original, Permit permit, FbdRepairLoop.Patch patch,
                                      Function<LlmPlan,BuildQualityReport> verifier) {
        final LlmPlan candidate;
        try { candidate=candidate(original,permit,patch); }
        catch (IllegalArgumentException e) { return new Evaluation(null,null,"PATCH_REJECTED"); }
        final BuildQualityReport checked;
        try {
            var beforeVerification=fingerprint(candidate);
            checked=verifier.apply(candidate);
            if (!beforeVerification.equals(fingerprint(candidate)))
                return new Evaluation(null,null,"VERIFIER_MUTATED_PLAN");
        } catch (Exception e) { return new Evaluation(null,null,"VERIFICATION_FAILED"); }
        if (checked==null || checked.hasFatal() || checked.stats().totalBlocks<=0)
            return new Evaluation(null,checked,"VERIFICATION_FAILED");
        var active=checked.activeDiagnostics();
        if (active.stream().anyMatch(i -> !"final".equals(i.phase()) || i.affectedCount()<=0))
            return new Evaluation(null,checked,"UNMEASURED_QUALITY");
        var after=metrics(checked);
        for(var entry:after.entrySet()) {
            var baseline=permit.baseline.get(entry.getKey()); var metric=entry.getValue();
            if (baseline==null || metric.severity()<baseline.severity() || metric.count()>baseline.count())
                return new Evaluation(null,checked,"QUALITY_REGRESSION");
        }
        int remaining=checked.stats().floatingColumns;
        var measured=after.getOrDefault("FLOATING",new Metric(3,0));
        if (remaining<0 || (measured.count()>0 && measured.count()!=remaining)
                || (measured.count()==0 && remaining>=BuildQualityReport.floatingWarningColumns()))
            return new Evaluation(null,checked,"VERIFICATION_FAILED");
        // Below-warning residual columns still count; absence of a warning is not proof of zero.
        if (remaining>=permit.baseline.get("FLOATING").count())
            return new Evaluation(null,checked,"NO_IMPROVEMENT");
        return new Evaluation(candidate,checked,"VERIFIED_CANDIDATE_ONLY");
    }

    private static List<Operation> compile(LlmPlan plan) {
        var previous=AssemblyCompileDiagnostics.get();
        try {
            AssemblyCompileDiagnostics.set(null);
            var components=FbdPlanLowering.lower(plan.components());
            if (AssemblyCompileDiagnostics.hasGap()) throw new IllegalArgumentException("Invalid FBD plan");
            var result=new ArrayList<Operation>();
            for(var component:components) {
                if(component==null || component.params()==null) continue;
                if(component.params().get(FbdPlanLowering.COMPILED) instanceof FbdCompiler.Compilation compiled) {
                    var source=(FbdPlanLowering.SourceContext)component.params().get(FbdPlanLowering.SOURCE);
                    for(var command:compiled.commands()) result.add(new Operation(source.componentPath()+"/params/fbd_program"+command.path(),
                            command.node(),command.instance(),source.slotId(),command.op()));
                }
            }
            return List.copyOf(result);
        } finally { AssemblyCompileDiagnostics.set(previous); }
    }
    private static Map<String,Metric> metrics(BuildQualityReport report) {
        var result=new HashMap<String,Metric>();
        for(var issue:report.activeDiagnostics()) result.merge(issue.code(),new Metric(issue.severity().ordinal(),issue.affectedCount()),
                (a,b) -> new Metric(Math.min(a.severity(),b.severity()),Math.addExact(a.count(),b.count())));
        return Map.copyOf(result);
    }
    private static String fingerprint(LlmPlan plan) throws Exception {
        var data=JSON.convertValue(plan,Map.class);
        byte[] bytes=JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(data);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Assessment no(String reason) { return new Assessment(null,reason); }
}
