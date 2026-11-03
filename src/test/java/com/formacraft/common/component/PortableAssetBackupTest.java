package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.formacraft.common.component.transform.*;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.patch.BlockPatch;
import com.google.gson.JsonParser;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PortableAssetBackupTest {
    @TempDir Path temp;
    private Path world,next;
    private final UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
    private final ComponentPackageIO.Environment env=new ComponentPackageIO.Environment("1.21.10",Map.of());
    private ComponentPackageIO.Verified original;
    private String asset;
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() throws Exception {
        ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("old-library")); world=Files.createDirectory(temp.resolve("world")); next=Files.createDirectory(temp.resolve("next-world"));
        var d=new ComponentDefinition(); d.id="column"; d.name="原柱"; d.sourceOrigin="CAPTURED"; d.category=ComponentCategory.COLUMN;
        d.size=new ComponentDefinition.Size(); d.size.w=d.size.d=1; d.size.h=5;
        d.anchor=new ComponentDefinition.Anchor(); d.anchor.facing="SOUTH"; d.blocks=new ArrayList<>();
        for(int y=0;y<5;y++) { var block=new ComponentDefinition.BlockEntry(); block.dy=y; block.block="minecraft:stone"; d.blocks.add(block); }
        var rules=new CaptureAdaptationSettings(); rules.modes[1]=CaptureAdaptationSettings.Mode.REPEAT; d.variantRules=rules.toRules(d);
        original=pack(d,"alice","Alice",ComponentPackageIO.TrainingPermission.ALLOWED); asset=install(original,alice,world);
    }
    @AfterEach void reset() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentStorage.SaveContext context(Path root,UUID owner) { return new ComponentStorage.SaveContext(root,owner,false,false); }
    private ComponentPackageIO.Verified pack(ComponentDefinition d,String namespace,String author,ComponentPackageIO.TrainingPermission training) throws IOException {
        return ComponentPackageIO.create(d,new ComponentPackageIO.Manifest(namespace,"1",author,"Terms of "+author,training,"1.21.10",Map.of(),Map.of(),List.of()),env);
    }
    private String install(ComponentPackageIO.Verified source,UUID owner,Path root) throws IOException {
        Path file=temp.resolve(UUID.randomUUID()+".fccomponent.json"); source.writeTo(file); return ComponentPackageInstallations.importPackage(file,env,context(root,owner)).assetId();
    }
    private ComponentRecipeLibrary.Saved recipe(UUID owner,Path root,String id,String name) throws IOException {
        var entry=ComponentStorage.loadCatalog(root,owner).components.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow();
        return ComponentRecipeLibrary.save(root,owner,id,entry.packageRef,new ComponentRecipeLibrary.Spec(name,1,7,1));
    }
    private void targetDevice() { ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("new-library")); }
    private ComponentSourceBinding.Selection community() throws Exception {
        var source=pack(original.component(),"publisher","Publisher B",ComponentPackageIO.TrainingPermission.DENIED);
        byte[] raw=(" \n"+new String(source.toBytes(),StandardCharsets.UTF_8)+"\n ").getBytes(StandardCharsets.UTF_8);
        var ref=new CommunityPackages.ReleaseRef("test","resource","release_b",CommunityPackages.sha256(raw),source.sha256(),source.manifestSha256());
        var descriptor=new CommunityPackages.Descriptor(ref,raw.length,ComponentPackageIO.SCHEMA,ComponentContentIdentity.fingerprints(source.component()));
        var index=new OfflineCommunityIndex("test",Clock.systemUTC()); index.add(descriptor,raw);
        var details=CommunityReleaseDetails.decode(index.releaseDetails(ref),descriptor);
        Path cache=temp.resolve("downloads"); String receipt=CommunityDownloadCache.save(cache,new CommunitySearchHttpClient.Fetched(details,index.download(ref,env)),env,Clock.systemUTC());
        return ComponentSourceBinding.install(cache,receipt,context(world,alice));
    }
    @Test void recipeMovesToNewOwnerAndDeviceWithoutSourceAssetAndReusesAnIdenticalImport() throws Exception {
        var saved=recipe(alice,world,asset,"七格柱"); byte[] result=ComponentRecipeLibrary.result(alice,saved.assetId()).toBytes();
        Path file=temp.resolve("recipe.fcbackup"); var backup=PortableAssetBackup.exportRecipe(alice,saved.assetId(),file); targetDevice();
        assertEquals(backup,PortableAssetBackup.inspect(file)); assertEquals(alice,backup.originalOwner()); assertFalse(Files.exists(temp.resolve("new-library")));
        assertFalse(PortableAssetBackup.importArchive(file,backup.sha256(),next,bob).reused());
        assertArrayEquals(result,ComponentRecipeLibrary.result(bob,saved.assetId()).toBytes());
        assertEquals(bob.toString(),ComponentRecipeLibrary.backupRead(ComponentStorage.getPlayerComponentDir(bob),bob,saved.assetId()).creator());
        assertTrue(PortableAssetBackup.importArchive(file,backup.sha256(),next,bob).reused());
        assertTrue(ComponentStorage.loadCatalog(next,bob).components.stream().allMatch(e -> e.recipe));
        ComponentRecipeLibrary.replay(bob,saved.assetId());
        var restored=ComponentRecipeLibrary.restore(context(next,bob),saved.assetId());
        assertEquals(original.manifestSha256(),ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(bob),restored.assetId()).manifestSha256());
    }
    @Test void recipeKeepsIndependentCommunityRightsAndOriginalBytes() throws Exception {
        var selected=community(); assertEquals(asset,selected.assetId());
        var saved=ComponentRecipeLibrary.saveSelected(world,alice,selected,new ComponentRecipeLibrary.Spec("选中来源",1,7,1));
        Path file=temp.resolve("community.fcbackup"); var backup=PortableAssetBackup.exportRecipe(alice,saved.assetId(),file); assertEquals(5,backup.files()); targetDevice();
        PortableAssetBackup.importArchive(file,backup.sha256(),next,bob);
        var restored=ComponentRecipeLibrary.restoreSelected(context(next,bob),saved.assetId());
        assertEquals(selected.receipt().receiptId(),restored.receipt().receiptId()); assertEquals("Publisher B",restored.source().manifest().author());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED,restored.source().manifest().trainingPermission());
        assertArrayEquals(selected.receipt().download().originalBytes(),restored.receipt().download().originalBytes());
        ComponentRecipeLibrary.replay(bob,saved.assetId());
    }
    @Test void buildingLockMovesExactPatchesAndTwoSourceUsesAcrossWorldAndOwner() throws Exception {
        var selected=community(); var patches=new ArrayList<BlockPatch>(); BuildingAssetCapture.Data data;
        try(var capture=BuildingAssetCapture.open(world,alice)) {
            patches.addAll(ComponentDefinitionCompiler.compile(ComponentStorage.loadComponent(world,asset,alice),Direction.SOUTH,new ComponentTransform(Direction.EAST,Mirror.NONE),0,0,0,false,null,1));
            patches.addAll(selected.prepare(world,alice,null).compile(world,alice,Direction.SOUTH,new ComponentTransform(Direction.WEST,Mirror.X),3,0,0,false,null,2));
            data=capture.data();
        }
        assertEquals(2,data.sources().size()); String id=BuildingAssetLocks.save(world,alice,"minecraft:overworld",19,10,70,20,"{}",data,patches);
        var old=BuildingAssetLocks.load(world,alice,id,"minecraft:overworld");
        Path file=temp.resolve("lock.fcbackup"); var backup=PortableAssetBackup.exportLock(world,alice,id,file); targetDevice();
        assertFalse(PortableAssetBackup.importArchive(file,backup.sha256(),next,bob).reused());
        assertTrue(PortableAssetBackup.importArchive(file,backup.sha256(),next,bob).reused());
        var result=BuildingAssetLocks.load(next,bob,id,"minecraft:overworld");
        assertEquals(old.patches(),result.patches()); assertEquals(old.inputPlan(),result.inputPlan()); assertEquals(old.manifest().patchesSha256(),result.manifest().patchesSha256());
        assertEquals(bob.toString(),result.manifest().owner()); assertEquals(old.manifest().producerVersion(),result.manifest().producerVersion());
        assertEquals(old.manifest().uses(),result.manifest().uses()); assertEquals(old.manifest().dependencies(),result.manifest().dependencies());
        assertThrows(IOException.class,()->BuildingAssetLocks.load(next,alice,id,"minecraft:overworld"));
        assertThrows(IOException.class,()->BuildingAssetLocks.load(next,bob,id,"minecraft:the_nether"));
        var restored=BuildingAssetLocks.restore(next,bob,id,"minecraft:overworld"); assertTrue(restored.complete());
        assertEquals(selected.receipt().receiptId(),restored.selections().get(1).receipt().receiptId());
        assertArrayEquals(selected.receipt().download().originalBytes(),restored.selections().get(1).receipt().download().originalBytes());
    }
    private static Map<String,byte[]> unpack(Path file) throws IOException {
        var map=new LinkedHashMap<String,byte[]>(); try(var zip=new ZipFile(file.toFile())) {
            var entries=zip.entries(); while(entries.hasMoreElements()) { var e=entries.nextElement(); try(var in=zip.getInputStream(e)) { map.put(e.getName(),in.readAllBytes()); } }
        } return map;
    }
    private Path zip(String name,Map<String,byte[]> files) throws IOException {
        Path target=temp.resolve(name+".fcbackup"); try(var out=new ZipOutputStream(Files.newOutputStream(target))) {
            for(var entry:files.entrySet()) { out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry(); }
        } return target;
    }
    private static String index(Map<String,byte[]> files) { return new String(files.get("backup.json"),StandardCharsets.UTF_8); }
    @Test void tamperingTraversalDuplicateIndexFieldsAndChangedApprovalDoNotWriteTarget() throws Exception {
        var saved=recipe(alice,world,asset,"Original"); Path file=temp.resolve("valid.fcbackup"); var backup=PortableAssetBackup.exportRecipe(alice,saved.assetId(),file); targetDevice();
        assertThrows(IOException.class,()->PortableAssetBackup.importArchive(file,"0".repeat(64),next,bob));
        var damaged=unpack(file); byte[] data=damaged.get("recipes/"+saved.assetId()+"/result.fccomponent.json"); data[0]^=1;
        assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("damaged",damaged)));
        var traversal=unpack(file); traversal.put("../escape",new byte[]{1}); assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("traversal",traversal)));
        var duplicate=unpack(file); duplicate.put("backup.json",("{\"kind\":\"RECIPE\","+index(duplicate).substring(1)).getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("duplicate",duplicate)));
        assertFalse(Files.exists(temp.resolve("new-library"))); assertEquals(backup,PortableAssetBackup.inspect(file));
    }
    @Test void fullyHashedButUnreferencedFileAndMissingDependencyAreRejected() throws Exception {
        var saved=recipe(alice,world,asset,"Original"); Path file=temp.resolve("valid.fcbackup"); PortableAssetBackup.exportRecipe(alice,saved.assetId(),file);
        var extra=unpack(file); var header=JsonParser.parseString(index(extra)).getAsJsonObject();
        String name="snapshots/"+"0".repeat(64)+"_"+"1".repeat(64)+"/source.fccomponent.json"; byte[] bytes=original.toBytes();
        var blob=new com.google.gson.JsonObject(); blob.addProperty("length",bytes.length); blob.addProperty("sha256",BuildingAssetCapture.digest(bytes));
        header.getAsJsonObject("files").add(name,blob); extra.put(name,bytes); extra.put("backup.json",header.toString().getBytes(StandardCharsets.UTF_8));
        var failure=assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("extra",extra))); assertTrue(failure.getMessage().contains("多余"));
        var missing=unpack(file); missing.keySet().removeIf(k -> k.startsWith("snapshots/"));
        assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("missing",missing)));
    }
    @Test void sameIdentityDifferentRecordAndExistingExportAreNeverOverwritten() throws Exception {
        var saved=recipe(alice,world,asset,"Exported"); Path file=temp.resolve("record.fcbackup"); var backup=PortableAssetBackup.exportRecipe(alice,saved.assetId(),file);
        assertThrows(FileAlreadyExistsException.class,()->PortableAssetBackup.exportRecipe(alice,saved.assetId(),file)); assertEquals(backup,PortableAssetBackup.inspect(file));
        targetDevice(); String targetAsset=install(original,bob,next); var local=recipe(bob,next,targetAsset,"Keep target"); assertEquals(saved.assetId(),local.assetId());
        assertThrows(IOException.class,()->PortableAssetBackup.importArchive(file,backup.sha256(),next,bob));
        assertEquals("Keep target",ComponentRecipeLibrary.result(bob,local.assetId()).component().name);
    }
    @Test void declaredFileAndIndexLimitsRejectSmallCompressedAbuse() throws Exception {
        var saved=recipe(alice,world,asset,"Original"); Path file=temp.resolve("valid.fcbackup"); PortableAssetBackup.exportRecipe(alice,saved.assetId(),file);
        var entries=unpack(file); var header=JsonParser.parseString(index(entries)).getAsJsonObject();
        header.getAsJsonObject("files").getAsJsonObject("recipes/"+saved.assetId()+"/result.fccomponent.json").addProperty("length",ComponentPackageIO.MAX_PACKAGE_BYTES+1);
        entries.put("backup.json",header.toString().getBytes(StandardCharsets.UTF_8)); assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("oversize",entries)));
        var large=" ".repeat(PortableAssetBackup.MAX_INDEX+1).getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->PortableAssetBackup.inspect(zip("index-limit",Map.of("backup.json",large))));
        assertFalse(PortableAssetBackup.fileName("../private.fcbackup")); assertTrue(PortableAssetBackup.fileName("My_house-1.fcbackup"));
    }
}
