package com.formacraft.common.component;

import com.formacraft.common.component.community.CommunityDownloadCache;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.Strictness;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Complete data-only single-artifact backups. Never writes blocks or runs a generator. */
public final class PortableAssetBackup {
    private PortableAssetBackup() {}
    public static final long MAX_BYTES=256L*1024*1024;
    public static final int MAX_FILES=1024, MAX_INDEX=1024*1024;
    private static final String SCHEMA="formacraft.portable-backup.v1";
    public enum Kind { RECIPE, LOCK }
    private record Blob(long length,String sha256) {}
    private record Header(String schema,Kind kind,String id,UUID owner,Map<String,Blob> files) {}
    public record Preview(Kind kind,String id,UUID originalOwner,String label,int files,long expandedBytes,String sha256) {}
    public record Imported(Kind kind,String id,boolean reused) {}
    private record Closure(String label,Set<String> files,Object artifact) {}
    private record Stage(Path root,Header header,Preview preview,Object artifact) implements AutoCloseable {
        public void close() throws IOException { cleanup(root); }
    }
    public static Preview inspect(Path archive) throws IOException {
        try(var stage=stage(archive)) { return stage.preview; }
    }
    public static Preview exportRecipe(UUID owner,String id,Path target) throws IOException {
        return export(ComponentRecipeLibrary.backupVault(owner,false),owner,Kind.RECIPE,id,target);
    }
    public static Preview exportLock(Path world,UUID owner,String id,Path target) throws IOException {
        return export(BuildingAssetLocks.backupVault(world,owner,false),owner,Kind.LOCK,id,target);
    }
    private static Preview export(Path root,UUID owner,Kind kind,String id,Path target) throws IOException {
        var closure=closure(root,kind,id,owner); var files=new TreeMap<String,Blob>(); long total=0;
        for(String name:closure.files) { var blob=hash(safeFile(root,name),limit(name)); total+=blob.length; if(total>MAX_BYTES) throw invalid(); files.put(name,blob); }
        var header=new Header(SCHEMA,kind,id,owner,files); byte[] index=JsonUtil.toJson(header).getBytes(StandardCharsets.UTF_8);
        if(index.length>MAX_INDEX || total+index.length>MAX_BYTES) throw invalid();
        Path parent=target.toAbsolutePath().normalize().getParent();
        if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent)) throw invalid();
        Path pending=Files.createTempFile(parent,".export-",".pending");
        try {
            try(var output=new ZipOutputStream(new LimitedOutput(Files.newOutputStream(pending),MAX_BYTES),StandardCharsets.UTF_8)) {
                output.putNextEntry(new ZipEntry("backup.json")); output.write(index); output.closeEntry();
                for(var entry:files.entrySet()) {
                    output.putNextEntry(new ZipEntry(entry.getKey()));
                    try(var input=Files.newInputStream(safeFile(root,entry.getKey()),LinkOption.NOFOLLOW_LINKS)) {
                        if(!copy(input,output,entry.getValue().length).equals(entry.getValue())) throw invalid();
                    }
                    output.closeEntry();
                }
            }
            var preview=inspect(pending);
            // Hard-link publication is atomic and CREATE_NEW; unlike ATOMIC_MOVE it cannot replace an existing target.
            Files.createLink(target.toAbsolutePath().normalize(),pending);
            return preview;
        } finally { Files.deleteIfExists(pending); }
    }
    public static Imported importArchive(Path archive,String expectedSha,Path world,UUID owner) throws IOException {
        if(owner==null || expectedSha==null || !expectedSha.matches("[0-9a-f]{64}")) throw invalid();
        try(var stage=stage(archive)) {
            if(!expectedSha.equals(stage.preview.sha256)) throw new IOException("备份已变化，请重新检查");
            var rebound=rebind(stage,owner); var header=stage.header;
            synchronized(ComponentStorage.class) {
                Path root=header.kind==Kind.RECIPE ? ComponentRecipeLibrary.backupVault(owner,true) : BuildingAssetLocks.backupVault(world,owner,true);
                copyDependencies(stage.root,root,header.files.keySet());
                String folder=header.kind==Kind.RECIPE ? "recipes" : "locks";
                Path parent=ComponentAssetSnapshots.child(root,folder,true), destination=parent.resolve(header.id);
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
                    if(!record(root,header.kind,header.id,owner).equals(rebound)) throw new IOException("同 ID 的记录不同，拒绝覆盖");
                    return new Imported(header.kind,header.id,true);
                }
                if(header.kind==Kind.RECIPE) try(var children=Files.list(parent)) {
                    if(children.filter(p -> ComponentRecipeLibrary.isRecipe(p.getFileName().toString())).limit(256).count()>=256) throw new IOException("私人配方已达上限");
                }
                Path publishing=Files.createTempDirectory(parent,".import-");
                try {
                    for(String name:primaryFiles(header.kind,header.id))
                        Files.copy(safeFile(stage.root,name),publishing.resolve(Path.of(name).getFileName()));
                    Files.move(publishing,destination,StandardCopyOption.ATOMIC_MOVE);
                } finally { cleanup(publishing); }
                return new Imported(header.kind,header.id,false);
            }
        }
    }
    private static Stage stage(Path archive) throws IOException {
        Path absolute=archive.toAbsolutePath().normalize();
        if(!Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().equals(absolute) || Files.size(absolute)>MAX_BYTES) throw invalid();
        String sha=hash(absolute,MAX_BYTES).sha256; Path root=Files.createTempDirectory("formacraft-backup-"); boolean keep=false;
        try(var zip=new ZipFile(absolute.toFile(),StandardCharsets.UTF_8)) {
            var index=zip.getEntry("backup.json"); if(index==null || index.isDirectory() || index.getSize()<0 || index.getSize()>MAX_INDEX) throw invalid();
            Header header;
            try(var input=zip.getInputStream(index)) { header=header(input,index.getSize()); }
            var seen=new HashSet<String>(); long total=index.getSize(); var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement(); String name=entry.getName();
                if(entry.isDirectory() || !seen.add(name) || seen.size()>MAX_FILES+1) throw invalid();
                if(name.equals("backup.json")) continue;
                var expected=header.files.get(name);
                if(expected==null || entry.getSize()!=expected.length) throw invalid();
                total+=expected.length; if(total>MAX_BYTES) throw invalid();
                Path target=root.resolve(name); Files.createDirectories(target.getParent());
                try(var input=zip.getInputStream(entry); var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                    if(!copy(input,output,expected.length).equals(expected)) throw new IOException("备份文件校验失败");
                }
            }
            var expectedNames=new HashSet<>(header.files.keySet()); expectedNames.add("backup.json");
            if(!seen.equals(expectedNames) || !hash(absolute,MAX_BYTES).sha256.equals(sha)) throw invalid();
            var closure=closure(root,header.kind,header.id,header.owner);
            if(!closure.files.equals(header.files.keySet())) throw new IOException("备份依赖不完整或包含多余文件");
            keep=true; return new Stage(root,header,new Preview(header.kind,header.id,header.owner,closure.label,header.files.size(),total,sha),closure.artifact);
        } catch(RuntimeException invalid) { throw new IOException("备份格式无效",invalid); }
        finally { if(!keep) cleanup(root); }
    }
    private static Header header(InputStream input,long expectedLength) throws IOException {
        byte[] bytes=input.readNBytes(MAX_INDEX+1); if(bytes.length>MAX_INDEX || bytes.length!=expectedLength) throw invalid();
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(text.startsWith("\ufeff")) throw invalid();
        try(var reader=new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT); reader.beginObject(); var keys=new HashSet<String>();
            String schema=null,id=null,owner=null,kind=null; Map<String,Blob> files=null;
            while(reader.hasNext()) {
                String key=reader.nextName(); if(!keys.add(key)) throw invalid();
                switch(key) {
                    case "schema" -> schema=string(reader,64);
                    case "kind" -> kind=string(reader,16);
                    case "id" -> id=string(reader,64);
                    case "owner" -> owner=string(reader,36);
                    case "files" -> {
                        files=new LinkedHashMap<>(); reader.beginObject(); long total=0;
                        while(reader.hasNext()) {
                            String name=reader.nextName(); if(files.size()>=MAX_FILES || files.containsKey(name)) throw invalid(); long maximum=limit(name);
                            reader.beginObject(); var fields=new HashSet<String>(); String digest=null; Long length=null;
                            while(reader.hasNext()) {
                                String field=reader.nextName(); if(!fields.add(field)) throw invalid();
                                if(field.equals("sha256")) digest=string(reader,64);
                                else if(field.equals("length")) {
                                    if(reader.peek()!=JsonToken.NUMBER) throw invalid(); length=new BigDecimal(reader.nextString()).longValueExact();
                                } else throw invalid();
                            }
                            reader.endObject();
                            if(digest==null || !digest.matches("[0-9a-f]{64}") || length==null || length<0 || length>maximum) throw invalid();
                            total+=length; if(total>MAX_BYTES) throw invalid(); files.put(name,new Blob(length,digest));
                        }
                        reader.endObject();
                    }
                    default -> throw invalid();
                }
            }
            reader.endObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT || !SCHEMA.equals(schema) || kind==null || owner==null || id==null || files==null || files.isEmpty()) throw invalid();
            Kind type=Kind.valueOf(kind); UUID who=UUID.fromString(owner);
            if(!who.toString().equals(owner) || !(type==Kind.RECIPE ? ComponentRecipeLibrary.isRecipe(id) : BuildingAssetLocks.validId(id))) throw invalid();
            return new Header(schema,type,id,who,Map.copyOf(files));
        } catch(RuntimeException invalid) { throw new IOException("备份索引无效",invalid); }
    }
    private static String string(JsonReader reader,int maximum) throws IOException {
        if(reader.peek()!=JsonToken.STRING) throw invalid(); String value=reader.nextString(); if(value.isBlank() || value.length()>maximum) throw invalid(); return value;
    }
    private static long limit(String name) throws IOException {
        if(name.matches("snapshots/[0-9a-f]{64}_[0-9a-f]{64}/source\\.fccomponent\\.json")
                || name.matches("community_downloads/objects/[0-9a-f]{64}/original\\.fccomponent\\.json")
                || name.matches("recipes/recipe_[0-9a-f]{48}/result\\.fccomponent\\.json")) return ComponentPackageIO.MAX_PACKAGE_BYTES;
        if(name.matches("community_downloads/receipts/receipt_[0-9a-f]{64}/receipt\\.json")) return CommunityDownloadCache.MAX_RECEIPT_BYTES;
        if(name.matches("recipes/recipe_[0-9a-f]{48}/recipe\\.json")) return 16384;
        if(name.matches("locks/lock_[0-9a-f]{32}/manifest\\.json")) return BuildingAssetLocks.MAX_MANIFEST;
        if(name.matches("locks/lock_[0-9a-f]{32}/input-plan\\.json")) return BuildingAssetLocks.MAX_PLAN;
        if(name.matches("locks/lock_[0-9a-f]{32}/patches\\.json\\.gz")) return BuildingAssetLocks.MAX_PATCH_BYTES;
        throw invalid();
    }
    private static Closure closure(Path root,Kind kind,String id,UUID owner) throws IOException {
        var names=new TreeSet<>(primaryFiles(kind,id)); String label; Object artifact;
        if(kind==Kind.RECIPE) {
            var recipe=ComponentRecipeLibrary.backupRead(root,owner,id); label=recipe.spec().name(); artifact=recipe;
            names.add("snapshots/"+recipe.snapshot()+"/source.fccomponent.json");
            if(recipe.communityReceipt()!=null) addReceipt(root,names,recipe.communityReceipt());
        } else {
            var lock=BuildingAssetLocks.backupRead(root,owner,id).manifest(); label=id; artifact=lock;
            for(String key:lock.dependencies().keySet()) names.add("snapshots/"+key+"/source.fccomponent.json");
            for(String receipt:lock.communityReceipts().keySet()) addReceipt(root,names,receipt);
        }
        if(names.size()>MAX_FILES) throw invalid(); return new Closure(label,Set.copyOf(names),artifact);
    }
    private static Set<String> primaryFiles(Kind kind,String id) throws IOException {
        if(kind==Kind.RECIPE && ComponentRecipeLibrary.isRecipe(id)) return Set.of("recipes/"+id+"/recipe.json","recipes/"+id+"/result.fccomponent.json");
        if(kind==Kind.LOCK && BuildingAssetLocks.validId(id)) return Set.of("locks/"+id+"/manifest.json","locks/"+id+"/input-plan.json","locks/"+id+"/patches.json.gz");
        throw invalid();
    }
    private static void addReceipt(Path root,Set<String> names,String receipt) throws IOException {
        var cached=CommunityDownloadCache.read(root.resolve("community_downloads"),receipt,ComponentPackageIO.currentEnvironment());
        names.add("community_downloads/receipts/"+receipt+"/receipt.json");
        names.add("community_downloads/objects/"+cached.details().descriptor().releaseRef().packageSha256()+"/original.fccomponent.json");
    }
    private static Object record(Path root,Kind kind,String id,UUID owner) throws IOException {
        return kind==Kind.RECIPE ? ComponentRecipeLibrary.backupRead(root,owner,id) : BuildingAssetLocks.backupRead(root,owner,id).manifest();
    }
    private static Object rebind(Stage stage,UUID owner) throws IOException {
        var h=stage.header;
        if(h.kind==Kind.RECIPE) {
            var r=(ComponentRecipeLibrary.Recipe)stage.artifact;
            var updated=new ComponentRecipeLibrary.Recipe(r.schema(),r.engine(),r.identity(),r.snapshot(),owner.toString(),r.spec(),r.resultRef(),r.resultManifest(),r.communityReceipt());
            Files.writeString(stage.root.resolve("recipes/"+h.id+"/recipe.json"),JsonUtil.toJson(updated),StandardCharsets.UTF_8);
            return updated;
        } else {
            var m=(BuildingAssetLocks.Manifest)stage.artifact;
            var updated=new BuildingAssetLocks.Manifest(m.schema(),m.id(),owner.toString(),m.dimension(),m.createdAt(),m.engine(),m.producerVersion(),m.seed(),m.originX(),m.originY(),m.originZ(),m.planSha256(),m.patchesSha256(),m.patchCount(),m.dependencies(),m.shapes(),m.uses(),m.communityReceipts(),m.revision());
            Files.writeString(stage.root.resolve("locks/"+h.id+"/manifest.json"),JsonUtil.toJson(updated),StandardCharsets.UTF_8);
            return updated;
        }
    }
    private static void copyDependencies(Path source,Path target,Set<String> names) throws IOException {
        for(String name:names) {
            if(name.startsWith("snapshots/")) ComponentAssetSnapshots.save(target,ComponentAssetSnapshots.read(source,name.split("/")[1]));
            else if(name.startsWith("community_downloads/receipts/")) {
                var receipt=CommunityDownloadCache.read(source.resolve("community_downloads"),name.split("/")[2],ComponentPackageIO.currentEnvironment());
                CommunityDownloadCache.pin(target.resolve("community_downloads"),receipt,ComponentPackageIO.currentEnvironment());
            }
        }
    }
    private static Path safeFile(Path root,String name) throws IOException {
        limit(name); Path current=root;
        for(String part:name.split("/")) { Path next=current.resolve(part); if(!next.toRealPath().equals(current.toRealPath().resolve(part))) throw invalid(); current=next; }
        if(!Files.isRegularFile(current,LinkOption.NOFOLLOW_LINKS)) throw invalid(); return current;
    }
    private static Blob hash(Path file,long maximum) throws IOException {
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { return copy(input,OutputStream.nullOutputStream(),maximum); }
    }
    private static Blob copy(InputStream input,OutputStream output,long maximum) throws IOException {
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer=new byte[16384]; long count=0; int read;
        while((read=input.read(buffer))!=-1) { count+=read; if(count>maximum) throw invalid(); output.write(buffer,0,read); digest.update(buffer,0,read); }
        return new Blob(count,HexFormat.of().formatHex(digest.digest()));
    }
    private static final class LimitedOutput extends FilterOutputStream {
        long left; LimitedOutput(OutputStream output,long maximum) { super(output); left=maximum; }
        public void write(int value) throws IOException { if(--left<0) throw invalid(); out.write(value); }
        public void write(byte[] bytes,int off,int length) throws IOException { if(length>left) throw invalid(); left-=length; out.write(bytes,off,length); }
    }
    private static void cleanup(Path root) throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths=Files.walk(root)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private static IOException invalid() { return new IOException("备份格式、路径或大小无效"); }
    public static boolean fileName(String value) { return value!=null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}\\.fcbackup"); }
    public static Path exchange(Path world,UUID owner,String folder) throws IOException {
        if(owner==null || !Set.of("inbox","outbox").contains(folder)) throw invalid();
        Path root=world.toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root)) throw invalid();
        for(String child:List.of("formacraft","backups","players",owner.toString(),folder)) root=ComponentAssetSnapshots.child(root,child,true);
        return root;
    }
    public static void outboxQuota(Path outbox) throws IOException {
        try(var children=Files.list(outbox)) {
            var paths=children.limit(129).toList(); if(paths.size()>128) throw new IOException("请整理备份导出目录");
            int count=0; long total=0;
            for(Path file:paths) if(fileName(file.getFileName().toString())) {
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw invalid();
                long size=Files.size(file); if(size>MAX_BYTES) throw invalid();
                count++; total+=size;
            }
            if(count>8 || total>512L*1024*1024) throw new IOException("导出目录最多 8 份或 512 MiB，请移走已有备份");
        }
    }
}
