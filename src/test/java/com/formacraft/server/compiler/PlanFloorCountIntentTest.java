package com.formacraft.server.compiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanFloorCountIntentTest {
    @Test void localFloorFeaturesDoNotDeclareTheWholeBuildingsStoreyCount() {
        for (var text : java.util.List.of("高20格的赛博朋克住宅，二层凸出的植物露台",
                "在二层设置阳台", "第3层设有窗户", "二层与三层之间设有腰线", "二层凸出露台\n屋顶有灯牌"))
            assertEquals(0, PlanIntentFidelityRepair.parseFloorCount(text), text);
    }
    @Test void explicitTotalsAndArchitecturalNamesRemainValid() {
        assertEquals(5, PlanIntentFidelityRepair.parseFloorCount("五层住宅，二层凸出植物露台"));
        assertEquals(3, PlanIntentFidelityRepair.parseFloorCount("三层住宅，二层与三层之间悬挑一格"));
        assertEquals(3, PlanIntentFidelityRepair.parseFloorCount("三层和式住宅"));
        assertEquals(2, PlanIntentFidelityRepair.parseFloorCount("共二层设有宽敞房间"));
        assertEquals(2, PlanIntentFidelityRepair.parseFloorCount("建造二层住宅"));
        assertEquals(3, PlanIntentFidelityRepair.parseFloorCount("three-storey house"));
    }
}
