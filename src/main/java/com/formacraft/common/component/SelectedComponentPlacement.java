package com.formacraft.common.component;

import com.formacraft.common.component.transform.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Background-only preparation: no entities, world reads, ticket publication or world mutation. */
public final class SelectedComponentPlacement {
    private SelectedComponentPlacement() {}
    public record Compiled(List<BlockPatch> patches,String receiptId,String contentRef,String manifestSha256) {
        public Compiled { patches=List.copyOf(patches); }
    }
    public static Compiled compile(Path world,UUID owner,ComponentSourceBinding.Selection selected,String requestedAsset,
                                   Direction facing,Mirror mirror,boolean material,String style,long seed) throws IOException {
        if(selected==null || !selected.assetId().equals(requestedAsset) || facing==null || !facing.getAxis().isHorizontal() || mirror==null
                || style!=null && (style.length()>128 || style.codePoints().anyMatch(Character::isISOControl))) throw new IOException("Invalid selected placement");
        var prepared=selected.prepare(world,owner,null); var definition=prepared.definition();
        Direction from=Direction.SOUTH;
        try { if(definition.anchor!=null && definition.anchor.facing!=null) from=Direction.valueOf(definition.anchor.facing); }
        catch(IllegalArgumentException invalid) { throw new IOException("Invalid source facing"); }
        try(var capture=BuildingAssetCapture.open(world,owner)) {
            var patches=prepared.compile(world,owner,from,new ComponentTransform(facing,mirror),0,0,0,material,style,seed);
            var uses=capture.data().uses(); String receipt=selected.receipt().receiptId();
            if(patches.isEmpty() || uses.size()!=1 || !receipt.equals(uses.getFirst().receiptId())) throw new IOException("Selected source not captured");
            return new Compiled(patches,receipt,selected.source().contentRef(),selected.source().manifestSha256());
        }
    }
}
