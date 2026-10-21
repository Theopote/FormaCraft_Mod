package com.formacraft.common.style;

import com.google.gson.*;
import com.formacraft.config.SettingsConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Additive, startup-frozen JSON assets shared by the existing registries. */
public final class UserStylePacks {
    private UserStylePacks() {}
    private static final String PREFIX="/assets/formacraft/";
    private static final Map<String,String> TABLES=Map.of(
        "style_profiles/style_profile_catalog_v1.json","profiles",
        "style_profiles/style_identity_catalog_v1.json","lexical_aliases",
        "palettes/palette_catalog_v1.json","palettes",
        "structural_typologies/structural_typologies_v1.json","typologies",
        "style_visual_cases/catalog_v1.json","cases");
    private static final Set<String> DIRECTORIES=Set.of("style_genomes","culture_cards","proportion_cards","llmplan_examples","ornament_contracts");
    public record Snapshot(Map<String,JsonObject> assets,List<String> accepted,List<String> errors) {}
    private static volatile Snapshot active;

    public static Path configuredDirectory() {
        String env=System.getenv("FORMACRAFT_STYLE_PACKS_DIR");
        String value=System.getProperty("formacraft.stylePacksDir",env!=null && !env.isBlank()?env:SettingsConfig.persistedUserStylePacksDirectory());
        return Path.of(value==null || value.isBlank()?"config/formacraft/style_packs":value).toAbsolutePath().normalize();
    }

    private static synchronized Snapshot active() {
        if(active==null) {
            try { active=discover(configuredDirectory()); }
            catch(RuntimeException error) { active=new Snapshot(Map.of(),List.of(),List.of("Invalid style pack root: "+error.getMessage())); }
        }
        return active;
    }

    public static InputStream open(String path) {
        if(!path.startsWith(PREFIX)) throw new IllegalArgumentException("Unsupported asset resource");
        JsonObject data=active().assets().get(path.substring(PREFIX.length()));
        return data==null?UserStylePacks.class.getResourceAsStream(path):new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject builtin(String relative) throws IOException {
        try(var input=UserStylePacks.class.getResourceAsStream(PREFIX+relative)) {
            return input==null?null:JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject read(Path path) throws IOException {
        if(Files.size(path)>262144) throw new IllegalArgumentException("JSON file exceeds 256 KiB");
        return JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public static Snapshot discover(Path directory) {
        var overlay=new LinkedHashMap<String,JsonObject>();
        var accepted=new ArrayList<String>(); var errors=new ArrayList<String>();
        try {
            if(!Files.exists(directory)) return new Snapshot(Map.of(),List.of(),List.of());
            Path root=directory.toRealPath();
            List<Path> folders;
            try(var stream=Files.list(root)) { folders=stream.sorted().toList(); }
            if(folders.size()>32) throw new IllegalArgumentException("At most 32 pack directories are allowed");
            for(Path folder:folders) {
                if(!Files.isDirectory(folder)) continue;
                String id=folder.getFileName().toString();
                try {
                    if(Files.isSymbolicLink(folder) || !folder.toRealPath().getParent().equals(root)) throw new IllegalArgumentException("Pack directory leaves root");
                    var manifestPath=folder.resolve("style-pack.json");
                    if(Files.isSymbolicLink(manifestPath) || !manifestPath.toRealPath().startsWith(folder.toRealPath())) throw new IllegalArgumentException("Manifest path leaves pack");
                    var manifest=read(manifestPath);
                    id=manifest.get("id").getAsString();
                    if(!"formacraft.user_style_pack.v1".equals(manifest.get("schema").getAsString()) || !id.matches("[a-z][a-z0-9_]{1,31}"))
                        throw new IllegalArgumentException("Unsupported schema or invalid pack ID");
                    if(accepted.contains(id)) throw new IllegalArgumentException("Duplicate pack ID");
                    var files=manifest.getAsJsonArray("files");
                    if(files.isEmpty() || files.size()>128) throw new IllegalArgumentException("Expected 1–128 unique JSON files");
                    var trial=new LinkedHashMap<String,JsonObject>();
                    overlay.forEach((key,value)->trial.put(key,value.deepCopy()));
                    var seen=new HashSet<String>(); long total=0;
                    for(var element:files) {
                        String relative=element.getAsString();
                        if(!relative.matches("[a-z_]+/[A-Za-z0-9_]+\\.json") || !seen.add(relative)) throw new IllegalArgumentException("Invalid or duplicate asset path");
                        Path path=folder.resolve(relative);
                        if(Files.isSymbolicLink(path) || !path.toRealPath().startsWith(folder.toRealPath())) throw new IllegalArgumentException("Asset path leaves pack");
                        total+=Files.size(path); if(total>4194304) throw new IllegalArgumentException("Pack exceeds 4 MiB");
                        var data=read(path);
                        String table=TABLES.get(relative);
                        if(table!=null) {
                            var values=data.get(table); if(values==null) throw new IllegalArgumentException("Missing catalog table");
                            var base=trial.containsKey(relative)?trial.get(relative):builtin(relative);
                            base=base==null?new JsonObject():base.deepCopy();
                            var ids=new HashSet<String>();
                            if(table.equals("typologies") || table.equals("cases")) {
                                if(!base.has(table)) base.add(table,new JsonArray());
                                for(var entry:base.getAsJsonArray(table)) ids.add(entry.getAsJsonObject().get("id").getAsString().toLowerCase(Locale.ROOT));
                                for(var value:values.getAsJsonArray()) {
                                    var item=value.getAsJsonObject(); String key=item.get("id").getAsString();
                                    checkId(key,id,ids);
                                    if(table.equals("typologies") && (!"compositional_only".equals(item.get("routingPolicy").getAsString())
                                            || item.has("interpreterId") && !item.get("interpreterId").isJsonNull() && !item.get("interpreterId").getAsString().isEmpty()))
                                        throw new IllegalArgumentException("User typologies must be compositional_only without native interpreters");
                                    base.getAsJsonArray(table).add(item.deepCopy());
                                }
                            } else {
                                if(!base.has(table)) base.add(table,new JsonObject());
                                base.getAsJsonObject(table).keySet().forEach(key->ids.add(key.toLowerCase(Locale.ROOT)));
                                for(var entry:values.getAsJsonObject().entrySet()) {
                                    checkId(entry.getKey(),id,ids);
                                    base.getAsJsonObject(table).add(entry.getKey(),entry.getValue().deepCopy());
                                }
                            }
                            trial.put(relative,base);
                        } else {
                            String stem=relative.substring(relative.indexOf('/')+1,relative.length()-5);
                            if(!DIRECTORIES.contains(relative.split("/")[0]) || !stem.toLowerCase(Locale.ROOT).startsWith(id+"_")) throw new IllegalArgumentException("Unsupported asset kind or missing pack prefix");
                            if(trial.containsKey(relative) || builtin(relative)!=null) throw new IllegalArgumentException("Asset path collision");
                            if(!stem.equals(data.get("id").getAsString())) throw new IllegalArgumentException("Asset ID must match filename");
                            trial.put(relative,data);
                        }
                    }
                    validate(trial);
                    overlay=trial; accepted.add(id);
                } catch(Exception error) { errors.add(id+": "+error.getMessage()); }
            }
        } catch(Exception error) { errors.add(error.getMessage()); }
        return new Snapshot(Collections.unmodifiableMap(overlay),List.copyOf(accepted),List.copyOf(errors));
    }

    private static void checkId(String value,String pack,Set<String> ids) {
        String key=value.toLowerCase(Locale.ROOT);
        if(!key.startsWith(pack+"_") || !ids.add(key)) throw new IllegalArgumentException("Duplicate, builtin or non-namespaced asset ID: "+value);
    }

    private static JsonObject catalog(Map<String,JsonObject> assets,String path) throws IOException {
        return assets.containsKey(path)?assets.get(path):builtin(path);
    }

    private static void validate(Map<String,JsonObject> assets) throws IOException {
        var profiles=catalog(assets,"style_profiles/style_profile_catalog_v1.json").getAsJsonObject("profiles");
        var palettes=catalog(assets,"palettes/palette_catalog_v1.json").getAsJsonObject("palettes");
        var types=new HashSet<String>();
        for(var item:catalog(assets,"structural_typologies/structural_typologies_v1.json").getAsJsonArray("typologies")) types.add(item.getAsJsonObject().get("id").getAsString());
        var aliases=new HashMap<String,String>();
        var identity=catalog(assets,"style_profiles/style_identity_catalog_v1.json");
        for(var entry:identity.getAsJsonObject("aliases").entrySet()) aliases.put(entry.getKey().toLowerCase(Locale.ROOT),entry.getValue().getAsString());
        for(var entry:identity.getAsJsonObject("lexical_aliases").entrySet()) {
            var names=new JsonArray(); names.add(entry.getKey());
            entry.getValue().getAsJsonArray().forEach(names::add);
            for(var name:names) {
                String key=name.getAsString().toLowerCase(Locale.ROOT);
                if(key.isBlank()) throw new IllegalArgumentException("Empty alias");
                String previous=aliases.putIfAbsent(key,entry.getKey());
                if(previous!=null && !previous.equals(entry.getKey())) throw new IllegalArgumentException("Style alias collision: "+key);
            }
        }
        var builtinIdentities=builtin("style_profiles/style_identity_catalog_v1.json").getAsJsonObject("lexical_aliases");
        for(var entry:identity.getAsJsonObject("lexical_aliases").entrySet()) {
            String style=entry.getKey();
            if(!builtinIdentities.has(style) && !profiles.has(style) && !assets.containsKey("style_genomes/"+style+".json")) throw new IllegalArgumentException("Unknown identity profile/genome");
        }
        for(var entry:assets.entrySet()) {
            String path=entry.getKey(); var data=entry.getValue();
            if(path.startsWith("style_genomes/")) {
                var roles=data.getAsJsonObject("palette");
                if(roles==null || !roles.keySet().equals(Set.of("wall","roof","floor","window","foundation","trim","pillar","cap","door","roof_trim","stairs","roof_slab"))) throw new IllegalArgumentException("Genome requires twelve material roles");
                for(var value:roles.entrySet()) if(!value.getValue().getAsString().matches("minecraft:[a-z0-9_]+")) throw new IllegalArgumentException("Invalid material ID");
            } else if(path.startsWith("culture_cards/")) {
                if(!profiles.has(data.get("styleId").getAsString()) || !types.contains(data.get("structuralTypologyId").getAsString())) throw new IllegalArgumentException("Unknown culture style/typology");
                if(data.has("exampleRefs")) for(var ref:data.getAsJsonArray("exampleRefs")) requireAsset(assets,"assembly_examples/"+ref.getAsString());
                if(data.has("patchCardId") && !data.get("patchCardId").getAsString().matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid patch card reference");
                if(data.has("llmPlanExampleRefs")) for(var ref:data.getAsJsonArray("llmPlanExampleRefs")) requireAsset(assets,"llmplan_examples/"+ref.getAsString());
                if(data.has("proportionCardId")) requireAsset(assets,"proportion_cards/"+data.get("proportionCardId").getAsString()+".json");
            } else if(path.startsWith("llmplan_examples/")) {
                var plan=data.has("plan")?data.getAsJsonObject("plan"):data;
                if(!plan.has("components") || plan.getAsJsonArray("components").isEmpty()) throw new IllegalArgumentException("Plan example requires components");
            } else if(path.startsWith("ornament_contracts/")) {
                validateOrnament(data);
            }
        }
        for(var entry:profiles.entrySet()) {
            var defaults=entry.getValue().getAsJsonObject().getAsJsonObject("defaults");
            if(defaults!=null && defaults.has("components")) {
                var components=defaults.getAsJsonObject("components");
                if(components.has("palette_id") && !palettes.has(components.get("palette_id").getAsString())) throw new IllegalArgumentException("Unknown profile palette");
            }
        }
        var builtinCases=new HashSet<String>();
        for(var item:builtin("style_visual_cases/catalog_v1.json").getAsJsonArray("cases")) builtinCases.add(item.getAsJsonObject().get("id").getAsString());
        for(var item:catalog(assets,"style_visual_cases/catalog_v1.json").getAsJsonArray("cases")) {
            var value=item.getAsJsonObject(); if(builtinCases.contains(value.get("id").getAsString())) continue;
            if(!profiles.has(value.get("style_id").getAsString()) || value.getAsJsonObject("feature_groups").size()<2) throw new IllegalArgumentException("Invalid visual case");
            for(var group:value.getAsJsonObject("feature_groups").entrySet()) {
                if(group.getValue().getAsJsonArray().isEmpty()) throw new IllegalArgumentException("Empty visual feature group");
                for(var term:group.getValue().getAsJsonArray()) if(!term.isJsonPrimitive() || !term.getAsJsonPrimitive().isString() || term.getAsString().isBlank()) throw new IllegalArgumentException("Invalid visual feature term");
            }
            String reference="llmplan_examples/"+value.get("plan_fixture").getAsString();
            if(!assets.containsKey(reference)) throw new IllegalArgumentException("Unknown visual plan example");
        }
    }

    private static void requireAsset(Map<String,JsonObject> assets,String relative) throws IOException {
        if(!relative.matches("[a-z_]+/[A-Za-z0-9_]+\\.json") || !assets.containsKey(relative) && builtin(relative)==null) throw new IllegalArgumentException("Missing/invalid referenced asset: "+relative);
    }

    private static void validateOrnament(JsonObject data) {
        if(!"formacraft.ornament_contract.v1".equals(data.get("schema").getAsString())) throw new IllegalArgumentException("Unsupported ornament schema");
        String application=data.get("application").getAsString();
        if(!Set.of("standalone","collect").contains(application)) throw new IllegalArgumentException("Invalid ornament application");
        var target=data.getAsJsonObject("target");
        if(target.get("component_type").getAsString().isBlank()) throw new IllegalArgumentException("Missing ornament component type");
        if(!target.has("typology_id") && !target.has("style_ids")) throw new IllegalArgumentException("Missing ornament target");
        if(target.has("typology_id") && target.get("typology_id").getAsString().isBlank()) throw new IllegalArgumentException("Invalid ornament typology");
        if(target.has("style_ids")) {
            if(target.getAsJsonArray("style_ids").isEmpty()) throw new IllegalArgumentException("Empty ornament styles");
            for(var style:target.getAsJsonArray("style_ids")) if(style.getAsString().isBlank()) throw new IllegalArgumentException("Invalid ornament style");
        }
        java.util.regex.Pattern.compile(data.get("unresolved_scope_pattern").getAsString());
        if(application.equals("standalone")) {
            var report=data.getAsJsonObject("report");
            if(!report.get("key").getAsString().startsWith(data.get("id").getAsString()+"_") || report.get("schema").getAsString().isBlank()) throw new IllegalArgumentException("User ornament report key must start with contract_id_");
        }
        var properties=new HashSet<String>();
        if(data.has("toggles")) for(var value:data.getAsJsonArray("toggles")) {
            var toggle=value.getAsJsonObject(); String property=toggle.get("property").getAsString();
            if(property.isBlank() || !properties.add(property) || !toggle.has("negative") && !toggle.has("positive")) throw new IllegalArgumentException("Invalid ornament toggle");
            for(String field:List.of("negative","positive")) if(toggle.has(field)) java.util.regex.Pattern.compile(toggle.get(field).getAsString());
            if(toggle.has("positive_verification_level") && !"plan_parameters_only".equals(toggle.get("positive_verification_level").getAsString())) throw new IllegalArgumentException("Invalid verification level");
        }
        if(data.has("choices")) for(var value:data.getAsJsonArray("choices")) {
            var choice=value.getAsJsonObject(); java.util.regex.Pattern.compile(choice.get("pattern").getAsString());
            if(choice.has("unless")) java.util.regex.Pattern.compile(choice.get("unless").getAsString());
            if(choice.getAsJsonObject("params").isEmpty()) throw new IllegalArgumentException("Empty ornament choice");
        }
    }
}
