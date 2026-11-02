package com.formacraft.client.component;

import com.formacraft.common.component.ComponentCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentLibraryOrderingTest {
    private ComponentCatalog.Entry entry(String id, long saved) {
        var entry = new ComponentCatalog.Entry(); entry.id = id; entry.name = "Same name"; entry.updatedAtMs = saved; return entry;
    }
    @Test void mostRecentlyLoadedWinsBeforeSaveTimeAndTiesAreStable() {
        var loaded = entry("loaded",1); var saved = entry("saved",100); var tie = entry("a",1);
        var list = new ArrayList<>(List.of(saved,loaded,tie));
        list.sort(ComponentLibraryOrdering.comparator("LOADED",e -> 0,e -> e == saved ? 1 : 10));
        assertEquals(List.of(tie,loaded,saved),list);
    }
    @Test void strongerTagMatchWinsBeforeNewerSaveAndSecondaryTimeIsDescending() {
        var exactOld = entry("exact_old",1); var exactNew = entry("exact_new",2); var nameNew = entry("name",100);
        var list = new ArrayList<>(List.of(nameNew,exactOld,exactNew));
        list.sort(ComponentLibraryOrdering.comparator("TAG_MATCH",e -> e == nameNew ? 2 : 3,e -> 0));
        assertEquals(List.of(exactNew,exactOld,nameNew),list);
    }
}
