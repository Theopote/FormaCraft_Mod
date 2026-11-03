package com.formacraft.client.component;

import com.formacraft.common.network.CommunitySelectionRef;
import java.util.Objects;

/** Pure client binding: a stale selected source must never become a plain alias placement. */
public final class CommunityPlacementSelection {
    public record Binding(CommunitySelectionRef ref,String assetId,String receiptId) {}
    private Binding binding;
    public static Binding current(CommunityTaskClientState state) {
        if(state.phase()!=CommunityTaskClientState.Phase.READY || state.installed()==null || state.sessionId()==null || state.taskId()==null || state.receiptId().isEmpty()) return null;
        return new Binding(new CommunitySelectionRef(state.sessionId(),state.taskId()),state.installed().assetId(),state.receiptId());
    }
    public void bind(CommunityTaskClientState state) { binding=Objects.requireNonNull(current(state),"No installed source"); }
    public void clear() { binding=null; }
    public boolean active(CommunityTaskClientState state) { return binding!=null && binding.equals(current(state)); }
    public CommunitySelectionRef reference(CommunityTaskClientState state,String assetId) {
        if(binding==null || !binding.assetId().equals(assetId)) return null;
        if(!active(state)) throw new IllegalStateException("Selected source expired; explicit ordinary reload required");
        return binding.ref();
    }
}
