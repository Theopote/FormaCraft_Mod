package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.component.community.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityBrowserModelTest {
    private OfflineCommunityIndex index(int count) throws Exception {
        var index=new OfflineCommunityIndex("test",Clock.systemUTC());
        var env=new ComponentPackageIO.Environment("1.21.10",Map.of());
        for(int i=0;i<count;i++) {
            var d=new ComponentDefinition(); d.id="column"; d.name="相同名称";
            d.category=ComponentCategory.COLUMN; d.sourceOrigin="CAPTURED"; d.tags=List.of("stone");
            d.size=new ComponentDefinition.Size(); d.size.w=d.size.d=1; d.size.h=i+1;
            d.anchor=new ComponentDefinition.Anchor(); d.anchor.facing="SOUTH";
            var block=new ComponentDefinition.BlockEntry(); block.block="minecraft:stone"; d.blocks=List.of(block);
            var manifest=new ComponentPackageIO.Manifest("author","1","Author","LicenseRef-test",ComponentPackageIO.TrainingPermission.DENIED,"1.21.10",Map.of(),Map.of(),List.of());
            var pack=ComponentPackageIO.create(d,manifest,env); byte[] bytes=pack.toBytes();
            var ref=new CommunityPackages.ReleaseRef("test","resource","release_"+i,CommunityPackages.sha256(bytes),pack.sha256(),pack.manifestSha256());
            index.add(new CommunityPackages.Descriptor(ref,bytes.length,ComponentPackageIO.SCHEMA,ComponentContentIdentity.fingerprints(d)),bytes);
        }
        return index;
    }
    private CommunitySearchResponse.Decoded page(OfflineCommunityIndex index,byte[] bytes) throws Exception {
        var request=CommunitySearchContract.decode(bytes);
        return CommunitySearchResponse.decode(CommunitySearchResponse.search(index,request,Clock.systemUTC()),request,"test");
    }
    @Test void formPreservesExactTypesAndAllTagsWithoutInventingCapabilities() throws Exception {
        var model=new CommunityBrowserModel();
        var query=CommunitySearchContract.decode(model.search("formacraft:column"," stone, 古典，stone, "));
        assertEquals("formacraft:column",query.query().exactTypeKey()); assertEquals(Set.of("stone","古典"),query.query().hardTags());
        assertNull(query.query().keywords()); assertNull(query.environment());
        assertEquals(1,page(index(1),model.search(null,"")).candidates().size());
        var custom=ComponentTypes.custom("我的窗");
        assertEquals(custom.key(),CommunitySearchContract.decode(model.search(custom.key(),"")).query().exactTypeKey());
    }
    @Test void pagingKeepsSubmittedQueryAndRejectsDifferentQueryAndClear() throws Exception {
        var model=new CommunityBrowserModel(); var index=index(11);
        var request=CommunitySearchContract.decode(model.search("formacraft:column","stone"));
        var first=page(index,model.search("formacraft:column","stone")); assertTrue(CommunityBrowserModel.hasNext(first));
        var next=CommunitySearchContract.decode(model.nextPage(first));
        assertEquals(request.queryFingerprint(),next.queryFingerprint()); assertNotEquals(request.requestId(),next.requestId()); assertNotNull(next.cursor());
        var last=page(index,model.nextPage(first)); assertEquals(1,last.candidates().size()); assertFalse(CommunityBrowserModel.hasNext(last));
        assertThrows(java.io.IOException.class,()->model.nextPage(last));
        model.search(null,""); assertThrows(java.io.IOException.class,()->model.nextPage(first));
        model.clear(); assertThrows(java.io.IOException.class,()->model.nextPage(first));
    }
    @Test void identicalDisplayNamesKeepSeparateReleaseIdentitiesAndRights() throws Exception {
        var model=new CommunityBrowserModel(); var decoded=page(index(2),model.search(null,""));
        var rows=CommunityBrowserModel.rows(decoded); assertEquals(2,rows.size()); assertEquals(rows.get(0).title(),rows.get(1).title());
        assertNotEquals(rows.get(0).releaseId(),rows.get(1).releaseId());
        for(int i=0;i<rows.size();i++) {
            assertEquals(decoded.candidates().get(i).releaseRef().releaseId(),rows.get(i).releaseId());
            assertEquals("LicenseRef-test",rows.get(i).license()); assertEquals("DENIED",rows.get(i).trainingPermission());
            assertEquals("柱",rows.get(i).type());
        }
        assertThrows(UnsupportedOperationException.class,()->rows.clear());
    }
    @Test void presentationStripsFormattingAndBidiAndTruncatesByCodePoint() {
        assertEquals("Author",CommunityBrowserModel.display("§cAu\u202Eth\nor",30));
        assertEquals("😀窗…",CommunityBrowserModel.display("😀窗口",2));
        assertEquals("未声明",CommunityBrowserModel.display("\u202E\n",10));
    }
    @Test void invalidFiltersDoNotReplaceLastSubmittedQuery() throws Exception {
        var model=new CommunityBrowserModel(); var first=page(index(11),model.search(null,""));
        assertThrows(java.io.IOException.class,()->model.search("../../bad",""));
        assertNotNull(CommunitySearchContract.decode(model.nextPage(first)).cursor());
        assertThrows(java.io.IOException.class,()->model.search(null,"x".repeat(129)));
    }
}
