package com.formacraft.common.patch;

import com.fasterxml.jackson.annotation.*;
import com.formacraft.common.build.GeometrySource;
import java.util.Objects;

/** Immutable relative write. Internal provenance is excluded from JSON and geometric equality. */
@JsonIgnoreProperties("source")
public final class BlockPatch {
    private final String action;
    private final int dx, dy, dz;
    private final String targetBlock;
    @JsonIgnore private final transient GeometrySource source;
    public static final String PLACE = "place";
    public static final String REPLACE = "replace";
    public static final String REMOVE = "remove";

    @JsonCreator
    public BlockPatch(@JsonProperty("action") String action, @JsonProperty("dx") int dx,
                      @JsonProperty("dy") int dy, @JsonProperty("dz") int dz,
                      @JsonProperty("targetBlock") String targetBlock) {
        this(action, dx, dy, dz, targetBlock, null);
    }

    public BlockPatch(String action, int dx, int dy, int dz, String targetBlock, GeometrySource source) {
        this.action = action; this.dx = dx; this.dy = dy; this.dz = dz;
        this.targetBlock = targetBlock; this.source = source;
    }
    @JsonProperty public String action() { return action; }
    @JsonProperty public int dx() { return dx; }
    @JsonProperty public int dy() { return dy; }
    @JsonProperty public int dz() { return dz; }
    @JsonProperty public String targetBlock() { return targetBlock; }
    @JsonIgnore public GeometrySource source() { return source; }

    public BlockPatch translated(int x, int y, int z) {
        return new BlockPatch(action, dx+x, dy+y, dz+z, targetBlock, source);
    }

    public BlockPatch withSource(GeometrySource newSource) {
        return new BlockPatch(action, dx, dy, dz, targetBlock, newSource);
    }

    @Override public boolean equals(Object other) {
        return other instanceof BlockPatch p && dx == p.dx && dy == p.dy && dz == p.dz
                && Objects.equals(action,p.action) && Objects.equals(targetBlock,p.targetBlock);
    }
    @Override public int hashCode() {
        int hash = Objects.hashCode(action);
        hash = 31 * hash + dx; hash = 31 * hash + dy; hash = 31 * hash + dz;
        return 31 * hash + Objects.hashCode(targetBlock);
    }
    @Override public String toString() {
        return "BlockPatch[action="+action+", dx="+dx+", dy="+dy+", dz="+dz+", targetBlock="+targetBlock+"]";
    }
}
