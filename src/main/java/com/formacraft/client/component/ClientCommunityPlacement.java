package com.formacraft.client.component;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.common.network.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.io.IOException;

public final class ClientCommunityPlacement {
    private ClientCommunityPlacement() {}
    private static final CommunityPlacementSelection SELECTION=new CommunityPlacementSelection();
    public static void clear() { SELECTION.clear(); }
    public static boolean active() { return SELECTION.active(ClientCommunityTasks.STATE); }
    public static CommunitySelectionRef reference(String assetId) { return SELECTION.reference(ClientCommunityTasks.STATE,assetId); }
    public static void activate() throws IOException {
        var state=ClientCommunityTasks.STATE; var current=CommunityPlacementSelection.current(state);
        if(current==null || ClientComponentLibraryOperations.STATE.busy() || !ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) throw new IOException("Source cannot be loaded");
        var catalog=ClientComponentCatalogState.getCatalog();
        var entry=catalog==null || catalog.components==null ? null : catalog.components.stream().filter(e -> e!=null && current.assetId().equals(e.id)).findFirst().orElse(null);
        if(entry==null || entry.packageRef==null) throw new IOException("Refresh the local component catalog first");
        var tool=ComponentTool.INSTANCE.getState(); tool.librarySelectedId=current.assetId(); tool.librarySelectedName=state.installed().name();
        ComponentTool.INSTANCE.requestLoadSelectedComponent();
        if(!ClientComponentLibraryOperations.STATE.busy()) throw new IOException("Component load did not start");
        SELECTION.bind(state); tool.useLibrary=true;
    }
}
