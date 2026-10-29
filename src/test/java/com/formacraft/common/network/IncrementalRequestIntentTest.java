package com.formacraft.common.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalRequestIntentTest {
    @Test void existingBuildingEditsAreRecognized() {
        assertTrue(IncrementalRequestIntent.isIncremental("只去掉刚才这座徽派民居朝向天井的木格窗"));
        assertTrue(IncrementalRequestIntent.isIncremental("修改刚才的小木屋：只增加一扇窗"));
        assertTrue(IncrementalRequestIntent.isIncremental("调整现有建筑的屋顶"));
        assertTrue(IncrementalRequestIntent.isIncremental("edit the previous cabin"));
        assertTrue(IncrementalRequestIntent.isIncremental(
                "修改上一次建筑：在正门右侧添加一扇 3格宽 2格高的玻璃窗，其余结构不动。"));
        assertTrue(IncrementalRequestIntent.isIncremental(
                "在正门右侧增加一扇 2格宽 2格高的玻璃窗，不要重建整栋，不要移动建筑。"));
    }
    @Test void newBuildingsAndNullAreNotEdits() {
        assertFalse(IncrementalRequestIntent.isIncremental(null));
        assertFalse(IncrementalRequestIntent.isIncremental("建一座小木屋，保持周围地形不变"));
        assertFalse(IncrementalRequestIntent.isIncremental("新建一座带玻璃窗的小屋"));
    }
}
