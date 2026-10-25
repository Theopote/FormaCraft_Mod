package com.formacraft.common.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalRequestIntentTest {
    @Test void existingBuildingEditsAreRecognized() {
        assertTrue(IncrementalRequestIntent.isIncremental("修改刚才的小木屋：只增加一扇窗"));
        assertTrue(IncrementalRequestIntent.isIncremental("调整现有建筑的屋顶"));
        assertTrue(IncrementalRequestIntent.isIncremental("edit the previous cabin"));
    }
    @Test void newBuildingsAndNullAreNotEdits() {
        assertFalse(IncrementalRequestIntent.isIncremental(null));
        assertFalse(IncrementalRequestIntent.isIncremental("建一座小木屋，保持周围地形不变"));
        assertFalse(IncrementalRequestIntent.isIncremental("新建一座带玻璃窗的小屋"));
    }
}
