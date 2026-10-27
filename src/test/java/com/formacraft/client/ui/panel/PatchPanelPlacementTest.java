package com.formacraft.client.ui.panel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PatchPanelPlacementTest {
    @Test void compactPanelAndAllButtonsStayOutsideSidebar() {
        var box=new PatchPanelPlacement().layout(480,254,160);
        assertTrue(box.x()>160);assertTrue(box.width()<=240);assertTrue(box.height()<=110);
        for(int i=0;i<4;i++) {assertTrue(box.contains(box.buttonX(i),box.buttonY(i)));assertTrue(box.buttonX(i)+box.buttonWidth()<=box.x()+box.width());}
        assertFalse(box.contains(150,200));assertTrue(box.title(box.x()+10,box.y()+10));
    }
    @Test void dragIsClampedAndPositionSurvivesLayout() {
        var state=new PatchPanelPlacement();state.move(-100,1000);var box=state.layout(480,254,160);
        assertTrue(box.x()>160);assertTrue(box.y()+box.height()<=246);
        state.move(200,20);assertEquals(200,state.layout(480,254,160).x());assertEquals(20,state.layout(480,254,160).y());
    }
    @Test void smallWindowUsesTwoButtonRowsAndReclampsAfterResize() {
        var state=new PatchPanelPlacement();state.move(400,200);var box=state.layout(320,240,160);
        assertEquals(2,box.columns());assertTrue(box.x()>160);assertTrue(box.x()+box.width()<=312);
        assertTrue(box.buttonY(2)>box.buttonY(0));assertTrue(box.buttonWidth()>=28);
    }
}
