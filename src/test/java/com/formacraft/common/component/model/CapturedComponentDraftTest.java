package com.formacraft.common.component.model;

import com.formacraft.client.tool.ComponentToolState;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedComponentDraftTest {
    @Test void cancelRestoresPermissionsFromSnapshot() {
        var state=new ComponentToolState();state.beginCapture();
        state.captureDraft.adaptation.modes[1]=CaptureAdaptationSettings.Mode.REPEAT;
        state.captureDraft.adaptation.allowMaterialSwap=true;
        state.syncDraftToState();assertTrue(state.adaptation.allowMaterialSwap);
        state.cancelCapture();assertFalse(state.adaptation.allowMaterialSwap);
        assertEquals(CaptureAdaptationSettings.Mode.FIXED,state.adaptation.modes[1]);
    }
    @Test void commitRetainsPermissionsAndSnapshotsAreIndependent() {
        var state=new ComponentToolState();state.beginCapture();
        state.captureDraft.adaptation.modes[1]=CaptureAdaptationSettings.Mode.REPEAT;
        state.captureDraft.adaptation.fixedEdges[1]=2;
        state.captureDraft.adaptation.setFixedEdge(1,true,1);state.commitCapture();
        assertEquals(CaptureAdaptationSettings.Mode.REPEAT,state.adaptation.modes[1]);
        state.captureDraft.adaptation.fixedEdges[1]=3;
        state.captureDraft.adaptation.setFixedEdge(1,true,3);
        assertEquals(2,state.adaptation.fixedEdges[1]);assertEquals(2,state.confirmedDraft.adaptation.fixedEdges[1]);
        assertEquals(1,state.adaptation.fixedEnd(1));assertEquals(1,state.confirmedDraft.adaptation.fixedEnd(1));
    }
}
